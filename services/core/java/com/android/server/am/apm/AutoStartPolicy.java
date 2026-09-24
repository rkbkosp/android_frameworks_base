/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.am.apm;

import android.annotation.Nullable;
import android.apm.ApmWhitelist;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.pm.PackageList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto-start allow list. Two facts hold the whole policy: a {@code Settings.Global} switch,
 * on by default, and the AUTO_START column of the per user whitelist table. An unlisted
 * package can be refused a cold background start or a cold broadcast delivery.
 *
 * <p>The list includes the user's entries and implicit exemptions. Foreground callers and
 * running targets are not auto-starts. A listed caller may also reach dependency services.
 *
 * <p>Two kinds of exemption. One is a fact that comes with a settings or package change, so
 * it is applied when the snapshot is published and costs a gate nothing: the platform
 * package, {@link ProtectionArbiter#isAlwaysExempt}, and every package signed with the
 * platform key. The other is applied when a gate is asked, because the fact comes and goes
 * without a settings write: the current input method and the enabled accessibility services.
 * Both are already tracked by this service, so no second copy is kept here.
 *
 * <p>The switch, the table, and the platform facts are read on the service thread. A gate
 * query is two set lookups and an integer compare, so a caller that holds the activity
 * manager lock calls {@link #shouldBlockStart} or {@link #shouldBlockBroadcast} without an
 * IPC.
 */
final class AutoStartPolicy {
    private static final String TAG = "Apm";

    /** {@code startService} gate. */
    static final int GATE_START = 0;
    /** {@code bindService} gate. */
    static final int GATE_BIND = 1;
    /** Broadcast delivery gate. */
    static final int GATE_BROADCAST = 2;
    private static final int GATE_COUNT = 3;

    /** Signed with the platform key, so no build can block it. */
    private static final String PLATFORM_PACKAGE = "android";

    static final String USAGE =
            "Error: apm autostart needs list|add <pkg>|remove <pkg>|enable|disable";

    /**
     * The switch value. Production reads {@code Settings.Global}; a test hands in its own map
     * so a gate can be exercised without a content provider.
     */
    interface Store {
        int getInt(String key, int defaultValue);

        /** @return null when the write was accepted, else a one-line shell error. */
        @Nullable
        String putInt(String key, int value);
    }

    /**
     * Packages that are never blocked while they hold the role. Backed by the observations
     * this service already runs: no second registration, no copy.
     */
    interface Roles {
        boolean holdsRole(@Nullable String packageName);

        /** Current input-method and enabled accessibility packages. For the dump only. */
        List<String> rolePackages();
    }

    /** Platform side facts. Both are read on the service thread, never from a gate. */
    interface PlatformSignatures {
        boolean isPlatformSigned(@Nullable String packageName);

        /**
         * Every installed package name.
         *
         * @return an empty list when the package manager is not up yet.
         */
        List<String> installedPackages();
    }

    /**
     * One read of the switch and the table. Immutable once published. {@code allowed} is the
     * AUTO_START column as read, the implicit whitelist included, so a pre-installed package
     * or a KernelSU manager is allowed without a row of its own; {@code exempt} is the set no
     * gate may refuse.
     */
    private static final class Snapshot {
        /**
         * Nothing is allowed and nothing is exempt. The state before the first read: every
         * gate keeps the answer it had, exactly as the switch off used to.
         */
        static final Snapshot EMPTY =
                new Snapshot(false, new ArraySet<String>(), new ArraySet<String>());

        final boolean enabled;
        final ArraySet<String> allowed;
        final ArraySet<String> exempt;

        Snapshot(boolean enabled, ArraySet<String> allowed, ArraySet<String> exempt) {
            this.enabled = enabled;
            this.allowed = allowed;
            this.exempt = exempt;
        }
    }

    private final Store mStore;
    private final ApmWhitelistTable mWhitelist;
    private final PlatformSignatures mSignatures;
    private final Roles mRoles;
    @Nullable
    private final ContentResolver mResolver;
    @Nullable
    private final ContentObserver mObserver;
    private final AtomicInteger[] mDenied = new AtomicInteger[GATE_COUNT];
    private volatile Snapshot mSnapshot = Snapshot.EMPTY;
    private boolean mRegistered;

    AutoStartPolicy(Store store, ApmWhitelistTable whitelist, PlatformSignatures signatures,
            Roles roles, @Nullable ContentResolver resolver, @Nullable Handler handler) {
        mStore = store;
        mWhitelist = whitelist;
        mSignatures = signatures;
        mRoles = roles;
        mResolver = resolver;
        mObserver = handler == null ? null : new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                    UserHandle user) {
                refresh();
            }
        };
        for (int i = 0; i < mDenied.length; i++) {
            mDenied[i] = new AtomicInteger();
        }
    }

    /**
     * Follows the switch and publishes the first read. Runs on the service thread: the
     * platform facts are a package list scan and must not run under the activity manager
     * lock. A test that supplied its own store does nothing here.
     */
    void systemReady() {
        if (mResolver == null || mObserver == null || mRegistered) {
            return;
        }
        mRegistered = true;
        try {
            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(ApmConstants.KEY_AUTO_START_ENABLED),
                    false, mObserver, UserHandle.USER_ALL);
        } catch (Throwable t) {
            Slog.w(TAG, "auto start settings observer not registered", t);
        }
        refresh();
    }

    /**
     * Re-reads the switch and the whitelist and publishes what they say. Disabling the gate
     * takes effect even if the table cannot be read. When enabling, a failed read keeps the
     * previous snapshot: an unreadable table must not become an empty allow list.
     */
    void refresh() {
        final boolean enabled;
        try {
            final int byDefault = ApmConstants.DEFAULT_AUTO_START_ENABLED ? 1 : 0;
            enabled = mStore.getInt(ApmConstants.KEY_AUTO_START_ENABLED, byDefault) != 0;
        } catch (Throwable t) {
            Slog.w(TAG, "auto start settings read failed", t);
            return;
        }
        if (!enabled) {
            final Snapshot previous = mSnapshot;
            mSnapshot = new Snapshot(false, previous.allowed, previous.exempt);
            return;
        }
        final ArraySet<String> allowed = readAllowed();
        if (allowed == null) {
            Slog.w(TAG, "auto start allow list read failed; keeping the previous one");
            return;
        }
        final ArraySet<String> exempt = readExempt();
        if (exempt == null) {
            Slog.w(TAG, "installed package list not available; keeping the previous exemptions");
            return;
        }
        mSnapshot = new Snapshot(enabled, allowed, exempt);
    }

    /**
     * The packages whose row holds AUTO_START, union over the alive users. The table is per
     * user, and a package one user allowed must not be refused because another user did not.
     *
     * @return null when any user's table could not be read.
     */
    @Nullable
    private ArraySet<String> readAllowed() {
        final int[] users = mWhitelist.userIds();
        final ArraySet<String> allowed = new ArraySet<>();
        for (int i = 0; i < users.length; i++) {
            final Map<String, Integer> table = mWhitelist.read(users[i]);
            if (table == null) {
                return null;
            }
            for (Map.Entry<String, Integer> entry : table.entrySet()) {
                final Integer bits = entry.getValue();
                if (bits != null && ApmWhitelist.has(bits, ApmWhitelist.AUTO_START)) {
                    allowed.add(entry.getKey());
                }
            }
        }
        return allowed;
    }

    /**
     * The packages no gate may refuse whatever the list says: the platform package, the
     * always exempt table of the arbiter, and every package signed with the platform key.
     * The signature fact costs a lookup per installed package, so it is read here, on the
     * service thread, and a gate only reads the set it produced.
     *
     * @return null when the installed package list is not available yet. An empty list is
     *         not a device with no packages, and must not replace the previous exemptions
     *         with a shorter set that would refuse the platform's own components.
     */
    @Nullable
    private ArraySet<String> readExempt() {
        final List<String> installed = mSignatures.installedPackages();
        if (installed == null || installed.isEmpty()) {
            return null;
        }
        final ArraySet<String> exempt = new ArraySet<>(installed.size() + 1);
        exempt.add(PLATFORM_PACKAGE);
        for (int i = 0; i < installed.size(); i++) {
            final String packageName = installed.get(i);
            if (ProtectionArbiter.isAlwaysExempt(packageName)
                    || mSignatures.isPlatformSigned(packageName)) {
                exempt.add(packageName);
            }
        }
        return exempt;
    }

    /**
     * True when the switch is on and {@code targetPackage} holds neither AUTO_START nor an
     * exemption. Every other installed package is refused: that is the allow list.
     */
    boolean blocks(@Nullable String targetPackage) {
        if (targetPackage == null) {
            return false;
        }
        final Snapshot snapshot = mSnapshot;
        return snapshot.enabled && !snapshot.allowed.contains(targetPackage)
                && !snapshot.exempt.contains(targetPackage);
    }

    /**
     * True when this caller may not start or bind {@code targetPackage}. Both packages must
     * be unlisted, the caller must be an ordinary app, and the target must hold no exempting
     * role. The caller's foreground state and the target's running state are checked by
     * {@link #shouldBlockColdStart}.
     */
    boolean shouldBlockStart(@Nullable String callerPackage, int callerUid,
            @Nullable String targetPackage) {
        if (targetPackage == null) {
            return false;
        }
        final Snapshot snapshot = mSnapshot;
        if (!snapshot.enabled || snapshot.allowed.contains(targetPackage)
                || snapshot.exempt.contains(targetPackage)) {
            return false;
        }
        // A process starting one of its own services is not an app auto-start request.
        if (targetPackage != null && targetPackage.equals(callerPackage)) {
            return false;
        }
        if (callerPackage != null && (snapshot.allowed.contains(callerPackage)
                || mRoles.holdsRole(callerPackage))) {
            return false;
        }
        if (isPlatformCaller(callerPackage, callerUid)) {
            return false;
        }
        return !mRoles.holdsRole(targetPackage);
    }

    /** A service request is an auto-start only when the caller is in the background and the
     * target app is not already running. */
    boolean shouldBlockColdStart(@Nullable String callerPackage, int callerUid,
            @Nullable String targetPackage, boolean callerForeground, boolean targetRunning) {
        return !callerForeground && !targetRunning
                && shouldBlockStart(callerPackage, callerUid, targetPackage);
    }

    /**
     * True when this delivery to {@code targetPackage} must be skipped. The sender is not
     * part of the answer: every system broadcast, {@code BOOT_COMPLETED} included, is sent
     * by the platform itself, so a sender test would exempt exactly the deliveries this gate
     * exists for.
     */
    boolean shouldBlockBroadcast(@Nullable String targetPackage) {
        return blocks(targetPackage) && !mRoles.holdsRole(targetPackage);
    }

    /** Count one refusal at {@code gate}. Read back by the dump. */
    void noteBlocked(int gate) {
        if (gate < 0 || gate >= GATE_COUNT) {
            return;
        }
        mDenied[gate].incrementAndGet();
    }

    int denied(int gate) {
        return gate < 0 || gate >= GATE_COUNT ? 0 : mDenied[gate].get();
    }

    boolean isEnabled() {
        return mSnapshot.enabled;
    }

    /** Sorted copy of the allow list: the packages whose row holds AUTO_START. */
    List<String> allowedPackages() {
        return sorted(mSnapshot.allowed);
    }

    int allowedCount() {
        return mSnapshot.allowed.size();
    }

    /** How many packages a gate may not refuse whatever the list says. */
    int exemptCount() {
        return mSnapshot.exempt.size();
    }

    /** Sorted copy of the current input-method and enabled accessibility packages. */
    List<String> rolePackages() {
        final List<String> roles = mRoles.rolePackages();
        return roles == null ? Collections.<String>emptyList() : roles;
    }

    String list() {
        final Snapshot snapshot = mSnapshot;
        final StringBuilder out = new StringBuilder();
        out.append("autoStart=").append(snapshot.enabled ? "on" : "off");
        out.append(" allowed=").append(snapshot.allowed.size());
        out.append(" exempt=").append(snapshot.exempt.size());
        if (snapshot.allowed.size() == 0) {
            return out.append(snapshot.enabled
                    ? " (no package listed; cold background starts may be refused)"
                    : " (gate is off)").toString();
        }
        final List<String> allowed = sorted(snapshot.allowed);
        for (int i = 0; i < allowed.size(); i++) {
            out.append("\n  ").append(allowed.get(i));
        }
        return out.toString();
    }

    /**
     * Adds AUTO_START to one user's row, writes the table back, and republishes. The other
     * columns of the row are kept: this is one column of the whitelist, not the whole row.
     */
    String add(int userId, @Nullable String packageName) {
        final String valid = validated(packageName);
        if (valid == null || userId < 0) {
            return "Error: apm autostart add requires a package name and a user";
        }
        final Map<String, Integer> table = mWhitelist.read(userId);
        if (table == null) {
            return "Error: whitelist read failed for user " + userId;
        }
        final Integer bits = table.get(valid);
        final int next = (bits == null ? 0 : bits) | ApmWhitelist.AUTO_START;
        if (bits != null && bits.intValue() == next) {
            return "already allowed " + valid;
        }
        final String error = mWhitelist.write(userId, valid, next);
        if (error != null) {
            return error;
        }
        refresh();
        return mSnapshot.exempt.contains(valid)
                ? "allowed " + valid + " (exempt, no gate is changed)"
                : "allowed " + valid;
    }

    /**
     * Clears AUTO_START from one user's row, writes the table back, and republishes. A row
     * that holds only other columns keeps them, and a row that holds nothing is removed.
     */
    String remove(int userId, @Nullable String packageName) {
        final String valid = validated(packageName);
        if (valid == null || userId < 0) {
            return "Error: apm autostart remove requires a package name and a user";
        }
        final Map<String, Integer> table = mWhitelist.read(userId);
        if (table == null) {
            return "Error: whitelist read failed for user " + userId;
        }
        final Integer bits = table.get(valid);
        if (bits == null || !ApmWhitelist.has(bits, ApmWhitelist.AUTO_START)) {
            return "not allowed " + valid;
        }
        final String error = mWhitelist.write(userId, valid, bits & ~ApmWhitelist.AUTO_START);
        if (error != null) {
            return error;
        }
        refresh();
        return "removed " + valid;
    }

    /** Turns the switch on or off and republishes. */
    String setEnabled(boolean enabled) {
        final String error =
                mStore.putInt(ApmConstants.KEY_AUTO_START_ENABLED, enabled ? 1 : 0);
        if (error != null) {
            return error;
        }
        refresh();
        return "autoStart=" + (mSnapshot.enabled ? "on" : "off");
    }

    static String gateName(int gate) {
        switch (gate) {
            case GATE_START:
                return "start";
            case GATE_BIND:
                return "bind";
            case GATE_BROADCAST:
                return "broadcast";
            default:
                return "gate" + gate;
        }
    }

    /** @return the trimmed package name, or null when it is not one. */
    @Nullable
    private static String validated(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        final String packageName = raw.trim();
        return ApmWhitelist.isValidPackageName(packageName) ? packageName : null;
    }

    /** Root, system, and shell act for the platform. The platform package name is the same. */
    private static boolean isPlatformCaller(@Nullable String callerPackage, int callerUid) {
        if (PLATFORM_PACKAGE.equals(callerPackage)) {
            return true;
        }
        final int appId = UserHandle.getAppId(callerUid);
        return appId == Process.ROOT_UID || appId == Process.SYSTEM_UID
                || appId == Process.SHELL_UID;
    }

    private static List<String> sorted(ArraySet<String> values) {
        final List<String> out = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            out.add(values.valueAt(i));
        }
        Collections.sort(out);
        return out;
    }

    /** {@code Settings.Global}, or nothing at all when the caller has no context. */
    static Store globalStore(@Nullable Context context) {
        final ContentResolver resolver = context == null ? null : context.getContentResolver();
        if (resolver == null) {
            return new Store() {
                @Override
                public int getInt(String key, int defaultValue) {
                    return defaultValue;
                }

                @Nullable
                @Override
                public String putInt(String key, int value) {
                    return "Error: settings are not available";
                }
            };
        }
        return new Store() {
            @Override
            public int getInt(String key, int defaultValue) {
                try {
                    return Settings.Global.getInt(resolver, key, defaultValue);
                } catch (Throwable t) {
                    return defaultValue;
                }
            }

            @Nullable
            @Override
            public String putInt(String key, int value) {
                try {
                    return Settings.Global.putInt(resolver, key, value)
                            ? null : "Error: settings write rejected";
                } catch (Throwable t) {
                    return "Error: settings write failed";
                }
            }
        };
    }

    /**
     * Platform signature and installed package list, both from the package manager's copy in
     * this process: no IPC and no second package database. That copy is resolved once and
     * kept, so a scan of the installed packages costs no binder call per package.
     */
    static PlatformSignatures platformSignatures() {
        return new PlatformSignatures() {
            @Nullable
            private PackageManagerInternal mPackageManager;

            private PackageManagerInternal packageManager() {
                if (mPackageManager == null) {
                    mPackageManager = LocalServices.getService(PackageManagerInternal.class);
                }
                return mPackageManager;
            }

            @Override
            public boolean isPlatformSigned(@Nullable String packageName) {
                final PackageManagerInternal packageManager = packageManager();
                return packageManager != null && packageName != null
                        && packageManager.isPlatformSigned(packageName);
            }

            @Override
            public List<String> installedPackages() {
                final PackageManagerInternal packageManager = packageManager();
                if (packageManager == null) {
                    return Collections.emptyList();
                }
                final PackageList packages = packageManager.getPackageList();
                return packages == null
                        ? Collections.<String>emptyList() : packages.getPackageNames();
            }
        };
    }
}
