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
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Merges one package's protection rows. Highest layer first. A lower row's
 * {@code denyFreeze=false} or {@code denyKill=false} does not clear a higher row's ban.
 * User force-stop blocks every allow and makes a cached kill proceed; it does not erase
 * the higher ban bit from the dump.
 *
 * <p>This object never calls into the activity manager. Callers may hold that lock.
 */
public final class ProtectionArbiter {
    /**
     * Highest rank is 0. A lower number outranks a higher number.
     * Order matches the port spec: system safety, user force-stop, runtime session,
     * enterprise admin, user lock, product static rules, dynamic prediction. The user
     * whitelist is last: it carries the user's own allows, so every layer above it is a
     * reason the allow does not take effect, and the user's own force-stop still wins
     * through {@link #setUserForceStop} rather than through this order.
     */
    public enum Layer {
        SYSTEM_SAFETY(0),
        USER_RESTRICTION(1),
        RUNTIME(2),
        ADMIN(3),
        USER_LOCK(4),
        STATIC(5),
        DYNAMIC(6),
        USER_WHITELIST(7);

        public final int rank;

        Layer(int rank) {
            this.rank = rank;
        }

        public boolean outranks(Layer other) {
            return other != null && rank < other.rank;
        }
    }

    /** User lock delays freeze by the existing big-app debounce. It is not a ban. */
    public static final long USER_LOCK_FREEZE_DELAY_MS = ApmConstants.DEFAULT_BIG_APP_FREEZE_DELAY_MS;
    /** Raised score only. Cached kill still runs unless some layer set {@code denyKill}. */
    public static final int USER_LOCK_PROTECTION_SCORE = 1000;
    /** Recent-task row. Small score, not {@code denyKill}, and no revival. */
    public static final int RECENT_TASK_PROTECTION_SCORE = 100;

    private static final Layer[] HIGHEST_FIRST = Layer.values();

    /**
     * Packages that are never frozen, never trimmed, and never handed to the cached
     * killer while they are installed, whatever every other layer says. The package name
     * is the whole condition: a package that is not installed never reaches this table,
     * and nothing has to be tracked for one that is.
     *
     * <p>{@code com.xiaomi.xmsf} is the Xiaomi push service framework. Apps that ship Mi
     * Push deliver their notifications through it, so a background restriction on it
     * breaks push for all of them. It is user installed on this build, so no shipped
     * static table can cover it.
     *
     * <p>An explicit user force-stop still stops the app: the platform decides whether a
     * stopped package may run at all, and this port never starts a package. The same goes
     * for the user's own background restriction toggle, which {@code
     * #shouldSpareCachedKill} keeps ahead of every exemption.
     */
    private static final ArraySet<String> ALWAYS_EXEMPT = new ArraySet<>(new String[] {
            "com.xiaomi.xmsf",
    });

    /** One shared row. No user id, so it applies to every user. */
    private static final AppProtectionPolicy ALWAYS_EXEMPT_ROW =
            AppProtectionPolicy.builder(ALWAYS_EXEMPT.valueAt(0), UserHandle.USER_ALL,
                            Layer.SYSTEM_SAFETY)
                    .denyFreeze(true)
                    .denyKill(true)
                    .allowNetworkWhileFrozen(true)
                    .expiresElapsed(0L)
                    .source("always-exempt")
                    .reason("installed-always-exempt")
                    .build();

    /** @return true when {@code packageName} is exempt for as long as it is installed */
    public static boolean isAlwaysExempt(@Nullable String packageName) {
        return packageName != null && ALWAYS_EXEMPT.contains(packageName);
    }

    private final Object mLock = new Object();
    private final ArrayMap<String, ArrayList<AppProtectionPolicy>> mByPackage = new ArrayMap<>();
    /** Active role tokens, keyed by {@code userId:package}. One row while any token remains. */
    private final ArrayMap<String, ArraySet<String>> mRoleTokens = new ArrayMap<>();
    /** Active runtime tokens. Same shape. Clearing audio does not clear vpn. */
    private final ArrayMap<String, ArraySet<String>> mRuntimeTokens = new ArrayMap<>();

    /** Replaces the previous row for the same package, user, and layer. */
    public void put(AppProtectionPolicy policy) {
        if (policy == null || policy.packageName == null || policy.layer == null) {
            return;
        }
        synchronized (mLock) {
            ArrayList<AppProtectionPolicy> rows = mByPackage.get(policy.packageName);
            if (rows == null) {
                rows = new ArrayList<>();
                mByPackage.put(policy.packageName, rows);
            }
            for (int i = rows.size() - 1; i >= 0; i--) {
                final AppProtectionPolicy old = rows.get(i);
                if (old.layer == policy.layer && old.userId == policy.userId) {
                    rows.remove(i);
                }
            }
            rows.add(policy);
        }
    }

    public void remove(String packageName, int userId, Layer layer) {
        if (packageName == null || layer == null) {
            return;
        }
        synchronized (mLock) {
            final ArrayList<AppProtectionPolicy> rows = mByPackage.get(packageName);
            if (rows == null) {
                return;
            }
            for (int i = rows.size() - 1; i >= 0; i--) {
                final AppProtectionPolicy old = rows.get(i);
                if (old.layer == layer && old.userId == userId) {
                    rows.remove(i);
                }
            }
            if (rows.isEmpty()) {
                mByPackage.remove(packageName);
            }
        }
    }

    /**
     * IME, device owner, or accessibility. Tokens stack on one row. {@code expiresElapsed}
     * stays 0. The row is removed only when the last token for this package and user is
     * cleared. Source is {@code role}.
     */
    public void setHardRole(String packageName, int userId, String reason, boolean active) {
        setToken(mRoleTokens, packageName, userId, Layer.SYSTEM_SAFETY, "role",
                reason == null ? "role" : reason, active);
    }

    /**
     * VPN or audio. Same bans, lower than force-stop. Tokens stack so clearing one
     * session does not clear the other. Source is {@code runtime}.
     */
    public void setRuntimeSession(String packageName, int userId, String reason, boolean active) {
        setToken(mRuntimeTokens, packageName, userId, Layer.RUNTIME, "runtime",
                reason == null ? "runtime" : reason, active);
    }

    private void setToken(ArrayMap<String, ArraySet<String>> tokens, String packageName,
            int userId, Layer layer, String source, String token, boolean active) {
        if (packageName == null || layer == null) {
            return;
        }
        final String key = userId + ":" + packageName;
        synchronized (mLock) {
            ArraySet<String> set = tokens.get(key);
            if (!active) {
                if (set == null) {
                    remove(packageName, userId, layer);
                    return;
                }
                set.remove(token);
                if (!set.isEmpty()) {
                    putTokenRow(packageName, userId, layer, source, set);
                    return;
                }
                tokens.remove(key);
                remove(packageName, userId, layer);
                return;
            }
            if (set == null) {
                set = new ArraySet<>();
                tokens.put(key, set);
            }
            set.add(token);
            putTokenRow(packageName, userId, layer, source, set);
        }
    }

    private void putTokenRow(String packageName, int userId, Layer layer, String source,
            ArraySet<String> tokens) {
        final StringBuilder reason = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                reason.append(',');
            }
            reason.append(tokens.valueAt(i));
        }
        put(AppProtectionPolicy.builder(packageName, userId, layer)
                .denyFreeze(true)
                .denyKill(true)
                .expiresElapsed(0L)
                .source(source)
                .reason(reason.toString())
                .build());
    }

    /** User explicit force-stop. Blocks allows. Does not clear a higher ban bit. */
    public void setUserForceStop(String packageName, int userId, boolean stopped) {
        setUserRestriction(packageName, userId, stopped, false /* background */);
    }

    public void setBackgroundRestricted(String packageName, int userId, boolean restricted) {
        setUserRestriction(packageName, userId, restricted, true /* background */);
    }

    /**
     * Force-stop and background restriction share one layer. Clearing one leaves the other.
     * Neither writes {@code denyFreeze} or {@code denyKill}.
     */
    private void setUserRestriction(String packageName, int userId, boolean on, boolean background) {
        synchronized (mLock) {
            boolean forceStopped = false;
            boolean backgroundRestricted = false;
            final ArrayList<AppProtectionPolicy> rows = mByPackage.get(packageName);
            if (rows != null) {
                for (int i = 0; i < rows.size(); i++) {
                    final AppProtectionPolicy old = rows.get(i);
                    if (old.layer == Layer.USER_RESTRICTION && old.userId == userId) {
                        forceStopped = old.forceStopped;
                        backgroundRestricted = old.backgroundRestricted;
                        break;
                    }
                }
            }
            if (background) {
                backgroundRestricted = on;
            } else {
                forceStopped = on;
            }
            if (!forceStopped && !backgroundRestricted) {
                remove(packageName, userId, Layer.USER_RESTRICTION);
                return;
            }
            final String reason = forceStopped ? "force-stop" : "background-restricted";
            put(AppProtectionPolicy.builder(packageName, userId, Layer.USER_RESTRICTION)
                    .forceStopped(forceStopped)
                    .backgroundRestricted(backgroundRestricted)
                    .expiresElapsed(Long.MAX_VALUE)
                    .source("user")
                    .reason(reason)
                    .build());
        }
    }

    /** Freeze may be delayed. Score goes up. Not {@code denyKill}. */
    public void setUserLocked(String packageName, int userId, boolean locked) {
        if (!locked) {
            remove(packageName, userId, Layer.USER_LOCK);
            return;
        }
        put(AppProtectionPolicy.builder(packageName, userId, Layer.USER_LOCK)
                .userLocked(true)
                .protectionScore(USER_LOCK_PROTECTION_SCORE)
                .freezeDelayExtraMs(USER_LOCK_FREEZE_DELAY_MS)
                .expiresElapsed(Long.MAX_VALUE)
                .source("user")
                .reason("recent-lock")
                .build());
    }

    public boolean deniesFreeze(String packageName, int userId, long nowElapsed) {
        return merge(packageName, userId, nowElapsed).denyFreeze;
    }

    /**
     * Cached-kill spare. False when the user force-stopped the package, even if a layer
     * set {@code denyKill}: the ban bit stays visible, and the kill still proceeds.
     * {@code processForceStopped} covers the process flag when the arbiter row is absent.
     */
    public boolean shouldSpareCachedKill(String packageName, int userId, long nowElapsed,
            boolean processForceStopped) {
        final Merged merged = merge(packageName, userId, nowElapsed);
        if (merged.forceStopped || merged.backgroundRestricted || processForceStopped) {
            return false;
        }
        return merged.denyKill;
    }

    public long freezeDelayExtraMs(String packageName, int userId, long nowElapsed) {
        return merge(packageName, userId, nowElapsed).freezeDelayExtraMs;
    }

    public int protectionScore(String packageName, int userId, long nowElapsed) {
        return merge(packageName, userId, nowElapsed).protectionScore;
    }

    public Merged merge(String packageName, int userId, long nowElapsed) {
        final AppProtectionPolicy[] rows = new AppProtectionPolicy[HIGHEST_FIRST.length];
        synchronized (mLock) {
            final ArrayList<AppProtectionPolicy> stored = mByPackage.get(packageName);
            if (stored != null) {
                for (int i = 0; i < stored.size(); i++) {
                    final AppProtectionPolicy policy = stored.get(i);
                    if (policy.expired(nowElapsed)) {
                        continue;
                    }
                    if (policy.userId != userId && policy.userId != UserHandle.USER_ALL) {
                        continue;
                    }
                    final int index = policy.layer.rank;
                    if (rows[index] == null || policy.userId == userId) {
                        rows[index] = policy;
                    }
                }
            }
        }
        if (isAlwaysExempt(packageName)) {
            // Replaces whatever is stored at this rank: the exemption is built in, has no
            // user id, and no caller can clear it.
            rows[Layer.SYSTEM_SAFETY.rank] = ALWAYS_EXEMPT_ROW;
        }
        return fold(packageName, userId, rows);
    }

    public void dump(PrintWriter pw, long nowElapsed) {
        synchronized (mLock) {
            pw.println("  protection policies:");
            if (mByPackage.isEmpty()) {
                pw.println("    (none)");
                return;
            }
            for (int i = 0; i < mByPackage.size(); i++) {
                final String pkg = mByPackage.keyAt(i);
                final ArrayList<AppProtectionPolicy> rows = mByPackage.valueAt(i);
                final ArrayList<Integer> users = new ArrayList<>();
                for (int r = 0; r < rows.size(); r++) {
                    final int user = rows.get(r).userId;
                    if (!users.contains(user)) {
                        users.add(user);
                    }
                }
                for (int u = 0; u < users.size(); u++) {
                    dumpPackageLocked(pw, pkg, users.get(u), nowElapsed);
                }
            }
        }
    }

    public void dumpPackage(PrintWriter pw, String packageName, int userId, long nowElapsed) {
        synchronized (mLock) {
            dumpPackageLocked(pw, packageName, userId, nowElapsed);
        }
    }

    private void dumpPackageLocked(PrintWriter pw, String packageName, int userId,
            long nowElapsed) {
        final Merged merged = merge(packageName, userId, nowElapsed);
        if (merged.rows.isEmpty()) {
            return;
        }
        pw.print("    pkg=");
        pw.print(packageName);
        pw.print(" user=");
        pw.println(userId);
        for (int i = 0; i < merged.rows.size(); i++) {
            final AppProtectionPolicy policy = merged.rows.get(i);
            pw.print("      layer=");
            pw.print(policy.layer.name());
            pw.print(" source=");
            pw.print(policy.source);
            pw.print(" denyFreeze=");
            pw.print(policy.denyFreeze);
            pw.print(" denyKill=");
            pw.print(policy.denyKill);
            pw.print(" allowNetworkWhileFrozen=");
            pw.print(policy.allowNetworkWhileFrozen);
            pw.print(" allowJobWakeup=");
            pw.print(policy.allowJobWakeup);
            pw.print(" allowAlarmWakeup=");
            pw.print(policy.allowAlarmWakeup);
            pw.print(" allowServiceWakeup=");
            pw.print(policy.allowServiceWakeup);
            pw.print(" taskRestore=");
            pw.print(policy.taskRestore);
            pw.print(" forceStopped=");
            pw.print(policy.forceStopped);
            pw.print(" backgroundRestricted=");
            pw.print(policy.backgroundRestricted);
            pw.print(" userLocked=");
            pw.print(policy.userLocked);
            pw.print(" protectionScore=");
            pw.print(policy.protectionScore);
            pw.print(" reason=");
            pw.print(policy.reason);
            pw.print(" expires=");
            pw.println(policy.expiresElapsed);
        }
        pw.print("      effective denyFreeze=");
        pw.print(merged.denyFreeze);
        if (merged.denyFreezeLayer != null) {
            pw.print(" by ");
            pw.print(merged.denyFreezeLayer.name());
        }
        pw.print("; higher layers that still allow freeze: ");
        pw.println(merged.higherAllowsFreeze);
        pw.print("      effective denyKill=");
        pw.print(merged.denyKill);
        if (merged.denyKillLayer != null) {
            pw.print(" by ");
            pw.print(merged.denyKillLayer.name());
        }
        pw.print("; higher layers that still allow kill: ");
        pw.println(merged.higherAllowsKill);
        pw.print("      cachedKillSpared=");
        pw.print(shouldSpareCachedKill(packageName, userId, nowElapsed, false));
        pw.println(merged.forceStopped || merged.backgroundRestricted
                ? " (user force-stop or background restriction wins over the ban)"
                : "");
    }

    private static Merged fold(String packageName, int userId, AppProtectionPolicy[] rows) {
        boolean denyFreeze = false;
        boolean denyKill = false;
        boolean allowNet = false;
        boolean allowJob = false;
        boolean allowAlarm = false;
        boolean allowService = false;
        boolean taskRestore = false;
        boolean forceStopped = false;
        boolean backgroundRestricted = false;
        boolean userLocked = false;
        int protectionScore = 0;
        long freezeExtra = 0L;
        RevivalBudget revival = null;
        Layer denyFreezeLayer = null;
        Layer denyKillLayer = null;
        final ArrayList<AppProtectionPolicy> present = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            final AppProtectionPolicy policy = rows[i];
            if (policy == null) {
                continue;
            }
            present.add(policy);
            // OR. A later (lower) false does not clear a higher true.
            if (policy.denyFreeze && !denyFreeze) {
                denyFreeze = true;
                denyFreezeLayer = policy.layer;
            } else if (policy.denyFreeze) {
                denyFreeze = true;
            }
            if (policy.denyKill && denyKillLayer == null) {
                denyKill = true;
                denyKillLayer = policy.layer;
            } else if (policy.denyKill) {
                denyKill = true;
            }
            if (policy.forceStopped) {
                forceStopped = true;
            }
            if (policy.backgroundRestricted) {
                backgroundRestricted = true;
            }
            if (policy.userLocked) {
                userLocked = true;
            }
            if (policy.protectionScore > protectionScore) {
                protectionScore = policy.protectionScore;
            }
            if (policy.freezeDelayExtraMs > freezeExtra) {
                freezeExtra = policy.freezeDelayExtraMs;
            }
            allowNet |= policy.allowNetworkWhileFrozen;
            allowJob |= policy.allowJobWakeup;
            allowAlarm |= policy.allowAlarmWakeup;
            allowService |= policy.allowServiceWakeup;
            taskRestore |= policy.taskRestore;
            if (policy.revival != null) {
                revival = policy.revival;
            }
        }
        // Force-stop and background restriction win over every allow, including a higher layer.
        if (forceStopped || backgroundRestricted) {
            allowNet = false;
            allowJob = false;
            allowAlarm = false;
            allowService = false;
            taskRestore = false;
            revival = null;
        }
        return new Merged(packageName, userId, denyFreeze, denyKill, allowNet, allowJob,
                allowAlarm, allowService, taskRestore, forceStopped, backgroundRestricted,
                userLocked, protectionScore, freezeExtra, revival, denyFreezeLayer, denyKillLayer,
                higherAllows(rows, denyFreezeLayer, true),
                higherAllows(rows, denyKillLayer, false), present);
    }

    /**
     * Layers above the one that set the ban, that did not set it themselves.
     * Those layers still allow the action. They do not clear the lower ban, and a lower
     * allow does not clear them when they are the ones that set it.
     */
    private static String higherAllows(AppProtectionPolicy[] rows, Layer banLayer,
            boolean freeze) {
        if (banLayer == null) {
            return "(no ban)";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < banLayer.rank; i++) {
            final AppProtectionPolicy policy = rows[i];
            final boolean banned = policy != null && (freeze ? policy.denyFreeze : policy.denyKill);
            if (banned) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(HIGHEST_FIRST[i].name());
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    /** Effective policy for one package. Rows are highest layer first. */
    public static final class Merged {
        public final String packageName;
        public final int userId;
        public final boolean denyFreeze;
        public final boolean denyKill;
        public final boolean allowNetworkWhileFrozen;
        public final boolean allowJobWakeup;
        public final boolean allowAlarmWakeup;
        public final boolean allowServiceWakeup;
        public final boolean taskRestore;
        public final boolean forceStopped;
        public final boolean backgroundRestricted;
        public final boolean userLocked;
        public final int protectionScore;
        public final long freezeDelayExtraMs;
        public final RevivalBudget revival;
        public final Layer denyFreezeLayer;
        public final Layer denyKillLayer;
        public final String higherAllowsFreeze;
        public final String higherAllowsKill;
        public final List<AppProtectionPolicy> rows;

        Merged(String packageName, int userId, boolean denyFreeze, boolean denyKill,
                boolean allowNetworkWhileFrozen, boolean allowJobWakeup, boolean allowAlarmWakeup,
                boolean allowServiceWakeup, boolean taskRestore, boolean forceStopped,
                boolean backgroundRestricted, boolean userLocked, int protectionScore,
                long freezeDelayExtraMs, RevivalBudget revival, Layer denyFreezeLayer,
                Layer denyKillLayer, String higherAllowsFreeze, String higherAllowsKill,
                List<AppProtectionPolicy> rows) {
            this.packageName = packageName;
            this.userId = userId;
            this.denyFreeze = denyFreeze;
            this.denyKill = denyKill;
            this.allowNetworkWhileFrozen = allowNetworkWhileFrozen;
            this.allowJobWakeup = allowJobWakeup;
            this.allowAlarmWakeup = allowAlarmWakeup;
            this.allowServiceWakeup = allowServiceWakeup;
            this.taskRestore = taskRestore;
            this.forceStopped = forceStopped;
            this.backgroundRestricted = backgroundRestricted;
            this.userLocked = userLocked;
            this.protectionScore = protectionScore;
            this.freezeDelayExtraMs = freezeDelayExtraMs;
            this.revival = revival;
            this.denyFreezeLayer = denyFreezeLayer;
            this.denyKillLayer = denyKillLayer;
            this.higherAllowsFreeze = higherAllowsFreeze;
            this.higherAllowsKill = higherAllowsKill;
            this.rows = rows;
        }
    }
}
