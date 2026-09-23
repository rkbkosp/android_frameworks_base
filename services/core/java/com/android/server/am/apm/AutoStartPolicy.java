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
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto-start block list. Two settings hold the whole policy: a switch, off by default, and a
 * {@code |} separated package list, empty by default. A package on the list is refused a
 * service start, a service bind, and a broadcast delivery.
 *
 * <p>Nothing is inferred. The entries are only the ones a user wrote. An empty list and the
 * switch off are the same answer at every gate: the gate answers false and its caller keeps
 * the answer it had. That is the shipped state.
 *
 * <p>Two kinds of exemption. One is applied when the list is read, so an exempt entry never
 * reaches a gate and never costs a lookup: the platform package,
 * {@link ProtectionArbiter#isAlwaysExempt}, and a package signed with the platform key. The
 * other is applied when a gate is asked, because the fact comes and goes without a settings
 * write: the current input method and the enabled accessibility services. Both are already
 * tracked by this service, so no second copy is kept here.
 *
 * <p>The list, the platform signature, and the role facts are read off the service thread. A
 * gate query is a set lookup and an integer compare, so a caller that holds the activity
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

    private static final int MAX_PACKAGE_LENGTH = 255;

    static final String USAGE =
            "Error: apm autostart needs list|add <pkg>|remove <pkg>|enable|disable";

    /**
     * The two settings values. Production reads {@code Settings.Global}; a test hands in its
     * own map so a gate can be exercised without a content provider.
     */
    interface Store {
        int getInt(String key, int defaultValue);

        @Nullable
        String getString(String key);

        /** @return null when the write was accepted, else a one-line shell error. */
        @Nullable
        String putInt(String key, int value);

        /** @return null when the write was accepted, else a one-line shell error. */
        @Nullable
        String putString(String key, @Nullable String value);
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

    /** Platform signature fact for one package. */
    interface PlatformSignatures {
        boolean isPlatformSigned(@Nullable String packageName);
    }

    /**
     * One settings read. Immutable once published. {@code blocked} is {@code listed} after
     * the exemptions that cannot change without a write.
     */
    private static final class Snapshot {
        static final Snapshot EMPTY =
                new Snapshot(false, new ArraySet<String>(), new ArraySet<String>());

        final boolean enabled;
        final ArraySet<String> listed;
        final ArraySet<String> blocked;

        Snapshot(boolean enabled, ArraySet<String> listed, ArraySet<String> blocked) {
            this.enabled = enabled;
            this.listed = listed;
            this.blocked = blocked;
        }
    }

    private final Store mStore;
    private final PlatformSignatures mSignatures;
    private final Roles mRoles;
    @Nullable
    private final ContentResolver mResolver;
    @Nullable
    private final ContentObserver mObserver;
    private final AtomicInteger[] mDenied = new AtomicInteger[GATE_COUNT];
    private volatile Snapshot mSnapshot = Snapshot.EMPTY;
    private boolean mRegistered;

    AutoStartPolicy(Store store, PlatformSignatures signatures, Roles roles,
            @Nullable ContentResolver resolver, @Nullable Handler handler) {
        mStore = store;
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
     * Follows both settings keys and publishes the first read. Runs on the service thread:
     * the platform signature lookup is an IPC and must not run under the activity manager
     * lock. A test that supplied its own store does nothing here.
     */
    void systemReady() {
        if (mResolver == null || mObserver == null || mRegistered) {
            return;
        }
        mRegistered = true;
        try {
            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(ApmConstants.KEY_AUTO_START_BLOCK_ENABLED),
                    false, mObserver, UserHandle.USER_ALL);
            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(ApmConstants.KEY_AUTO_START_BLOCKED),
                    false, mObserver, UserHandle.USER_ALL);
        } catch (Throwable t) {
            Slog.w(TAG, "auto start settings observer not registered", t);
        }
        refresh();
    }

    /**
     * Re-reads both settings and publishes what they say. A failed read keeps the previous
     * snapshot: a broken provider does not turn blocking on or off by itself.
     */
    void refresh() {
        final boolean enabled;
        final String raw;
        try {
            enabled = mStore.getInt(ApmConstants.KEY_AUTO_START_BLOCK_ENABLED, 0) != 0;
            raw = mStore.getString(ApmConstants.KEY_AUTO_START_BLOCKED);
        } catch (Throwable t) {
            Slog.w(TAG, "auto start settings read failed", t);
            return;
        }
        final ArraySet<String> listed = parse(raw);
        final ArraySet<String> blocked = new ArraySet<>();
        for (int i = 0; i < listed.size(); i++) {
            final String packageName = listed.valueAt(i);
            if (PLATFORM_PACKAGE.equals(packageName)
                    || ProtectionArbiter.isAlwaysExempt(packageName)
                    || mSignatures.isPlatformSigned(packageName)) {
                continue;
            }
            blocked.add(packageName);
        }
        mSnapshot = new Snapshot(enabled, listed, blocked);
    }

    /** True when the switch is on and {@code targetPackage} is on the list as enforced. */
    boolean blocks(@Nullable String targetPackage) {
        if (targetPackage == null) {
            return false;
        }
        final Snapshot snapshot = mSnapshot;
        return snapshot.enabled && snapshot.blocked.contains(targetPackage);
    }

    /**
     * True when this caller may not start or bind {@code targetPackage}. All three must
     * hold: the switch is on and the target is listed, the caller is an app rather than the
     * platform, root, or the shell, and the target holds no exempting role.
     */
    boolean shouldBlockStart(@Nullable String callerPackage, int callerUid,
            @Nullable String targetPackage) {
        if (!blocks(targetPackage)) {
            return false;
        }
        if (isPlatformCaller(callerPackage, callerUid)) {
            return false;
        }
        return !mRoles.holdsRole(targetPackage);
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

    int listedCount() {
        return mSnapshot.listed.size();
    }

    int enforcedCount() {
        return mSnapshot.blocked.size();
    }

    /** Sorted copy of the list as stored, exempt entries included. */
    List<String> listedPackages() {
        return sorted(mSnapshot.listed);
    }

    /** Sorted copy of the entries that reach a gate. */
    List<String> enforcedPackages() {
        return sorted(mSnapshot.blocked);
    }

    /** Sorted copy of the entries that are dropped when the list is read. */
    List<String> droppedPackages() {
        final Snapshot snapshot = mSnapshot;
        final ArraySet<String> dropped = new ArraySet<>();
        for (int i = 0; i < snapshot.listed.size(); i++) {
            final String packageName = snapshot.listed.valueAt(i);
            if (!snapshot.blocked.contains(packageName)) {
                dropped.add(packageName);
            }
        }
        return sorted(dropped);
    }

    /** Sorted copy of the current input-method and enabled accessibility packages. */
    List<String> rolePackages() {
        final List<String> roles = mRoles.rolePackages();
        return roles == null ? Collections.<String>emptyList() : roles;
    }

    String list() {
        final Snapshot snapshot = mSnapshot;
        final StringBuilder out = new StringBuilder();
        out.append("autoStartBlock=").append(snapshot.enabled ? "on" : "off");
        out.append(" listed=").append(snapshot.listed.size());
        out.append(" enforced=").append(snapshot.blocked.size());
        if (snapshot.listed.size() == 0) {
            return out.append(" (no packages)").toString();
        }
        for (int i = 0; i < snapshot.listed.size(); i++) {
            final String packageName = snapshot.listed.valueAt(i);
            out.append("\n  ").append(packageName);
            out.append(snapshot.blocked.contains(packageName) ? " blocked" : " exempt");
        }
        return out.toString();
    }

    /** Adds one entry, writes the list back, and republishes. */
    String add(@Nullable String packageName) {
        final String valid = validated(packageName);
        if (valid == null) {
            return "Error: apm autostart add requires a package name";
        }
        final ArraySet<String> listed = readListed();
        if (listed.contains(valid)) {
            return "already listed " + valid;
        }
        listed.add(valid);
        final String error = writeListed(listed);
        if (error != null) {
            return error;
        }
        refresh();
        return mSnapshot.blocked.contains(valid)
                ? "blocked " + valid
                : "listed " + valid + " (exempt, no gate is changed)";
    }

    /** Removes one entry, writes the list back, and republishes. */
    String remove(@Nullable String packageName) {
        final String valid = validated(packageName);
        if (valid == null) {
            return "Error: apm autostart remove requires a package name";
        }
        final ArraySet<String> listed = readListed();
        if (!listed.remove(valid)) {
            return "not listed " + valid;
        }
        final String error = writeListed(listed);
        if (error != null) {
            return error;
        }
        refresh();
        return "removed " + valid;
    }

    /** Turns the switch on or off and republishes. */
    String setEnabled(boolean enabled) {
        final String error =
                mStore.putInt(ApmConstants.KEY_AUTO_START_BLOCK_ENABLED, enabled ? 1 : 0);
        if (error != null) {
            return error;
        }
        refresh();
        return "autoStartBlock=" + (mSnapshot.enabled ? "on" : "off");
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

    private ArraySet<String> readListed() {
        return parse(mStore.getString(ApmConstants.KEY_AUTO_START_BLOCKED));
    }

    private String writeListed(ArraySet<String> listed) {
        final StringBuilder joined = new StringBuilder();
        for (int i = 0; i < listed.size(); i++) {
            if (i > 0) {
                joined.append(ApmConstants.AUTO_START_LIST_SEPARATOR);
            }
            joined.append(listed.valueAt(i));
        }
        return mStore.putString(ApmConstants.KEY_AUTO_START_BLOCKED, joined.toString());
    }

    private static ArraySet<String> parse(@Nullable String raw) {
        final ArraySet<String> out = new ArraySet<>();
        if (raw == null) {
            return out;
        }
        final int length = raw.length();
        int start = 0;
        for (int i = 0; i <= length; i++) {
            if (i == length || raw.charAt(i) == ApmConstants.AUTO_START_LIST_SEPARATOR) {
                final String packageName = validated(raw.substring(start, i));
                if (packageName != null) {
                    out.add(packageName);
                }
                start = i + 1;
            }
        }
        return out;
    }

    /** @return the trimmed package name, or null when it is not one. */
    @Nullable
    private static String validated(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        final String packageName = raw.trim();
        final int length = packageName.length();
        if (length == 0 || length > MAX_PACKAGE_LENGTH) {
            return null;
        }
        for (int i = 0; i < length; i++) {
            final char c = packageName.charAt(i);
            final boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!allowed) {
                return null;
            }
        }
        return packageName;
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
                public String getString(String key) {
                    return null;
                }

                @Nullable
                @Override
                public String putInt(String key, int value) {
                    return "Error: settings are not available";
                }

                @Nullable
                @Override
                public String putString(String key, @Nullable String value) {
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
            public String getString(String key) {
                try {
                    return Settings.Global.getString(resolver, key);
                } catch (Throwable t) {
                    return null;
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

            @Nullable
            @Override
            public String putString(String key, @Nullable String value) {
                try {
                    return Settings.Global.putString(resolver, key, value)
                            ? null : "Error: settings write rejected";
                } catch (Throwable t) {
                    return "Error: settings write failed";
                }
            }
        };
    }

    /**
     * {@code PackageManager#checkSignatures} against the platform package. This is the
     * platform-key exemption: a listed system component cannot break the build.
     */
    static PlatformSignatures packageSignatures(@Nullable Context context) {
        final PackageManager packageManager =
                context == null ? null : context.getPackageManager();
        if (packageManager == null) {
            return packageName -> false;
        }
        return packageName -> {
            if (packageName == null) {
                return false;
            }
            try {
                return packageManager.checkSignatures(packageName, PLATFORM_PACKAGE)
                        == PackageManager.SIGNATURE_MATCH;
            } catch (Throwable t) {
                // Not installed, or a package manager that is not up yet. Keep the entry.
                return false;
            }
        };
    }
}
