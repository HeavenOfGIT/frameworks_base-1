/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.infra;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.infra.AbstractRemoteService;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.PrintWriter;
import java.util.List;

/**
 * Base class for {@link SystemService SystemServices} that support multi user.
 *
 * <p>Subclasses of this service are just a facade for the service binder calls - the "real" work
 * is done by the {@link AbstractPerUserSystemService} subclasses, which are automatically managed
 * through an user -> service cache.
 *
 * <p>It also takes care of other plumbing tasks such as:
 *
 * <ul>
 *   <li>Disabling the service when {@link UserManager} restrictions change.
 *   <li>Refreshing the service when its underlying
 *   {@link #getServiceSettingsProperty() Settings property} changed.
 *   <li>Calling the service when other Settings properties changed.
 * </ul>
 *
 * <p>See {@code com.android.server.autofill.AutofillManagerService} for a concrete
 * (no pun intended) example of how to use it.
 *
 * @param <M> "master" service class.
 * @param <S> "real" service class.
 *
 * @hide
 */
// TODO(b/117779333): improve javadoc above instead of using Autofill as an example
public abstract class AbstractMasterSystemService<M extends AbstractMasterSystemService<M, S>,
        S extends AbstractPerUserSystemService<S, M>> extends SystemService {

    /**
     * Log tag
     */
    protected final String mTag = getClass().getSimpleName();

    /**
     * Lock used to synchronize access to internal state; should be acquired before calling a
     * method whose name ends with {@code locked}.
     */
    protected final Object mLock = new Object();

    /**
     * Object used to define the name of the service component used to create
     * {@link com.android.internal.infra.AbstractRemoteService} instances.
     */
    @Nullable
    protected final ServiceNameResolver mServiceNameResolver;

    /**
     * Whether the service should log debug statements.
     */
    //TODO(b/117779333): consider using constants for these guards
    public boolean verbose = false;

    /**
     * Whether the service should log verbose statements.
     */
    //TODO(b/117779333): consider using constants for these guards
    public boolean debug = false;

    /**
     * Whether the service is allowed to bind to an instant-app.
     */
    @GuardedBy("mLock")
    protected boolean mAllowInstantService;

    /**
     * Users disabled due to {@link UserManager} restrictions, or {@code null} if the service cannot
     * be disabled through {@link UserManager}.
     */
    @GuardedBy("mLock")
    @Nullable
    private final SparseBooleanArray mDisabledByUserRestriction;

    /**
     * Cache of services per user id.
     */
    @GuardedBy("mLock")
    private final SparseArray<S> mServicesCache = new SparseArray<>();

    /**
     * Whether the per-user service should be removed from the cache when its apk is updated.
     */
    private final boolean mRefreshServiceOnPackageUpdate;

    /**
     * Name of the service's package that was active but then was removed because its package
     * update.
     *
     * <p>It's a temporary state set / used by the {@link PackageMonitor} implementation, but
     * defined here so it can be dumped.
     */
    @GuardedBy("mLock")
    private String mLastActivePackageName;

    /**
     * Default constructor.
     *
     * <p>When using this constructor, the {@link AbstractPerUserSystemService} is removed from
     * the cache (and re-added) when the service package is updated.
     *
     * @param context system context.
     * @param serviceNameResolver resolver for
     * {@link com.android.internal.infra.AbstractRemoteService} instances, or
     * {@code null} when the service doesn't bind to remote services.
     * @param disallowProperty when not {@code null}, defines a {@link UserManager} restriction that
     *        disables the service. <b>NOTE: </b> you'll also need to add it to
     *        {@code UserRestrictionsUtils.USER_RESTRICTIONS}.
     */
    protected AbstractMasterSystemService(@NonNull Context context,
            @Nullable ServiceNameResolver serviceNameResolver,
            @Nullable String disallowProperty) {
        this(context, serviceNameResolver, disallowProperty,
                /* refreshServiceOnPackageUpdate=*/ true);
    }

    /**
     * Full constructor.
     *
     * @param context system context.
     * @param serviceNameResolver resolver for
     * {@link com.android.internal.infra.AbstractRemoteService} instances, or
     * {@code null} when the service doesn't bind to remote services.
     * @param disallowProperty when not {@code null}, defines a {@link UserManager} restriction that
     *        disables the service. <b>NOTE: </b> you'll also need to add it to
     *        {@code UserRestrictionsUtils.USER_RESTRICTIONS}.
     * @param refreshServiceOnPackageUpdate when {@code true}, the
     *        {@link AbstractPerUserSystemService} is removed from the cache (and re-added) when the
     *        service package is updated; when {@code false}, the service is untouched during the
     *        update.
     */
    protected AbstractMasterSystemService(@NonNull Context context,
            @Nullable ServiceNameResolver serviceNameResolver,
            @Nullable String disallowProperty, boolean refreshServiceOnPackageUpdate) {
        super(context);

        mRefreshServiceOnPackageUpdate = refreshServiceOnPackageUpdate;

        mServiceNameResolver = serviceNameResolver;
        if (mServiceNameResolver != null) {
            mServiceNameResolver
                    .setOnTemporaryServiceNameChangedCallback(
                            (u, s) -> updateCachedServiceLocked(u));

        }
        if (disallowProperty == null) {
            mDisabledByUserRestriction = null;
        } else {
            mDisabledByUserRestriction = new SparseBooleanArray();
            // Hookup with UserManager to disable service when necessary.
            final UserManager um = context.getSystemService(UserManager.class);
            final UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
            final List<UserInfo> users = um.getUsers();
            for (int i = 0; i < users.size(); i++) {
                final int userId = users.get(i).id;
                final boolean disabled = umi.getUserRestriction(userId, disallowProperty);
                if (disabled) {
                    Slog.i(mTag, "Disabling by restrictions user " + userId);
                    mDisabledByUserRestriction.put(userId, disabled);
                }
            }
            umi.addUserRestrictionsListener((userId, newRestrictions, prevRestrictions) -> {
                final boolean disabledNow =
                        newRestrictions.getBoolean(disallowProperty, false);
                synchronized (mLock) {
                    final boolean disabledBefore = mDisabledByUserRestriction.get(userId);
                    if (disabledBefore == disabledNow) {
                        // Nothing changed, do nothing.
                        if (debug) {
                            Slog.d(mTag, "Restriction did not change for user " + userId);
                            return;
                        }
                    }
                    Slog.i(mTag, "Updating for user " + userId + ": disabled=" + disabledNow);
                    mDisabledByUserRestriction.put(userId, disabledNow);
                    updateCachedServiceLocked(userId, disabledNow);
                }
            });
        }
        startTrackingPackageChanges();
    }

    @Override // from SystemService
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            new SettingsObserver(BackgroundThread.getHandler());
        }
    }

    @Override // from SystemService
    public void onUnlockUser(int userId) {
        synchronized (mLock) {
            updateCachedServiceLocked(userId);
        }
    }

    @Override // from SystemService
    public void onCleanupUser(int userId) {
        synchronized (mLock) {
            removeCachedServiceLocked(userId);
        }
    }

    /**
     * Gets whether the service is allowed to bind to an instant-app.
     *
     * <p>Typically called by {@code ShellCommand} during CTS tests.
     *
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     */
    public final boolean getAllowInstantService() {
        enforceCallingPermissionForManagement();
        synchronized (mLock) {
            return mAllowInstantService;
        }
    }

    /**
     * Checks whether the service is allowed to bind to an instant-app.
     *
     * <p>Typically called by subclasses when creating {@link AbstractRemoteService} instances.
     *
     * <p><b>NOTE: </b>must not be called by {@code ShellCommand} as it does not check for
     * permission.
     */
    public final boolean isBindInstantServiceAllowed() {
        synchronized (mLock) {
            return mAllowInstantService;
        }
    }

    /**
     * Sets whether the service is allowed to bind to an instant-app.
     *
     * <p>Typically called by {@code ShellCommand} during CTS tests.
     *
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     */
    public final void setAllowInstantService(boolean mode) {
        Slog.i(mTag, "setAllowInstantService(): " + mode);
        enforceCallingPermissionForManagement();
        synchronized (mLock) {
            mAllowInstantService = mode;
        }
    }

    /**
     * Temporarily sets the service implementation.
     *
     * <p>Typically used by Shell command and/or CTS tests.
     *
     * @param componentName name of the new component
     * @param durationMs how long the change will be valid (the service will be automatically reset
     *            to the default component after this timeout expires).
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     * @throws IllegalArgumentException if value of {@code durationMs} is higher than
     *             {@link #getMaximumTemporaryServiceDurationMs()}.
     */
    public final void setTemporaryService(@UserIdInt int userId, @NonNull String componentName,
            int durationMs) {
        Slog.i(mTag, "setTemporaryService(" + userId + ") to " + componentName + " for "
                + durationMs + "ms");
        enforceCallingPermissionForManagement();

        Preconditions.checkNotNull(componentName);
        final int maxDurationMs = getMaximumTemporaryServiceDurationMs();
        if (durationMs > maxDurationMs) {
            throw new IllegalArgumentException(
                    "Max duration is " + maxDurationMs + " (called with " + durationMs + ")");
        }

        synchronized (mLock) {
            final S oldService = peekServiceForUserLocked(userId);
            if (oldService != null) {
                oldService.removeSelfFromCacheLocked();
            }
            mServiceNameResolver.setTemporaryService(userId, componentName, durationMs);
        }
    }

    /**
     * Sets whether the default service should be used.
     *
     * <p>Typically used during CTS tests to make sure only the default service doesn't interfere
     * with the test results.
     *
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     */
    public final void setDefaultServiceEnabled(@UserIdInt int userId, boolean enabled) {
        Slog.i(mTag, "setDefaultServiceEnabled() for userId " + userId + ": " + enabled);
        enforceCallingPermissionForManagement();

        synchronized (mLock) {
            final S oldService = peekServiceForUserLocked(userId);
            if (oldService != null) {
                oldService.removeSelfFromCacheLocked();
            }
            mServiceNameResolver.setDefaultServiceEnabled(userId, enabled);

            // Must update the service on cache so its initialization code is triggered
            updateCachedServiceLocked(userId);
        }
    }

    /**
     * Checks whether the default service should be used.
     *
     * <p>Typically used during CTS tests to make sure only the default service doesn't interfere
     * with the test results.
     *
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     */
    public final boolean isDefaultServiceEnabled(@UserIdInt int userId) {
        enforceCallingPermissionForManagement();

        synchronized (mLock) {
            return mServiceNameResolver.isDefaultServiceEnabled(userId);
        }
    }

    /**
     * Gets the maximum time the service implementation can be changed.
     *
     * @throws UnsupportedOperationException if subclass doesn't override it.
     */
    protected int getMaximumTemporaryServiceDurationMs() {
        throw new UnsupportedOperationException("Not implemented by " + getClass());
    }

    /**
     * Resets the temporary service implementation to the default component.
     *
     * <p>Typically used by Shell command and/or CTS tests.
     *
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     */
    public final void resetTemporaryService(@UserIdInt int userId) {
        Slog.i(mTag, "resetTemporaryService(): " + userId);
        enforceCallingPermissionForManagement();
        synchronized (mLock) {
            final S service = getServiceForUserLocked(userId);
            if (service != null) {
                service.resetTemporaryServiceLocked();
            }
        }
    }

    /**
     * Asserts that the caller has permissions to manage this service.
     *
     * <p>Typically called by {@code ShellCommand} implementations.
     *
     * @throws UnsupportedOperationException if subclass doesn't override it.
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     */
    protected void enforceCallingPermissionForManagement() {
        throw new UnsupportedOperationException("Not implemented by " + getClass());
    }

    /**
     * Creates a new service that will be added to the cache.
     *
     * @param resolvedUserId the resolved user id for the service.
     * @param disabled whether the service is currently disabled (due to {@link UserManager}
     * restrictions).
     *
     * @return a new instance.
     */
    @Nullable
    protected abstract S newServiceLocked(@UserIdInt int resolvedUserId, boolean disabled);

    /**
     * Register the service for extra Settings changes (i.e., other than
     * {@link android.provider.Settings.Secure#USER_SETUP_COMPLETE} or
     * {@link #getServiceSettingsProperty()}, which are automatically handled).
     *
     * <p> Example:
     *
     * <pre><code>
     * resolver.registerContentObserver(Settings.Global.getUriFor(
     *     Settings.Global.AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES), false, observer,
     *     UserHandle.USER_ALL);
     * </code></pre>
     *
     * <p><b>NOTE: </p>it doesn't need to register for
     * {@link android.provider.Settings.Secure#USER_SETUP_COMPLETE} or
     * {@link #getServiceSettingsProperty()}.
     *
     */
    @SuppressWarnings("unused")
    protected void registerForExtraSettingsChanges(@NonNull ContentResolver resolver,
            @NonNull ContentObserver observer) {
    }

    /**
     * Callback for Settings changes that were registered though
     * {@link #registerForExtraSettingsChanges(ContentResolver, ContentObserver)}.
     *
     * @param userId user associated with the change
     * @param property Settings property changed.
     */
    protected void onSettingsChanged(@UserIdInt int userId, @NonNull String property) {
    }

    /**
     * Gets the service instance for an user, creating an instance if not present in the cache.
     */
    @GuardedBy("mLock")
    @NonNull
    protected S getServiceForUserLocked(@UserIdInt int userId) {
        final int resolvedUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, false, null, null);
        S service = mServicesCache.get(resolvedUserId);
        if (service == null) {
            final boolean disabled = isDisabledLocked(userId);
            service = newServiceLocked(resolvedUserId, disabled);
            if (!disabled) {
                onServiceEnabledLocked(service, resolvedUserId);
            }
            mServicesCache.put(userId, service);
        }
        return service;
    }

    /**
     * Gets the <b>existing</b> service instance for a user, returning {@code null} if not already
     * present in the cache.
     */
    @GuardedBy("mLock")
    @Nullable
    protected S peekServiceForUserLocked(@UserIdInt int userId) {
        final int resolvedUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, false, null, null);
        return mServicesCache.get(resolvedUserId);
    }

    /**
     * Updates a cached service for a given user.
     */
    @GuardedBy("mLock")
    protected void updateCachedServiceLocked(@UserIdInt int userId) {
        updateCachedServiceLocked(userId, isDisabledLocked(userId));
    }

    /**
     * Checks whether the service is disabled (through {@link UserManager} restrictions) for the
     * given user.
     */
    protected boolean isDisabledLocked(@UserIdInt int userId) {
        return mDisabledByUserRestriction == null ? false : mDisabledByUserRestriction.get(userId);
    }

    /**
     * Updates a cached service for a given user.
     *
     * @param userId user handle.
     * @param disabled whether the user is disabled.
     * @return service for the user.
     */
    @GuardedBy("mLock")
    protected S updateCachedServiceLocked(@UserIdInt int userId, boolean disabled) {
        final S service = getServiceForUserLocked(userId);
        if (service != null) {
            service.updateLocked(disabled);
            if (!service.isEnabledLocked()) {
                removeCachedServiceLocked(userId);
            } else {
                onServiceEnabledLocked(service, userId);
            }
        }
        return service;
    }

    /**
     * Gets the Settings property that defines the name of the component name used to bind this
     * service to an external service, or {@code null} when the service is not defined by such
     * property (for example, if it's a system service defined by framework resources).
     */
    @Nullable
    protected String getServiceSettingsProperty() {
        return null;
    }

    /**
     * Callback called after a new service was added to the cache, or an existing service that was
     * previously disabled gets enabled.
     *
     * <p>By default doesn't do anything, but can be overridden by subclasses.
     */
    @SuppressWarnings("unused")
    protected void onServiceEnabledLocked(@NonNull S service, @UserIdInt int userId) {
    }

    /**
     * Removes a cached service for a given user.
     *
     * @return the removed service.
     */
    @GuardedBy("mLock")
    @NonNull
    protected final S removeCachedServiceLocked(@UserIdInt int userId) {
        final S service = peekServiceForUserLocked(userId);
        if (service != null) {
            mServicesCache.delete(userId);
            onServiceRemoved(service, userId);
        }
        return service;
    }

    /**
     * Called after the service is removed from the cache.
     */
    @SuppressWarnings("unused")
    protected void onServiceRemoved(@NonNull S service, @UserIdInt int userId) {
    }

    /**
     * Visits all services in the cache.
     */
    @GuardedBy("mLock")
    protected void visitServicesLocked(@NonNull Visitor<S> visitor) {
        final int size = mServicesCache.size();
        for (int i = 0; i < size; i++) {
            visitor.visit(mServicesCache.valueAt(i));
        }
    }

    /**
     * Clear the cache by removing all services.
     */
    @GuardedBy("mLock")
    protected void clearCacheLocked() {
        mServicesCache.clear();
    }

    // TODO(b/117779333): support proto
    protected void dumpLocked(@NonNull String prefix, @NonNull PrintWriter pw) {
        boolean realDebug = debug;
        boolean realVerbose = verbose;
        final String prefix2 = "    ";

        try {
            // Temporarily turn on full logging;
            debug = verbose = true;
            final int size = mServicesCache.size();
            pw.print(prefix); pw.print("Debug: "); pw.print(realDebug);
            pw.print(" Verbose: "); pw.println(realVerbose);
            pw.print(" Refresh on package update: "); pw.println(mRefreshServiceOnPackageUpdate);
            pw.print(" Last active service on update: "); pw.println(mLastActivePackageName);
            if (mServiceNameResolver != null) {
                pw.print(prefix); pw.print("Name resolver: ");
                mServiceNameResolver.dumpShort(pw); pw.println();
                final UserManager um = getContext().getSystemService(UserManager.class);
                final List<UserInfo> users = um.getUsers();
                for (int i = 0; i < users.size(); i++) {
                    final int userId = users.get(i).id;
                    pw.print(prefix2); pw.print(userId); pw.print(": ");
                    mServiceNameResolver.dumpShort(pw, userId); pw.println();
                }
            }
            pw.print(prefix); pw.print("Users disabled by restriction: ");
            pw.println(mDisabledByUserRestriction);
            pw.print(prefix); pw.print("Allow instant service: "); pw.println(mAllowInstantService);
            final String settingsProperty = getServiceSettingsProperty();
            if (settingsProperty != null) {
                pw.print(prefix); pw.print("Settings property: "); pw.println(settingsProperty);
            }
            pw.print(prefix); pw.print("Cached services: ");
            if (size == 0) {
                pw.println("none");
            } else {
                pw.println(size);
                for (int i = 0; i < size; i++) {
                    pw.print(prefix); pw.print("Service at "); pw.print(i); pw.println(": ");
                    final S service = mServicesCache.valueAt(i);
                    service.dumpLocked(prefix2, pw);
                    pw.println();
                }
            }
        } finally {
            debug = realDebug;
            verbose = realVerbose;
        }
    }

    private void startTrackingPackageChanges() {
        final PackageMonitor monitor = new PackageMonitor() {

            @Override
            public void onPackageUpdateStarted(String packageName, int uid) {
                synchronized (mLock) {
                    final String activePackageName = getActiveServicePackageNameLocked();
                    if (packageName.equals(activePackageName)) {
                        final int userId = getChangingUserId();
                        if (mRefreshServiceOnPackageUpdate) {
                            if (debug) {
                                Slog.d(mTag, "Removing service for user " + userId
                                        + " because package " + activePackageName
                                        + " is being updated");
                            }
                            mLastActivePackageName = activePackageName;
                            removeCachedServiceLocked(userId);
                        } else {
                            if (debug) {
                                Slog.d(mTag, "Holding service for user " + userId
                                        + " while package " + activePackageName
                                        + " is being updated");
                            }
                        }
                    }
                }
            }

            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                synchronized (mLock) {
                    String activePackageName = getActiveServicePackageNameLocked();
                    if (activePackageName == null) {
                        activePackageName = mLastActivePackageName;
                        mLastActivePackageName = null;
                    }
                    if (!packageName.equals(activePackageName)) {
                        handlePackageUpdateLocked(packageName);
                    }
                }
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    final S service = peekServiceForUserLocked(userId);
                    if (service != null) {
                        final ComponentName componentName = service.getServiceComponentName();
                        if (componentName != null) {
                            if (packageName.equals(componentName.getPackageName())) {
                                handleActiveServiceRemoved(userId);
                            }
                        }
                    }
                }
            }

            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages,
                    int uid, boolean doit) {
                synchronized (mLock) {
                    final String activePackageName = getActiveServicePackageNameLocked();
                    for (String pkg : packages) {
                        if (pkg.equals(activePackageName)) {
                            if (!doit) {
                                return true;
                            }
                            removeCachedServiceLocked(getChangingUserId());
                        } else {
                            handlePackageUpdateLocked(pkg);
                        }
                    }
                }
                return false;
            }

            private void handleActiveServiceRemoved(@UserIdInt int userId) {
                synchronized (mLock) {
                    removeCachedServiceLocked(userId);
                }
                final String serviceSettingsProperty = getServiceSettingsProperty();
                if (serviceSettingsProperty != null) {
                    Settings.Secure.putStringForUser(getContext().getContentResolver(),
                            serviceSettingsProperty, null, userId);
                }
            }

            private String getActiveServicePackageNameLocked() {
                final int userId = getChangingUserId();
                final S service = peekServiceForUserLocked(userId);
                if (service == null) {
                    return null;
                }
                final ComponentName serviceComponent = service.getServiceComponentName();
                if (serviceComponent == null) {
                    return null;
                }
                return serviceComponent.getPackageName();
            }

            @GuardedBy("mLock")
            private void handlePackageUpdateLocked(String packageName) {
                visitServicesLocked((s) -> s.handlePackageUpdateLocked(packageName));
            }
        };

        // package changes
        monitor.register(getContext(), null,  UserHandle.ALL, true);
    }

    /**
     * Visitor pattern.
     *
     * @param <S> visited class.
     */
    public interface Visitor<S> {
        /**
         * Visits a service.
         *
         * @param service the service to be visited.
         */
        void visit(@NonNull S service);
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver resolver = getContext().getContentResolver();
            final String serviceProperty = getServiceSettingsProperty();
            if (serviceProperty != null) {
                resolver.registerContentObserver(Settings.Secure.getUriFor(
                        serviceProperty), false, this, UserHandle.USER_ALL);
            }
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.USER_SETUP_COMPLETE), false, this, UserHandle.USER_ALL);
            registerForExtraSettingsChanges(resolver, this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, @UserIdInt int userId) {
            if (verbose) Slog.v(mTag, "onChange(): uri=" + uri + ", userId=" + userId);
            final String property = uri.getLastPathSegment();
            if (property.equals(getServiceSettingsProperty())
                    || property.equals(Settings.Secure.USER_SETUP_COMPLETE)) {
                synchronized (mLock) {
                    updateCachedServiceLocked(userId);
                }
            } else {
                onSettingsChanged(userId, property);
            }
        }
    }
}
