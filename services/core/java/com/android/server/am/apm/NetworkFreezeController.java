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
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Cuts the network of a uid whose freeze the freezer confirmed, unless the uid is allowed
 * to keep it.
 *
 * <p>The mechanism is AOSP's own OEM denylist chain plus the socket destroy that enabling
 * that chain already performs. Nothing here talks to netd directly and nothing here lives in
 * the connectivity module, which is an APEX and would be replaced by a module update. Three
 * facts drive the design:
 *
 * <ol>
 * <li>The connectivity service already destroys the TCP sockets of every uid that is fully
 * frozen, and that path has no opt out. A uid that must keep its network therefore cannot be
 * cut here alone: the activity manager has to stop reporting it as frozen.
 * <li>{@code FIREWALL_CHAIN_OEM_DENY_2} is the chain AOSP reserves for OEM denials and no
 * service in this tree writes, so a rule here cannot be overwritten by the network policy
 * manager, whose whole-chain replace would clear it.
 * <li>Enabling a chain runs the socket scan <em>on the calling thread</em> inside the
 * connectivity service. Every call below therefore happens on the dedicated
 * {@code apm-net} thread, and one review sweep enables the chain at most once.
 * </ol>
 *
 * <p>Everything fails open. A cut that cannot be written costs the uid nothing but its
 * power saving; the activity manager never depends on this class for correctness.
 */
final class NetworkFreezeController {
    private static final String TAG = "Apm";

    /**
     * AOSP reserves this chain for OEM denials. The network policy manager owns the six
     * AOSP chains and rewrites them whole, so using one of those would drop our rules.
     */
    static final int CHAIN = ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_2;

    /** Attempts per uid, first one included. Same bound the freezer uses for the cgroup. */
    static final int MAX_CUT_ATTEMPTS = 3;
    /** Delay before a failed apply is tried again. */
    static final long RETRY_DELAY_MS = 1_000L;

    private static final int[] NO_UIDS = new int[0];

    /**
     * The firewall calls. All of them run on the {@code apm-net} thread. A
     * {@link RuntimeException} means the apply failed; the controller counts it and retries
     * a bounded number of times before it gives up and leaves the uid connected.
     */
    interface NetCutBackend {
        /** A denylist chain: DENY adds the uid to it, ALLOW removes it. */
        void setUidRule(int chain, int uid, int rule);

        void setChainEnabled(boolean enabled);

        boolean isChainEnabled();

        int getUidRule(int uid);

        /** False when there is no connectivity manager to talk to. Reported by dump. */
        boolean isAvailable();
    }

    /** Delivery, injected so a test can drive the grace window without a real clock. */
    interface Scheduler {
        void post(Runnable runnable);

        void postDelayed(Runnable runnable, long delayMs);

        void remove(Runnable runnable);
    }

    /** Whether a package gets the game grace instead of the plain one. */
    interface GameClassifier {
        boolean isGame(@Nullable String packageName, int userId, int uid);
    }

    private final ApmConfigManager mConfig;
    private final Scheduler mScheduler;
    private final NetCutBackend mBackend;
    private final GameClassifier mGames;

    /**
     * Guards the bookkeeping below. Never held across a backend call: the applier can sit
     * inside the connectivity service for as long as its socket scan takes.
     */
    private final Object mLock = new Object();
    /** Uids that should be cut. Cleared by an unfreeze, an allow bit, or a closed gate. */
    @GuardedBy("mLock") private final ArraySet<Integer> mCutRequested = new ArraySet<>();
    /** Uids whose deny rule this controller wrote and has not removed. */
    @GuardedBy("mLock") private final ArraySet<Integer> mCutUids = new ArraySet<>();
    /** Requested uids that are still inside the grace window. */
    @GuardedBy("mLock") private final ArraySet<Integer> mDeferred = new ArraySet<>();
    /**
     * Uids confirmed with an allow bit. Kept for as long as the uid is frozen, so the
     * report is right even if the service's allow bit set is one review behind.
     */
    @GuardedBy("mLock") private final ArraySet<Integer> mKeepConfirmed = new ArraySet<>();
    @GuardedBy("mLock") private final ArrayMap<Integer, String> mCutPackages = new ArrayMap<>();
    @GuardedBy("mLock") private final ArrayMap<Integer, String> mLastResult = new ArrayMap<>();
    /** uid -> attempts spent on the current cut. Absent when no attempt is running. */
    @GuardedBy("mLock") private final ArrayMap<Integer, Integer> mAttempts = new ArrayMap<>();
    /** uid -> attempts spent trying to remove its rule again. */
    @GuardedBy("mLock") private final ArrayMap<Integer, Integer> mUncutAttempts = new ArrayMap<>();
    /** uid -> armed grace timer, and uid -> the generation that timer must still match. */
    @GuardedBy("mLock") private final ArrayMap<Integer, Runnable> mGraceTimers = new ArrayMap<>();
    @GuardedBy("mLock") private final ArrayMap<Integer, Integer> mGraceGenerations =
            new ArrayMap<>();
    @GuardedBy("mLock") private int mNextGraceGeneration;
    @GuardedBy("mLock") private int mApplyFailures;
    /** A cut was written since the last enable, so one enable is owed to this sweep. */
    @GuardedBy("mLock") private boolean mChainDirty;
    /** The clean start of this process already happened. */
    @GuardedBy("mLock") private boolean mChainWashed;
    /** The allow bit set, as published by the service. Immutable, replaced whole. */
    @GuardedBy("mLock") private int[] mAllowNet = NO_UIDS;

    /**
     * The uids the activity manager must report as unfrozen even though they are frozen.
     * Immutable, replaced whole, read without a lock by the reporting thread.
     */
    private volatile int[] mReportSnapshot = NO_UIDS;

    /** Production: owns the {@code apm-net} thread and talks to the platform. */
    NetworkFreezeController(ApmConfigManager config, @Nullable Context context) {
        this(config, null /* scheduler */, context, null /* backend */, null /* games */);
    }

    @VisibleForTesting
    NetworkFreezeController(ApmConfigManager config, Scheduler scheduler,
            NetCutBackend backend, @Nullable GameClassifier games) {
        this(config, scheduler, null /* context */, backend, games);
    }

    /**
     * Used when the service runs without a thread of its own. The scheduler is the
     * service's; the backend is the platform one, so with no connectivity manager a cut
     * fails and the controller gives up after {@link #MAX_CUT_ATTEMPTS} exactly as it would
     * on a device.
     */
    @VisibleForTesting
    NetworkFreezeController(ApmConfigManager config, Scheduler scheduler) {
        this(config, scheduler, null /* context */,
                new ConnectivityManagerBackend(null /* context */), null /* games */);
    }

    /** The real {@code apm-net} thread with an injected backend. */
    @VisibleForTesting
    NetworkFreezeController(ApmConfigManager config, NetCutBackend backend,
            @Nullable GameClassifier games) {
        this(config, null /* scheduler */, null /* context */, backend, games);
    }

    private NetworkFreezeController(ApmConfigManager config, @Nullable Scheduler scheduler,
            @Nullable Context context, @Nullable NetCutBackend backend,
            @Nullable GameClassifier games) {
        mConfig = config;
        if (scheduler != null) {
            mScheduler = scheduler;
        } else {
            // The connectivity service runs the firewall writes and the socket scan on the
            // caller, and the package manager read takes the package lock. Neither may
            // happen on a caller that holds a service lock, so this class owns a thread.
            // The scheduler holds the handler, which holds the looper, so the thread and
            // its queue stay alive for the life of this controller.
            final HandlerThread thread = new HandlerThread("apm-net");
            thread.start();
            mScheduler = new HandlerScheduler(new Handler(thread.getLooper()));
        }
        mBackend = backend != null ? backend : new ConnectivityManagerBackend(context);
        mGames = games != null ? games : new DeviceGameClassifier();
    }

    /**
     * A freeze the freezer committed. Called with a service lock held, so this only
     * enqueues: the classification reads the package manager and the cut talks to the
     * connectivity service, and neither may block the caller.
     *
     * @param allowNet the merged {@code allowNetworkWhileFrozen} bit for the uid. A uid
     *                 with the bit is never cut and a previous cut of it is removed.
     * @param delayMs  the plain grace. A game gets at least the configured game grace.
     */
    void onFreezeConfirmed(int uid, @Nullable String packageName, int userId, boolean allowNet,
            long delayMs) {
        if (!isCutCandidate(uid)) {
            return;
        }
        mScheduler.post(() -> confirmedOnNetThread(uid, packageName, userId, allowNet, delayMs));
    }

    /** The uid is no longer frozen by this service. Idempotent. */
    void onUnfrozen(int uid) {
        if (uid < 0) {
            return;
        }
        mScheduler.post(() -> {
            clearGrace(uid);
            synchronized (mLock) {
                mCutRequested.remove(uid);
                mKeepConfirmed.remove(uid);
            }
            uncut(uid, "unfrozen");
            rebuildSnapshot();
        });
    }

    /**
     * Master switch off, or shadow mode on. Nothing may stay cut: with the gates closed
     * there is no freeze left for the cut to belong to.
     */
    void onMasterOffOrShadow() {
        mScheduler.post(() -> uncutAll("gates-closed"));
    }

    /**
     * The configuration was replaced. This is what makes the two shipped off switches
     * real: with the network cut off nothing stays cut, and a uid added to the debug list
     * gets its network back without waiting for a review. It is also the clean start of
     * this process, see {@link #washChain()}.
     */
    void onConfigChanged() {
        mScheduler.post(this::configChangedOnNetThread);
    }

    /**
     * Called once per review sweep, after every cut of that sweep was enqueued. Without
     * this each cut would run its own socket scan inside the connectivity service.
     */
    void flushDestroyTrigger() {
        mScheduler.post(this::flushOnNetThread);
    }

    /**
     * Publish the allow bit set: the uids whose merged policy allows the network while
     * frozen. The caller hands over an array it will not touch again; the read side takes
     * no lock.
     */
    void publishAllowNetSnapshot(@Nullable int[] uids) {
        final int[] next = uids == null ? NO_UIDS : uids;
        synchronized (mLock) {
            if (Arrays.equals(mAllowNet, next)) {
                return;
            }
            mAllowNet = next;
        }
        rebuildSnapshot();
    }

    /**
     * Whether the activity manager must keep reporting this uid as unfrozen. Linear scan
     * over a short array of ints, no lock and no allocation: the reporting path holds no
     * lock and must not take one.
     */
    boolean isNetworkKeptWhileFrozen(int uid) {
        final int[] snapshot = mReportSnapshot;
        for (int i = 0; i < snapshot.length; i++) {
            if (snapshot[i] == uid) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cached state only, so this is safe to call with a service lock held: the chain state
     * and the per uid rule are what this controller last applied, not a fresh read from the
     * connectivity service.
     */
    void dump(PrintWriter pw) {
        final ApmConfig config = mConfig.get();
        pw.print("  net enabled=");
        pw.print(config.networkFreezeEnabled);
        pw.print(" forceSocketDestroy=");
        pw.print(config.netForceSocketDestroy);
        pw.print(" delayMs=");
        pw.print(config.netFreezeDelayMs);
        pw.print(" gameDelayMs=");
        pw.print(config.netFreezeDelayGameMs);
        pw.print(" relaxUids=");
        pw.print(config.netRelaxUids.length);
        pw.print(" chain=");
        pw.print(CHAIN);
        pw.print(" chainEnabledApplied=");
        pw.print(chainEnabled());
        pw.print(" backendAvailable=");
        pw.print(mBackend.isAvailable());
        pw.print(" gameTable=");
        pw.println(ApmGamePackages.size());
        final int[] cut;
        final int[] deferred;
        final int[] requested;
        final int[] snapshot;
        final int failures;
        synchronized (mLock) {
            cut = toUidArray(mCutUids);
            deferred = toUidArray(mDeferred);
            requested = toUidArray(mCutRequested);
            snapshot = mReportSnapshot;
            failures = mApplyFailures;
        }
        pw.print("  net cut=");
        pw.print(cut.length);
        pw.print(" deferred=");
        pw.print(deferred.length);
        pw.print(" requested=");
        pw.print(requested.length);
        pw.print(" applyFailures=");
        pw.print(failures);
        pw.print(" reportSnapshot=");
        pw.println(snapshot.length);
        for (int i = 0; i < cut.length; i++) {
            final int uid = cut[i];
            final String pkg;
            final String result;
            final Integer attempts;
            synchronized (mLock) {
                pkg = mCutPackages.get(uid);
                result = mLastResult.get(uid);
                attempts = mAttempts.get(uid);
            }
            pw.print("    uid=");
            pw.print(uid);
            pw.print(" pkg=");
            pw.print(pkg);
            pw.print(" rule=");
            pw.print(ruleName(uid));
            pw.print(" attempts=");
            pw.print(attempts == null ? 0 : attempts);
            pw.print(" lastResult=");
            pw.println(result);
        }
    }

    private void confirmedOnNetThread(int uid, @Nullable String packageName, int userId,
            boolean allowNet, long delayMs) {
        final ApmConfig config = mConfig.get();
        if (!config.networkFreezeEnabled) {
            return;
        }
        if (allowNet) {
            // The merged policy lets this uid keep its network. Remove a cut from an
            // earlier review, and do not arm one: the allow bit is re-read on every cut.
            clearGrace(uid);
            uncut(uid, "allow-net");
            synchronized (mLock) {
                mKeepConfirmed.add(uid);
            }
            rebuildSnapshot();
            return;
        }
        synchronized (mLock) {
            mKeepConfirmed.remove(uid);
        }
        if (isRelaxed(uid, config)) {
            clearGrace(uid);
            uncut(uid, "relaxed");
            return;
        }
        synchronized (mLock) {
            if (mCutUids.contains(uid)) {
                // Already cut. A repeated review must not run the socket scan again.
                return;
            }
            if (mCutRequested.contains(uid)) {
                // Already waiting out its grace. The freezer confirms an already frozen uid
                // on every review, and the window is measured from the freeze, not from the
                // last review, so the armed timer stands.
                return;
            }
            mCutRequested.add(uid);
        }
        // A game is the one app that is awake enough to notice a cut in the middle of a
        // session, so it gets the longer grace. The plain delay is the caller's.
        final long grace = isGame(packageName, userId, uid)
                ? Math.max(delayMs, config.netFreezeDelayGameMs) : delayMs;
        if (grace <= 0L) {
            cut(uid, packageName, 1 /* attempt */);
            return;
        }
        armGrace(uid, packageName, grace);
    }

    /**
     * Write the deny rule for one uid. The chain is enabled later, once per sweep, because
     * enabling it is what makes the connectivity service walk its socket table on this
     * thread.
     */
    private void cut(int uid, @Nullable String packageName, int attempt) {
        final ApmConfig config = mConfig.get();
        if (!config.networkFreezeEnabled) {
            return;
        }
        synchronized (mLock) {
            if (!mCutRequested.contains(uid) || mCutUids.contains(uid)) {
                // Unfrozen, allowed, or already cut while this attempt was in flight.
                return;
            }
            mAttempts.put(uid, attempt);
            mCutPackages.put(uid, packageName);
        }
        if (isRelaxed(uid, config) || isAllowNet(uid)) {
            clearGrace(uid);
            return;
        }
        try {
            mBackend.setUidRule(CHAIN, uid, ConnectivityManager.FIREWALL_RULE_DENY);
        } catch (RuntimeException e) {
            noteApplyFailure(uid, "cut-failed:" + attempt);
            Slog.w(TAG, "net cut failed uid=" + uid + " attempt=" + attempt, e);
            if (attempt < MAX_CUT_ATTEMPTS) {
                mScheduler.postDelayed(
                        () -> cutOutsideSweep(uid, packageName, attempt + 1), RETRY_DELAY_MS);
            } else {
                // Fail open: the uid keeps its network and this controller stops trying.
                Slog.w(TAG, "net cut giving up uid=" + uid + " after " + attempt + " attempts");
            }
            return;
        }
        synchronized (mLock) {
            mCutUids.add(uid);
            mDeferred.remove(uid);
            mAttempts.remove(uid);
            mLastResult.put(uid, "cut");
            mChainDirty = true;
        }
        rebuildSnapshot();
    }

    /**
     * A cut that no review sweep is waiting to flush: the grace timer and the retry share
     * this, so the chain is enabled by the same call that wrote the rule.
     */
    private void cutOutsideSweep(int uid, @Nullable String packageName, int attempt) {
        cut(uid, packageName, attempt);
        flushOnNetThread();
    }

    /**
     * Remove a deny rule. Idempotent, and the rule is the only thing that has to be gone:
     * leaving a uid in the chain that no longer has a real process would cut the next
     * process of the same app id.
     */
    private void uncut(int uid, String reason) {
        final boolean had;
        synchronized (mLock) {
            had = mCutUids.remove(uid);
            mAttempts.remove(uid);
        }
        if (!had) {
            return;
        }
        writeAllow(uid, reason);
    }

    private void writeAllow(int uid, String reason) {
        try {
            mBackend.setUidRule(CHAIN, uid, ConnectivityManager.FIREWALL_RULE_ALLOW);
            synchronized (mLock) {
                mUncutAttempts.remove(uid);
            }
            return;
        } catch (RuntimeException e) {
            final int attempt;
            synchronized (mLock) {
                // Keep the uid: as far as this controller knows the chain still denies it,
                // and the next unfreeze, config change, or gates-closed sweep tries again.
                mCutUids.add(uid);
                mApplyFailures++;
                mLastResult.put(uid, "uncut-failed:" + reason);
                final Integer previous = mUncutAttempts.get(uid);
                attempt = previous == null ? 1 : previous + 1;
                mUncutAttempts.put(uid, attempt);
            }
            Slog.w(TAG, "net uncut failed uid=" + uid + " reason=" + reason, e);
            if (attempt < MAX_CUT_ATTEMPTS) {
                mScheduler.postDelayed(() -> writeAllow(uid, reason), RETRY_DELAY_MS);
            }
        }
    }

    private void uncutAll(String reason) {
        final int[] cut;
        final int[] timerUids;
        synchronized (mLock) {
            cut = toUidArray(mCutUids);
            timerUids = toUidArrayOfKeys(mGraceTimers);
            mCutRequested.clear();
            mDeferred.clear();
            mKeepConfirmed.clear();
            mGraceGenerations.clear();
        }
        for (int i = 0; i < timerUids.length; i++) {
            final Runnable timer = removeGraceTimer(timerUids[i]);
            if (timer != null) {
                mScheduler.remove(timer);
            }
        }
        for (int i = 0; i < cut.length; i++) {
            final int uid = cut[i];
            final boolean had;
            synchronized (mLock) {
                had = mCutUids.remove(uid);
                mAttempts.remove(uid);
            }
            if (had) {
                writeAllow(uid, reason);
            }
        }
        rebuildSnapshot();
        // The stronger statement: not only are the rules gone, the chain cannot drop a
        // packet even if a write above failed. This is why a call here is safe to make even
        // when the local set still holds uids whose rule removal failed.
        setChainEnabled(false);
    }

    private void armGrace(int uid, String packageName, long graceMs) {
        final int generation;
        final Runnable timer;
        synchronized (mLock) {
            generation = ++mNextGraceGeneration;
            mGraceGenerations.put(uid, generation);
            mDeferred.add(uid);
            mCutPackages.put(uid, packageName);
            mLastResult.put(uid, "grace");
        }
        timer = () -> graceExpired(uid, generation);
        final Runnable previous;
        synchronized (mLock) {
            previous = mGraceTimers.put(uid, timer);
        }
        if (previous != null) {
            mScheduler.remove(previous);
        }
        rebuildSnapshot();
        mScheduler.postDelayed(timer, graceMs);
    }

    private void graceExpired(int uid, int generation) {
        final Integer armed;
        synchronized (mLock) {
            armed = mGraceGenerations.get(uid);
            if (armed == null || armed != generation) {
                // Unfrozen, allowed, or re-armed during the grace window.
                return;
            }
            mGraceGenerations.remove(uid);
            mGraceTimers.remove(uid);
            mDeferred.remove(uid);
        }
        rebuildSnapshot();
        cutOutsideSweep(uid, packageNameOf(uid), 1 /* attempt */);
    }

    /** Drop an armed grace window, if any, and take the uid out of the report snapshot. */
    private void clearGrace(int uid) {
        final Runnable timer = removeGraceTimer(uid);
        if (timer != null) {
            mScheduler.remove(timer);
        }
        synchronized (mLock) {
            mDeferred.remove(uid);
        }
    }

    @Nullable
    private Runnable removeGraceTimer(int uid) {
        synchronized (mLock) {
            mGraceGenerations.remove(uid);
            return mGraceTimers.remove(uid);
        }
    }

    private void flushOnNetThread() {
        final boolean dirty;
        synchronized (mLock) {
            dirty = mChainDirty;
            mChainDirty = false;
        }
        if (!dirty) {
            return;
        }
        if (!mConfig.get().netForceSocketDestroy && chainEnabled()) {
            // The rules bite because the chain is on. The forced scan is off, so do not
            // make the connectivity service run it again just because rules were added.
            return;
        }
        setChainEnabled(true);
    }

    private void configChangedOnNetThread() {
        final ApmConfig config = mConfig.get();
        if (!config.networkFreezeEnabled) {
            uncutAll("net-freeze-off");
            return;
        }
        final int[] cut;
        synchronized (mLock) {
            cut = toUidArray(mCutUids);
        }
        for (int i = 0; i < cut.length; i++) {
            if (isRelaxed(cut[i], config)) {
                clearGrace(cut[i]);
                synchronized (mLock) {
                    mCutRequested.remove(cut[i]);
                }
                uncut(cut[i], "relaxed");
            }
        }
        washChain();
        rebuildSnapshot();
    }

    /**
     * Rules a previous system_server left in the chain are not enumerable through this
     * backend, so the clean start of this process drops the whole chain instead: disabled,
     * its rules cannot drop a packet. The next cut enables it again.
     */
    private void washChain() {
        final boolean wash;
        synchronized (mLock) {
            wash = !mChainWashed;
            mChainWashed = true;
        }
        if (wash) {
            setChainEnabled(false);
        }
    }

    private void setChainEnabled(boolean enabled) {
        try {
            mBackend.setChainEnabled(enabled);
        } catch (RuntimeException e) {
            synchronized (mLock) {
                mApplyFailures++;
            }
            Slog.w(TAG, "net chain " + (enabled ? "enable" : "disable") + " failed", e);
        }
    }

    private boolean chainEnabled() {
        try {
            return mBackend.isChainEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String ruleName(int uid) {
        try {
            return ruleToString(mBackend.getUidRule(uid));
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String ruleToString(int rule) {
        switch (rule) {
            case ConnectivityManager.FIREWALL_RULE_ALLOW:
                return "ALLOW";
            case ConnectivityManager.FIREWALL_RULE_DENY:
                return "DENY";
            case ConnectivityManager.FIREWALL_RULE_DEFAULT:
                return "DEFAULT";
            default:
                return Integer.toString(rule);
        }
    }

    /**
     * The snapshot the reporting path reads: the uids that must not be seen as frozen.
     * Rebuilt on the thread that owns the grace set, and published by whole replacement.
     */
    private void rebuildSnapshot() {
        final int[] allow;
        final int[] deferred;
        final int[] keep;
        synchronized (mLock) {
            allow = mAllowNet;
            deferred = toUidArray(mDeferred);
            keep = toUidArray(mKeepConfirmed);
        }
        final int[] relaxed = mConfig.get().netRelaxUids;
        final ArraySet<Integer> all = new ArraySet<>(allow.length + deferred.length + keep.length
                + relaxed.length);
        for (int i = 0; i < allow.length; i++) {
            all.add(allow[i]);
        }
        for (int i = 0; i < deferred.length; i++) {
            all.add(deferred[i]);
        }
        for (int i = 0; i < keep.length; i++) {
            all.add(keep[i]);
        }
        for (int i = 0; i < relaxed.length; i++) {
            all.add(relaxed[i]);
        }
        final int[] out = new int[all.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = all.valueAt(i);
        }
        mReportSnapshot = out;
    }

    private boolean isAllowNet(int uid) {
        synchronized (mLock) {
            final int[] allow = mAllowNet;
            for (int i = 0; i < allow.length; i++) {
                if (allow[i] == uid) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isRelaxed(int uid, ApmConfig config) {
        final int[] relaxed = config.netRelaxUids;
        for (int i = 0; i < relaxed.length; i++) {
            if (relaxed[i] == uid) {
                return true;
            }
        }
        return false;
    }

    private boolean isGame(@Nullable String packageName, int userId, int uid) {
        return mGames.isGame(packageName, userId, uid);
    }

    @Nullable
    private String packageNameOf(int uid) {
        synchronized (mLock) {
            return mCutPackages.get(uid);
        }
    }

    private void noteApplyFailure(int uid, String result) {
        synchronized (mLock) {
            mApplyFailures++;
            mLastResult.put(uid, result);
        }
    }

    /**
     * Shared uids and the system uid are not ours to cut. The socket destroy the
     * connectivity service runs matches sockets by owner uid, so cutting a shared app id
     * would take every process of it down. {@code UserHandle.getAppId(uid) ==
     * Process.SYSTEM_UID} is inside the range this rejects.
     */
    private static boolean isCutCandidate(int uid) {
        return UserHandle.getAppId(uid) >= Process.FIRST_APPLICATION_UID;
    }

    private static int[] toUidArray(ArraySet<Integer> set) {
        final int[] out = new int[set.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = set.valueAt(i);
        }
        return out;
    }

    private static int[] toUidArrayOfKeys(ArrayMap<Integer, Runnable> map) {
        final int[] out = new int[map.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = map.keyAt(i);
        }
        return out;
    }

    private static final class HandlerScheduler implements Scheduler {
        private final Handler mHandler;

        HandlerScheduler(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void post(Runnable runnable) {
            mHandler.post(runnable);
        }

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            mHandler.postDelayed(runnable, delayMs);
        }

        @Override
        public void remove(Runnable runnable) {
            mHandler.removeCallbacks(runnable);
        }
    }

    /**
     * The platform backend. Every call runs on the {@code apm-net} thread, so the lazy
     * lookup and the small records below need no lock of their own.
     *
     * <p>The read backs report what this controller last applied, not the live chain: the
     * chain getters are not part of the connectivity module API surface that the system
     * server links against. A device check reads the live state through
     * {@code dumpsys connectivity} instead.
     */
    private static final class ConnectivityManagerBackend implements NetCutBackend {
        @Nullable private final Context mContext;
        @Nullable private ConnectivityManager mCm;
        private boolean mLookupDone;
        private boolean mChainEnabled;
        private final ArraySet<Integer> mDenied = new ArraySet<>();

        ConnectivityManagerBackend(@Nullable Context context) {
            mContext = context;
        }

        @Override
        public void setUidRule(int chain, int uid, int rule) {
            cmOrThrow().setUidFirewallRule(chain, uid, rule);
            if (rule == ConnectivityManager.FIREWALL_RULE_DENY) {
                mDenied.add(uid);
            } else {
                mDenied.remove(uid);
            }
        }

        @Override
        public void setChainEnabled(boolean enabled) {
            cmOrThrow().setFirewallChainEnabled(CHAIN, enabled);
            mChainEnabled = enabled;
        }

        @Override
        public boolean isChainEnabled() {
            return mChainEnabled;
        }

        @Override
        public int getUidRule(int uid) {
            return mDenied.contains(uid) ? ConnectivityManager.FIREWALL_RULE_DENY
                    : ConnectivityManager.FIREWALL_RULE_ALLOW;
        }

        @Override
        public boolean isAvailable() {
            return cm() != null;
        }

        @Nullable
        private ConnectivityManager cm() {
            if (mCm == null && !mLookupDone) {
                mLookupDone = true;
                try {
                    mCm = mContext == null ? null
                            : mContext.getSystemService(ConnectivityManager.class);
                } catch (RuntimeException e) {
                    Slog.w(TAG, "connectivity manager unavailable", e);
                }
                if (mCm == null) {
                    Slog.w(TAG, "connectivity manager unavailable: network cuts are off");
                }
            }
            return mCm;
        }

        private ConnectivityManager cmOrThrow() {
            final ConnectivityManager cm = cm();
            if (cm == null) {
                throw new IllegalStateException("ConnectivityManager unavailable");
            }
            return cm;
        }
    }

    /**
     * Game classification for the grace window. The declared category is authoritative:
     * an app that ships {@code android:isGame} is a game. It is not the only source,
     * because a large share of the games on the target image never declare it, and an
     * undeclared category is not an answer. Those fall back to the image's own list, see
     * {@link ApmGamePackages}.
     */
    private static final class DeviceGameClassifier implements GameClassifier {
        @Override
        public boolean isGame(@Nullable String packageName, int userId, int uid) {
            if (packageName == null || packageName.length() == 0) {
                return false;
            }
            if (declaredGame(packageName, userId, uid)) {
                return true;
            }
            return ApmGamePackages.contains(packageName);
        }

        private static boolean declaredGame(String packageName, int userId, int uid) {
            try {
                final PackageManagerInternal pm =
                        LocalServices.getService(PackageManagerInternal.class);
                if (pm == null) {
                    return false;
                }
                final ApplicationInfo info = pm.getApplicationInfo(packageName, 0L /* flags */,
                        uid /* filterCallingUid */, userId);
                return info != null && info.category == ApplicationInfo.CATEGORY_GAME;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}
