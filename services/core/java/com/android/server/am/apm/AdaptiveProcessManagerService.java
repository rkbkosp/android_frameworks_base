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
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.am.ProcessList;
import com.android.server.am.apm.ApmConstants.ManagedState;
import com.android.server.am.apm.ApmEvent.ProcessSnapshot;
import com.android.server.pm.UserManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Adaptive process manager.
 *
 * <p>AMS remains the source of truth. Hooks copy pid, uid, name, and adj facts, then this
 * service scores them on its own thread. Freeze, compact, kill, and kernel writes run
 * when the master switch is on and shadow mode is off. Those are the defaults. Shadow mode
 * logs the decision and does not apply it. While the master switch is off, hooks do not
 * copy further snapshots, every uid this service froze is unfrozen, and new kills stop.
 */
public final class AdaptiveProcessManagerService {
    private static final String TAG = "Apm";
    private static final long SHELL_WAIT_MS = 2000L;

    /** Elapsed realtime, injectable so grace transitions do not sleep. */
    public interface Clock {
        long elapsedRealtime();

        Clock SYSTEM = () -> SystemClock.elapsedRealtime();
    }

    private final Clock mClock;
    private final ApmConfigManager mConfig = new ApmConfigManager();
    private final ProcessStateTracker mTracker = new ProcessStateTracker();
    private final PolicyEngine mPolicy = new PolicyEngine();
    private final ApmStats mStats = new ApmStats();
    private final ProtectionArbiter mArbiter = new ProtectionArbiter();
    private final ClearSceneTable mScenes = ClearSceneTable.get();
    private final TaskRestoreController mTasks = new TaskRestoreController();
    private final RevivalController mRevival = new RevivalController();
    private final ComponentExemptionTable mExemptions = new ComponentExemptionTable();
    /** Armed for the next oom-adj trim. Athena LMK adj 300. Not set in shadow mode. */
    private volatile String mArmedAdjScene;
    /**
     * Available MiB read on this thread before an athena_lmk trim is armed.
     * -1 means the read did not happen. Not read under the activity manager lock.
     */
    private volatile long mArmedAvailMb = -1L;
    private boolean mAvailableBytesOverrideSet;
    private long mAvailableBytesOverride = -1L;
    private final Object mLock = new Object();
    private final Object mQueueLock = new Object();
    private final Object mUnfreezeWait = new Object();
    /** Uids this service has frozen. Readable without {@link #mLock}. */
    private final Set<Integer> mFrozenUids = ConcurrentHashMap.newKeySet();
    private final ArrayList<DueAlarm> mDueAlarms = new ArrayList<>();
    @Nullable private final ApmExecutor mExecutor;
    private final FreezeController mFreeze;
    private final KernelKnobWriter mKnobs;
    @Nullable private final ApmPressure mPressure;
    private final MemoryController mMemory;
    private final FreezeController.Scheduler mScheduler = new FreezeController.Scheduler() {
        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            postDelayedExternal(runnable, delayMs);
        }

        @Override
        public void remove(Runnable runnable) {
            removeExternal(runnable);
        }
    };

    @Nullable private final Handler mHandler;
    @Nullable private final Context mContext;
    @Nullable private ApmEvent mPendingOom;
    private boolean mOomQueued;
    private boolean mSystemReady;
    private boolean mConfigListenerRegistered;
    private boolean mRoleObserversRegistered;
    /** userId -> current input-method package. Updated on this thread. */
    private final ConcurrentHashMap<Integer, String> mImeByUser = new ConcurrentHashMap<>();
    /** {@code userId:package} keys. Readers do not take the activity manager lock. */
    private final Set<String> mA11yKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> mVpnKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> mAudioKeys = ConcurrentHashMap.newKeySet();
    @Nullable private String mDeviceOwnerPackage;
    private int mDeviceOwnerUser = UserHandle.USER_NULL;
    @Nullable private ContentObserver mImeObserver;
    @Nullable private ContentObserver mA11yObserver;
    @Nullable private Boolean mShellEnabled;
    @Nullable private Boolean mShellShadow;
    @Nullable private Boolean mShellFreezer;
    /** Last foreground list written, or null if gates were closed and the next open must write. */
    @Nullable private int[] mPublishedFg;

    public AdaptiveProcessManagerService() {
        this(null /* context */, null /* executor */, null /* pressure */);
    }

    public AdaptiveProcessManagerService(@Nullable ApmExecutor executor) {
        this(null /* context */, executor, null /* pressure */);
    }

    public AdaptiveProcessManagerService(@Nullable ApmExecutor executor,
            @Nullable ApmPressure pressure) {
        this(null /* context */, executor, pressure);
    }

    public AdaptiveProcessManagerService(@Nullable Context context,
            @Nullable ApmExecutor executor, @Nullable ApmPressure pressure) {
        this(Clock.SYSTEM, true /* startThread */, executor, null /* knobs */, pressure, context);
    }

    @VisibleForTesting
    public AdaptiveProcessManagerService(Clock clock, boolean startThread) {
        this(clock, startThread, null /* executor */);
    }

    @VisibleForTesting
    public AdaptiveProcessManagerService(Clock clock, boolean startThread,
            @Nullable ApmExecutor executor) {
        // Tests must not create or write the phone's proc nodes.
        this(clock, startThread, executor,
                new KernelKnobWriter("/proc/apm-missing-fg-uids",
                        "/sys/module/apm_missing/parameters/vm_swappiness"),
                null /* pressure */, null /* context */);
    }

    @VisibleForTesting
    AdaptiveProcessManagerService(Clock clock, boolean startThread,
            @Nullable ApmExecutor executor, @Nullable KernelKnobWriter knobs,
            @Nullable ApmPressure pressure) {
        this(clock, startThread, executor, knobs, pressure, null /* context */);
    }

    @VisibleForTesting
    AdaptiveProcessManagerService(Clock clock, boolean startThread,
            @Nullable ApmExecutor executor, @Nullable KernelKnobWriter knobs,
            @Nullable ApmPressure pressure, @Nullable Context context) {
        mClock = clock != null ? clock : Clock.SYSTEM;
        mContext = context;
        mExecutor = executor;
        mKnobs = knobs != null ? knobs : new KernelKnobWriter();
        mPressure = pressure;
        if (startThread) {
            // Proc and sysfs writes happen on this thread.
            final ServiceThread thread = new ServiceThread("apm",
                    Process.THREAD_PRIORITY_BACKGROUND, true /* allowIo */);
            thread.start();
            mHandler = new Handler(thread.getLooper());
        } else {
            mHandler = null;
        }
        mFreeze = new FreezeController(mExecutor, mScheduler, mFrozenUids, mArbiter, mExemptions);
        mMemory = new MemoryController(mExecutor, mFreeze, mScheduler, mKnobs, mPressure, mStats,
                this::onMemoryRecheck, mArbiter);
    }

    /** Master switch. Read from the activity manager thread; does not take that lock. */
    public boolean isEnabled() {
        return mConfig.get().enabled;
    }

    /**
     * Starts config refresh on the policy thread. Does not read DeviceConfig on the caller,
     * and does not need the activity manager lock.
     */
    public void systemReady() {
        if (mHandler == null || mSystemReady) {
            return;
        }
        mSystemReady = true;
        mHandler.post(this::registerConfigListener);
    }

    public void noteProcessStarted(int pid, int uid, int userId, String processName,
            String packageName, long startSeq, boolean persistent) {
        if (!isEnabled()) {
            return;
        }
        post(ApmEvent.processStarted(pid, uid, userId, processName, packageName, startSeq,
                persistent));
    }

    public void noteProcessDied(int pid, int uid, int userId, String processName, long startSeq) {
        if (!isEnabled()) {
            return;
        }
        post(ApmEvent.processDied(pid, uid, userId, processName, startSeq));
    }

    public void noteTopResumed(int uid, int pid, int userId, @Nullable String processName) {
        if (!isEnabled()) {
            return;
        }
        post(ApmEvent.topResumed(uid, pid, userId, processName));
    }

    /**
     * One snapshot for a completed adj pass. Coalesced to the latest copy if the policy
     * thread is still applying the previous pass. The list must not contain live records.
     */
    public void postOomAdjCompleted(int oomAdjReason, List<ProcessSnapshot> processes) {
        if (!isEnabled()) {
            return;
        }
        final ApmEvent event = ApmEvent.oomAdjCompleted(oomAdjReason, processes);
        synchronized (mQueueLock) {
            mPendingOom = event;
            if (mOomQueued) {
                return;
            }
            mOomQueued = true;
        }
        postDrainOom();
    }

    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            ApmShellCommand.dump(pw, mConfig.get(), mTracker, mStats);
            pw.print("  kernel missing=");
            pw.print(mKnobs.getMissingCount());
            pw.print(" short=");
            pw.print(mKnobs.getShortCount());
            pw.print(" ok=");
            pw.println(mKnobs.getOkCount());
            mArbiter.dump(pw, mClock.elapsedRealtime());
            pw.print("  clearScenes=");
            pw.println(mScenes.sceneCount());
            mTasks.dump(pw);
            pw.print("  revival slots=");
            pw.print(mRevival.slotsUsed(mClock.elapsedRealtime()));
            pw.print(" energy=");
            pw.println(mRevival.energyUsed(mClock.elapsedRealtime()));
        }
    }

    public void explain(PrintWriter pw, String target) {
        synchronized (mLock) {
            ApmShellCommand.explain(pw, mConfig.get(), mTracker, target);
            final Integer uid = parseExplainUid(target);
            if (uid != null) {
                final ApmProcessRecord rec = mTracker.get(uid);
                if (rec != null && rec.primaryPackage() != null) {
                    mArbiter.dumpPackage(pw, rec.primaryPackage(), rec.userId,
                            mClock.elapsedRealtime());
                }
            } else if (target != null) {
                mArbiter.dumpPackage(pw, target, 0, mClock.elapsedRealtime());
            }
        }
    }

    /** Shadow still computes policy. Freeze, kill, adj, revival, and kernel writes do not run. */
    public boolean isShadowMode() {
        return mConfig.get().shadowMode;
    }

    /**
     * Cached-kill gate for {@code OomAdjuster}. User force-stop still wins: a force-stopped
     * process is not spared. Does not take the activity manager lock.
     */
    public boolean shouldSpareCachedKill(String packageName, int userId,
            boolean processForceStopped) {
        return mArbiter.shouldSpareCachedKill(packageName, userId, mClock.elapsedRealtime(),
                processForceStopped);
    }

    public ProtectionArbiter getArbiter() {
        return mArbiter;
    }

    /** Record a user force-stop. Allows lose. The ban bits of a higher layer stay set. */
    public void noteUserForceStop(String packageName, int userId) {
        if (packageName == null) {
            return;
        }
        mArbiter.setUserForceStop(packageName, userId, true);
        mRevival.noteForceStop(packageName, userId, true);
    }

    /**
     * Revival request. Category 1 does not consume a slot. Shadow computes and does not
     * arm the adj bump. Does not start {@code com.heytap.mcs}.
     */
    public RevivalController.Result requestRevival(String callerPackage, String action,
            String targetPackage, int userId) {
        final long now = mClock.elapsedRealtime();
        final boolean stopped = targetPackage != null
                && mArbiter.merge(targetPackage, userId, now).forceStopped;
        return mRevival.request(callerPackage, action, targetPackage, userId, now,
                isShadowMode(), stopped);
    }

    /**
     * 30 second adj bump for an applied revival. -1 when there is no grant, shadow is on,
     * or the user force-stopped the package. The bump is not {@code denyKill}. A higher
     * layer's {@code denyKill} is left in place and is not cleared by this bump.
     */
    public int revivalBumpAdj(String packageName, int userId, boolean processForceStopped) {
        if (isShadowMode() || processForceStopped || packageName == null) {
            return -1;
        }
        final long now = mClock.elapsedRealtime();
        final ProtectionArbiter.Merged merged = mArbiter.merge(packageName, userId, now);
        if (merged.forceStopped || merged.backgroundRestricted) {
            return -1;
        }
        if (merged.denyKill && merged.denyKillLayer != null
                && merged.denyKillLayer.outranks(ProtectionArbiter.Layer.DYNAMIC)) {
            // Higher denyKill already decides the kill. The bump does not replace it.
            return -1;
        }
        return mRevival.bumpAdj(packageName, userId, now, false);
    }

    @VisibleForTesting
    public RevivalController getRevivalForTest() {
        return mRevival;
    }

    public ComponentExemptionTable exemptions() {
        return mExemptions;
    }

    /** Black list denies delivery. Empty lists do not. */
    public boolean mayDeliver(ComponentExemptionTable.Kind kind, String callerPackage,
            String targetPackage, String name) {
        return mExemptions.mayDeliver(kind, callerPackage, targetPackage, name, 0);
    }

    /**
     * Alarm delivery to a uid this service froze. The allow bit and the alarm white list
     * are the only passes. Force-stop blocks. A uid that is not frozen is not blocked here.
     */
    public boolean mayDeliverBroadcast(String callerPackage, String targetPackage, String action,
            boolean alarm, int uid) {
        if (!mExemptions.mayDeliver(ComponentExemptionTable.Kind.BROADCAST, callerPackage,
                targetPackage, action, 0)) {
            return false;
        }
        if (!alarm || uid < 0 || !mFrozenUids.contains(uid)) {
            return true;
        }
        if (!alarmWakeupAllowed(uid, targetPackage, action)) {
            return false;
        }
        // Caller holds the activity manager lock. Post the unfreeze and do not wait.
        if (!ApmConstants.DEFAULT_DEFER_ALARMS) {
            noteStartUnfreeze(uid);
        }
        return true;
    }

    /**
     * Job start when the activity manager lock is already held. An allowed frozen uid is
     * unfrozen by posting. This does not wait and does not defer. {@code DEFAULT_DEFER_JOBS}
     * stays false, so a denied job is the existing deny, not a new deferral.
     */
    public void noteAllowedJobWakeup(int uid, @Nullable String packageName,
            @Nullable String component, boolean amsLockHeld) {
        if (ApmConstants.DEFAULT_DEFER_JOBS || !jobWakeupAllowed(uid, packageName, component)) {
            return;
        }
        if (amsLockHeld) {
            noteStartUnfreeze(uid);
        } else {
            awaitUnfreeze(uid);
        }
    }

    /**
     * Alarm delivery when the activity manager lock is not held. Waits up to
     * {@link ApmConstants#UNFREEZE_WAIT_MS}. Does not defer.
     */
    public void noteAllowedAlarmWakeup(int uid, @Nullable String packageName,
            @Nullable String action) {
        if (ApmConstants.DEFAULT_DEFER_ALARMS || !alarmWakeupAllowed(uid, packageName, action)) {
            return;
        }
        awaitUnfreeze(uid);
    }

    private boolean jobWakeupAllowed(int uid, String packageName, String component) {
        if (uid < 0 || packageName == null || !mFrozenUids.contains(uid)) {
            return false;
        }
        if (mExemptions.jobDenied(packageName)) {
            return false;
        }
        if (mExemptions.jobAllowed(packageName, component)) {
            return true;
        }
        final ProtectionArbiter.Merged merged = mArbiter.merge(packageName,
                UserHandle.getUserId(uid), mClock.elapsedRealtime());
        return merged.allowJobWakeup && !merged.forceStopped;
    }

    private boolean alarmWakeupAllowed(int uid, String packageName, String action) {
        if (uid < 0 || packageName == null || !mFrozenUids.contains(uid)) {
            return false;
        }
        if (mExemptions.check(ComponentExemptionTable.Kind.ALARM, false /* calling */,
                packageName, action, 0) == ComponentExemptionTable.Decision.DENY) {
            return false;
        }
        if (mExemptions.alarmAllowed(packageName, action)) {
            return true;
        }
        final ProtectionArbiter.Merged merged = mArbiter.merge(packageName,
                UserHandle.getUserId(uid), mClock.elapsedRealtime());
        return merged.allowAlarmWakeup && !merged.forceStopped;
    }

    /** Job start of a frozen uid. Sync-job black beats a job allow. Not frozen means deliver. */
    public boolean frozenDeniesJob(int uid, String packageName, String component) {
        if (uid < 0 || !mFrozenUids.contains(uid)) {
            return false;
        }
        if (mExemptions.jobDenied(packageName)) {
            return true;
        }
        if (mExemptions.jobAllowed(packageName, component)) {
            return false;
        }
        final ProtectionArbiter.Merged merged = mArbiter.merge(packageName,
                UserHandle.getUserId(uid), mClock.elapsedRealtime());
        if (merged.forceStopped) {
            return true;
        }
        return !merged.allowJobWakeup;
    }

    public int fastFreezeTimeout(String packageName) {
        return mFreeze.fastFreezeTimeoutMs(packageName);
    }

    public boolean fastFreezePermitted(String packageName) {
        return mFreeze.fastFreezePermitted(packageName);
    }

    /**
     * Run one clear scene. {@code key} is a caller name, or a scene name when that name
     * is unique. Repeated {@code cc_name} values are not keys. Shadow mode still selects
     * the scene and still applies skips and {@code denyKill}, and does not kill, force-stop,
     * or remove a task.
     */
    public void runClearScene(String key) {
        if (key == null) {
            return;
        }
        post(() -> applyClearScene(key));
    }

    /**
     * Clean type the force-stop would use. 2 stays 2 when the package is not eligible
     * or the user already force-stopped it. 3 keeps the recent task and still kills
     * the process. Shadow mode is applied by the caller, which must not change
     * {@code setRemoved} when this returns 3 but shadow is on.
     */
    public int rewriteCleanType(String packageName, int userId, int uid, int incoming,
            boolean hasLauncherIcon, boolean systemApp) {
        return mTasks.rewrite(packageName, userId, uid, incoming, hasLauncherIcon, systemApp);
    }

    public void noteTaskRestoreForceStop(String packageName, int userId) {
        mTasks.noteRuntimeForceStop(packageName, userId);
    }

    @VisibleForTesting
    public TaskRestoreController getTaskRestoreForTest() {
        return mTasks;
    }

    /** Consumed by one oom-adj pass. Null if nothing is armed. */
    public String consumeArmedAdjScene() {
        final String scene = mArmedAdjScene;
        mArmedAdjScene = null;
        return scene;
    }

    /** The user started the package again. The force-stop row is the one that drops. */
    public void noteUserForceStopCleared(String packageName, int userId) {
        if (packageName == null) {
            return;
        }
        mArbiter.setUserForceStop(packageName, userId, false);
    }

    @VisibleForTesting
    public void setEnabledForTest(boolean enabled) {
        if (!mConfig.tryReplace(mConfig.get().withEnabled(enabled))) {
            throw new IllegalStateException("rejected enabled=" + enabled);
        }
        onGatesChanged();
    }

    @VisibleForTesting
    public void setShadowModeForTest(boolean shadowMode) {
        if (!mConfig.tryReplace(mConfig.get().withShadowMode(shadowMode))) {
            throw new IllegalStateException("rejected shadowMode=" + shadowMode);
        }
        onGatesChanged();
    }

    @VisibleForTesting
    public void setFreezerEnabledForTest(boolean freezerEnabled) {
        if (!mConfig.tryReplace(mConfig.get().withFreezerEnabled(freezerEnabled))) {
            throw new IllegalStateException("rejected freezerEnabled=" + freezerEnabled);
        }
        onGatesChanged();
    }

    @VisibleForTesting
    public void setProtectionForTest(AppProtectionPolicy policy) {
        mArbiter.put(policy);
        reevaluateAll();
    }

    @VisibleForTesting
    public ProtectionArbiter getArbiterForTest() {
        return mArbiter;
    }

    @VisibleForTesting
    public void setMemoryEnabledForTest(boolean memoryEnabled) {
        if (!mConfig.tryReplace(mConfig.get().withMemoryEnabled(memoryEnabled))) {
            throw new IllegalStateException("rejected memoryEnabled=" + memoryEnabled);
        }
        onGatesChanged();
    }

    @VisibleForTesting
    @Nullable ManagedState getStateForTest(int uid) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec == null ? null : rec.state;
        }
    }

    @VisibleForTesting
    int getPidCountForTest(int uid) {
        synchronized (mLock) {
            return mTracker.pidCount(uid);
        }
    }

    @VisibleForTesting
    @Nullable String getPrimaryPackageForTest(int uid) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec == null ? null : rec.primaryPackage();
        }
    }

    @VisibleForTesting
    boolean hasProcessNameForTest(int uid, String processName) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec != null && rec.processNames.contains(processName);
        }
    }

    @VisibleForTesting
    @Nullable PolicyDecision getLastDecisionForTest(int uid) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec == null ? null : rec.lastDecision;
        }
    }

    @VisibleForTesting
    int getRecordedDecisionCountForTest() {
        synchronized (mLock) {
            return mStats.recorded();
        }
    }

    @VisibleForTesting
    int getDroppedActionCountForTest() {
        synchronized (mLock) {
            return mStats.dropped();
        }
    }

    /** Always zero. Present so tests can show the shadow path performs no action. */
    @VisibleForTesting
    int getExecutedActionCountForTest() {
        synchronized (mLock) {
            return mStats.executed();
        }
    }

    private void applyClearScene(String key) {
        final ClearScene scene = mScenes.select(key);
        if (scene == null || !mConfig.get().enabled) {
            return;
        }
        final boolean shadow = mConfig.get().shadowMode;
        final Integer strategy = mScenes.externalStrategy(key);
        final boolean forceStop = strategy != null && strategy == 1;
        final boolean removeTask = scene.flag(ClearScene.DO_REMOVE_TASK);
        final boolean athenaLmk = "athena_lmk".equals(scene.name);
        if (athenaLmk) {
            // Meminfo is read here, on this thread, before the trim kill is armed.
            mArmedAvailMb = readAvailableMb();
            if (!shadow) {
                mArmedAdjScene = scene.name;
            }
        }
        final ArrayList<SceneVictim> victims = new ArrayList<>();
        final long now = mClock.elapsedRealtime();
        synchronized (mLock) {
            for (int i = 0; i < mTracker.size(); i++) {
                final ApmProcessRecord rec = mTracker.valueAt(i);
                if (ClearSceneRunner.skipped(scene, factsFor(rec))) {
                    continue;
                }
                final String pkg = rec.primaryPackage();
                if (pkg == null) {
                    continue;
                }
                if (athenaLmk && ClearSceneTable.sappShouldKillMb(pkg) >= 0
                        && !athenaLmkKillsPackage(pkg, rec.minAdj)) {
                    continue;
                }
                if (sparedByPolicy(rec, now)) {
                    continue;
                }
                victims.add(new SceneVictim(rec.uid, pkg, rec.userId, livePids(rec)));
            }
        }
        if (shadow || mExecutor == null) {
            return;
        }
        for (int i = 0; i < victims.size(); i++) {
            final SceneVictim victim = victims.get(i);
            if (forceStop) {
                mExecutor.forceStopForScene(victim.packageName, victim.userId,
                        "apm:scene:" + key);
            } else {
                mExecutor.killForScene(victim.uid, victim.pids, "apm:scene:" + key);
            }
            if (removeTask) {
                mExecutor.removeTasksForPackage(victim.packageName, victim.userId);
            }
        }
    }

    private boolean sparedByPolicy(ApmProcessRecord rec, long now) {
        if (rec.forceStopped) {
            return false;
        }
        if (rec.packages.size() == 0) {
            return mArbiter.shouldSpareCachedKill(rec.primaryPackage(), rec.userId, now, false);
        }
        for (int i = 0; i < rec.packages.size(); i++) {
            if (mArbiter.shouldSpareCachedKill(rec.packages.valueAt(i), rec.userId, now, false)) {
                return true;
            }
        }
        return false;
    }

    private ClearSceneRunner.Facts factsFor(ApmProcessRecord rec) {
        final ClearSceneRunner.Facts facts = new ClearSceneRunner.Facts();
        facts.foreground = rec.foreground || rec.visible;
        facts.foregroundService = rec.foregroundService;
        facts.home = rec.home;
        facts.persistent = rec.persistent;
        facts.visibleWindow = rec.visible;
        facts.curAdj = rec.minAdj;
        facts.perceptible = rec.minAdj <= ProcessList.PERCEPTIBLE_APP_ADJ;
        facts.system = rec.systemUid;
        fillClearFacts(facts, rec.primaryPackage(), rec.userId, rec.foregroundAudio);
        for (int i = 0; i < rec.packages.size(); i++) {
            fillClearFacts(facts, rec.packages.valueAt(i), rec.userId, rec.foregroundAudio);
        }
        return facts;
    }

    /**
     * Role and session facts already cached on this thread. Does not read settings,
     * app ops, or audio service, so it is safe while the activity manager lock is held.
     * {@code liveAudio} is the boolean copied out of the process record under that lock.
     */
    public void fillClearFacts(ClearSceneRunner.Facts facts, @Nullable String packageName,
            int userId, boolean liveAudio) {
        if (facts == null) {
            return;
        }
        if (liveAudio) {
            facts.audio = true;
        }
        if (packageName == null) {
            return;
        }
        if (packageName.equals(mImeByUser.get(userId))) {
            facts.inputMethod = true;
        }
        final String key = roleKey(userId, packageName);
        if (mVpnKeys.contains(key)) {
            facts.vpn = true;
        }
        if (mA11yKeys.contains(key)) {
            facts.accessibility = true;
        }
        if (mAudioKeys.contains(key)) {
            facts.audio = true;
        }
    }

    /**
     * Athena LMK trim. Adj below 300 never kills. A package in {@code sapp_should_be_kill}
     * kills only when the available MiB read on this thread is at or below its threshold.
     * Packages that are not in that list keep the adj gate only.
     */
    public boolean athenaLmkKillsPackage(@Nullable String packageName, int curAdj) {
        if (curAdj < ClearSceneTable.ATHENA_LMK_ADJ_THRESHOLD) {
            return false;
        }
        final int threshold = ClearSceneTable.sappShouldKillMb(packageName);
        if (threshold < 0) {
            return true;
        }
        final long availMb = mArmedAvailMb;
        return availMb >= 0L && availMb <= threshold;
    }

    @VisibleForTesting
    public void setAvailableBytesForTest(long bytes) {
        mAvailableBytesOverride = bytes;
        mAvailableBytesOverrideSet = true;
    }

    /** Same value {@code ProcessList#getMemoryInfo} stores in {@code availMem}. */
    private long readAvailableMb() {
        final long bytes;
        if (mAvailableBytesOverrideSet) {
            bytes = mAvailableBytesOverride;
        } else {
            try {
                bytes = Process.getMemAvailable();
            } catch (Throwable t) {
                return -1L;
            }
        }
        if (bytes < 0L) {
            return -1L;
        }
        return bytes / (1024L * 1024L);
    }

    @VisibleForTesting
    public void noteCurrentInputMethodForTest(int userId, @Nullable String packageName) {
        noteCurrentInputMethod(userId, packageName);
    }

    /**
     * Settings and app-ops observers. Runs on this thread. Does not take the activity
     * manager lock. Navigation is not registered: nothing in-process names the navigating
     * package. Audio focus is not registered: the adj snapshot already copies the audio
     * capability and the media-playback foreground-service type.
     */
    private void registerRoleObservers() {
        if (mRoleObserversRegistered || mContext == null || mHandler == null) {
            return;
        }
        mRoleObserversRegistered = true;
        final android.content.ContentResolver resolver = mContext.getContentResolver();
        mImeObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                    UserHandle user) {
                if (user == null || user.getIdentifier() == UserHandle.USER_ALL) {
                    refreshAllInputMethods();
                } else {
                    refreshInputMethod(user.getIdentifier());
                }
            }
        };
        mA11yObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                    UserHandle user) {
                if (user == null || user.getIdentifier() == UserHandle.USER_ALL) {
                    refreshAllAccessibility();
                } else {
                    refreshAccessibility(user.getIdentifier());
                }
            }
        };
        try {
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                    false, mImeObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false, mA11yObserver, UserHandle.USER_ALL);
        } catch (Throwable t) {
            Slog.w(TAG, "role settings observer not registered", t);
        }
        refreshAllInputMethods();
        refreshAllAccessibility();
        refreshDeviceOwner();
        registerVpnWatch();
    }

    private void refreshAllInputMethods() {
        for (int userId : userIds()) {
            refreshInputMethod(userId);
        }
    }

    private void refreshInputMethod(int userId) {
        if (mContext == null || userId < 0) {
            return;
        }
        String pkg = null;
        try {
            final String flat = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, userId);
            if (flat != null) {
                final ComponentName cn = ComponentName.unflattenFromString(flat);
                if (cn != null) {
                    pkg = cn.getPackageName();
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "input method read failed for user " + userId, t);
            return;
        }
        noteCurrentInputMethod(userId, pkg);
    }

    private void noteCurrentInputMethod(int userId, @Nullable String packageName) {
        final String previous = mImeByUser.get(userId);
        if (previous == null && packageName == null) {
            return;
        }
        if (previous != null && previous.equals(packageName)) {
            return;
        }
        if (previous != null) {
            mImeByUser.remove(userId, previous);
            mArbiter.setHardRole(previous, userId, "ime", false);
        }
        if (packageName != null) {
            mImeByUser.put(userId, packageName);
            mArbiter.setHardRole(packageName, userId, "ime", true);
        }
        reevaluateAll();
    }

    private void refreshAllAccessibility() {
        for (int userId : userIds()) {
            refreshAccessibility(userId);
        }
    }

    /**
     * {@link com.android.server.AccessibilityManagerInternal} does not list enabled
     * service packages. The secure setting is the same kind of fact as the input method
     * and is read on this thread.
     */
    private void refreshAccessibility(int userId) {
        if (mContext == null || userId < 0) {
            return;
        }
        String raw = null;
        try {
            raw = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, userId);
        } catch (Throwable t) {
            Slog.w(TAG, "accessibility setting read failed for user " + userId, t);
            return;
        }
        final ArraySet<String> next = new ArraySet<>();
        if (raw != null && raw.length() > 0) {
            final String[] parts = raw.split(":");
            for (int i = 0; i < parts.length; i++) {
                final ComponentName cn = ComponentName.unflattenFromString(parts[i]);
                if (cn != null && cn.getPackageName() != null) {
                    next.add(cn.getPackageName());
                }
            }
        }
        final String prefix = userId + ":";
        final ArrayList<String> stale = new ArrayList<>();
        for (String key : mA11yKeys) {
            if (key.startsWith(prefix) && !next.contains(key.substring(prefix.length()))) {
                stale.add(key);
            }
        }
        boolean changed = !stale.isEmpty();
        for (int i = 0; i < stale.size(); i++) {
            final String key = stale.get(i);
            mA11yKeys.remove(key);
            mArbiter.setHardRole(key.substring(prefix.length()), userId, "accessibility", false);
        }
        for (int i = 0; i < next.size(); i++) {
            final String pkg = next.valueAt(i);
            if (mA11yKeys.add(roleKey(userId, pkg))) {
                mArbiter.setHardRole(pkg, userId, "accessibility", true);
                changed = true;
            }
        }
        if (changed) {
            reevaluateAll();
        }
    }

    private void refreshDeviceOwner() {
        if (mContext == null) {
            return;
        }
        String pkg = null;
        int userId = UserHandle.USER_SYSTEM;
        try {
            final DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
            if (dpm == null) {
                return;
            }
            final ComponentName owner = dpm.getDeviceOwnerComponentOnAnyUser();
            if (owner != null) {
                pkg = owner.getPackageName();
                final int ownerUser = dpm.getDeviceOwnerUserId();
                if (ownerUser >= 0) {
                    userId = ownerUser;
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "device owner lookup failed", t);
            return;
        }
        if (pkg == null && mDeviceOwnerPackage == null) {
            return;
        }
        if (pkg != null && pkg.equals(mDeviceOwnerPackage) && userId == mDeviceOwnerUser) {
            return;
        }
        if (mDeviceOwnerPackage != null) {
            mArbiter.setHardRole(mDeviceOwnerPackage, mDeviceOwnerUser, "device-owner", false);
        }
        mDeviceOwnerPackage = pkg;
        mDeviceOwnerUser = pkg == null ? UserHandle.USER_NULL : userId;
        if (pkg != null) {
            mArbiter.setHardRole(pkg, userId, "device-owner", true);
        }
        reevaluateAll();
    }

    private void registerVpnWatch() {
        if (mContext == null) {
            return;
        }
        try {
            final AppOpsManager ops = mContext.getSystemService(AppOpsManager.class);
            if (ops == null) {
                return;
            }
            final AppOpsManager.OnOpChangedListener listener = (op, packageName) ->
                    post(() -> recheckVpnPackage(packageName));
            ops.startWatchingMode(AppOpsManager.OP_ACTIVATE_VPN, null /* all packages */,
                    listener);
            ops.startWatchingMode(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN, null, listener);
        } catch (Throwable t) {
            Slog.w(TAG, "vpn app-op watch not registered", t);
        }
    }

    private void recheckVpnPackage(@Nullable String packageName) {
        if (packageName == null || mContext == null) {
            return;
        }
        final ArrayList<int[]> ids = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mTracker.size(); i++) {
                final ApmProcessRecord rec = mTracker.valueAt(i);
                if (rec.matchesName(packageName)) {
                    ids.add(new int[] {rec.uid, rec.userId});
                }
            }
        }
        final AppOpsManager ops = mContext.getSystemService(AppOpsManager.class);
        if (ops == null) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < ids.size(); i++) {
            changed |= setVpnAllowed(ids.get(i)[0], ids.get(i)[1], packageName,
                    vpnOpAllowed(ops, ids.get(i)[0], packageName));
        }
        if (changed) {
            reevaluateAll();
        }
    }

    private void refreshVpnSessions(@Nullable List<ProcessSnapshot> processes) {
        if (mContext == null || processes == null || processes.isEmpty()) {
            return;
        }
        final AppOpsManager ops = mContext.getSystemService(AppOpsManager.class);
        if (ops == null) {
            return;
        }
        final ArraySet<String> seen = new ArraySet<>();
        boolean changed = false;
        for (int i = 0; i < processes.size(); i++) {
            final ProcessSnapshot snap = processes.get(i);
            if (snap == null || snap.packageName == null || snap.uid < 0) {
                continue;
            }
            final String key = roleKey(snap.userId, snap.packageName);
            if (!seen.add(key)) {
                continue;
            }
            changed |= setVpnAllowed(snap.uid, snap.userId, snap.packageName,
                    vpnOpAllowed(ops, snap.uid, snap.packageName));
        }
        if (changed) {
            reevaluateAll();
        }
    }

    private static boolean vpnOpAllowed(AppOpsManager ops, int uid, String packageName) {
        try {
            if (ops.checkOpNoThrow(AppOpsManager.OP_ACTIVATE_VPN, uid, packageName)
                    == AppOpsManager.MODE_ALLOWED) {
                return true;
            }
            return ops.checkOpNoThrow(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN, uid, packageName)
                    == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** @return true when the cached session changed */
    private boolean setVpnAllowed(int uid, int userId, String packageName, boolean allowed) {
        final String key = roleKey(userId, packageName);
        if (allowed) {
            if (!mVpnKeys.add(key)) {
                return false;
            }
            mArbiter.setRuntimeSession(packageName, userId, "vpn", true);
            return true;
        }
        if (!mVpnKeys.remove(key)) {
            return false;
        }
        mArbiter.setRuntimeSession(packageName, userId, "vpn", false);
        return true;
    }

    private void applyAudioRoles(@Nullable List<ProcessSnapshot> processes) {
        final ArraySet<String> now = new ArraySet<>();
        if (processes != null) {
            for (int i = 0; i < processes.size(); i++) {
                final ProcessSnapshot snap = processes.get(i);
                if (snap == null || snap.packageName == null || !snap.foregroundAudio) {
                    continue;
                }
                now.add(roleKey(snap.userId, snap.packageName));
            }
        }
        boolean changed = false;
        final ArrayList<String> stale = new ArrayList<>();
        for (String key : mAudioKeys) {
            if (!now.contains(key)) {
                stale.add(key);
            }
        }
        for (int i = 0; i < stale.size(); i++) {
            final String key = stale.get(i);
            mAudioKeys.remove(key);
            final int cut = key.indexOf(':');
            if (cut <= 0) {
                continue;
            }
            mArbiter.setRuntimeSession(key.substring(cut + 1),
                    Integer.parseInt(key.substring(0, cut)), "audio", false);
            changed = true;
        }
        for (int i = 0; i < now.size(); i++) {
            final String key = now.valueAt(i);
            if (!mAudioKeys.add(key)) {
                continue;
            }
            final int cut = key.indexOf(':');
            mArbiter.setRuntimeSession(key.substring(cut + 1),
                    Integer.parseInt(key.substring(0, cut)), "audio", true);
            changed = true;
        }
        if (changed) {
            reevaluateAll();
        }
    }

    private int[] userIds() {
        try {
            final UserManagerInternal users = LocalServices.getService(UserManagerInternal.class);
            if (users != null) {
                final int[] ids = users.getUserIds();
                if (ids != null && ids.length > 0) {
                    return ids;
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "user list unavailable", t);
        }
        return new int[] {UserHandle.USER_SYSTEM};
    }

    private static String roleKey(int userId, String packageName) {
        return userId + ":" + packageName;
    }

    private static int[] livePids(ApmProcessRecord rec) {
        int count = 0;
        for (int i = 0; i < rec.pids.size(); i++) {
            if (rec.pids.valueAt(i).pid > 0) {
                count++;
            }
        }
        final int[] pids = new int[count];
        int write = 0;
        for (int i = 0; i < rec.pids.size(); i++) {
            final int pid = rec.pids.valueAt(i).pid;
            if (pid > 0) {
                pids[write++] = pid;
            }
        }
        return pids;
    }

    private static final class SceneVictim {
        final int uid;
        final String packageName;
        final int userId;
        final int[] pids;

        SceneVictim(int uid, String packageName, int userId, int[] pids) {
            this.uid = uid;
            this.packageName = packageName;
            this.userId = userId;
            this.pids = pids;
        }
    }

    private void reevaluateAll() {
        final Runnable runnable = () -> {
            synchronized (mLock) {
                final long now = mClock.elapsedRealtime();
                final ApmConfig config = mConfig.get();
                for (int i = 0; i < mTracker.size(); i++) {
                    reviewFreezeLocked(mTracker.valueAt(i), config, now);
                }
            }
        };
        if (mHandler != null) {
            mHandler.post(runnable);
        } else {
            runnable.run();
        }
    }

    private static Integer parseExplainUid(String target) {
        if (target == null || target.length() == 0 || target.length() > 9) {
            return null;
        }
        for (int i = 0; i < target.length(); i++) {
            final char c = target.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Integer.parseInt(target);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void post(ApmEvent event) {
        if (mHandler != null) {
            mHandler.post(() -> handleEvent(event));
        } else {
            handleEvent(event);
        }
    }

    private void post(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (mHandler != null) {
            mHandler.post(runnable);
        } else {
            runnable.run();
        }
    }

    private void postDrainOom() {
        if (mHandler != null) {
            mHandler.post(this::drainOom);
        } else {
            drainOom();
        }
    }

    private void drainOom() {
        final ApmEvent event;
        synchronized (mQueueLock) {
            event = mPendingOom;
            mPendingOom = null;
            mOomQueued = false;
        }
        if (event != null) {
            handleEvent(event);
        }
    }

    private void handleEvent(ApmEvent event) {
        final ApmConfig config = mConfig.get();
        if (!config.enabled) {
            return;
        }
        if (event.kind == ApmEvent.Kind.OOM_ADJ_COMPLETED) {
            // App ops, settings, and device policy stay off the activity manager lock.
            applyAudioRoles(event.processes);
            refreshVpnSessions(event.processes);
            refreshDeviceOwner();
        }
        final long now = mClock.elapsedRealtime();
        final long graceMs = config.freezeDelayMs;
        synchronized (mLock) {
            switch (event.kind) {
                case PROCESS_STARTED:
                    mTracker.onProcessStarted(event.pid, event.uid, event.userId, event.processName,
                            event.packageName, event.startSeq, event.persistent, now, graceMs);
                    if (event.packageName != null) {
                        mArbiter.setUserForceStop(event.packageName, event.userId, false);
                        mTasks.clearRuntimeForceStop(event.packageName, event.userId);
                        mRevival.noteForceStop(event.packageName, event.userId, false);
                    }
                    considerLocked(event.uid, config, now);
                    break;
                case PROCESS_DIED:
                    mTracker.onProcessDied(event.pid, event.uid, event.userId, event.processName,
                            event.startSeq);
                    considerLocked(event.uid, config, now);
                    break;
                case TOP_RESUMED:
                    final int previous = mTracker.onTopResumed(event.uid, event.userId,
                            event.processName, now, graceMs);
                    if (event.uid >= 0) {
                        considerLocked(event.uid, config, now);
                    }
                    if (previous >= 0) {
                        considerLocked(previous, config, now);
                    }
                    break;
                case OOM_ADJ_COMPLETED:
                    mTracker.onOomAdj(event.processes, now, graceMs);
                    final ArraySet<Integer> uids = new ArraySet<>();
                    for (int i = 0; i < event.processes.size(); i++) {
                        final ProcessSnapshot snap = event.processes.get(i);
                        if (snap != null) {
                            uids.add(snap.uid);
                        }
                    }
                    for (int i = 0; i < uids.size(); i++) {
                        considerLocked(uids.valueAt(i), config, now);
                    }
                    break;
                default:
                    break;
            }
            publishForegroundLocked(config);
        }
    }

    /** Foreground-uid list for {@code /proc/fg_info/fg_uids}. Skipped in shadow mode. */
    private void publishForegroundLocked(ApmConfig config) {
        if (!config.enabled || config.shadowMode) {
            mPublishedFg = null;
            return;
        }
        final int[] uids = mTracker.copyForegroundUids();
        if (mPublishedFg != null && Arrays.equals(mPublishedFg, uids)) {
            return;
        }
        mPublishedFg = uids;
        mKnobs.writeFgUids(uids);
    }

    /**
     * Memory-factor callback from {@code AppProfiler}. Posted onto this service's thread.
     * Does not take the activity manager lock.
     */
    public void noteMemoryPressure(int level) {
        post(() -> handlePressure(level));
    }

    private void handlePressure(int level) {
        synchronized (mLock) {
            final int before = mFrozenUids.size();
            mMemory.onPressureLocked(level, mConfig.get(), mTracker, mClock.elapsedRealtime());
            noteReleased(before);
        }
    }

    private void onMemoryRecheck(int generation) {
        synchronized (mLock) {
            final int before = mFrozenUids.size();
            mMemory.handleRecheckLocked(generation, mConfig.get(), mTracker,
                    mClock.elapsedRealtime());
            noteReleased(before);
        }
    }

    private void considerLocked(int uid, ApmConfig config, long now) {
        final ApmProcessRecord rec = mTracker.get(uid);
        if (rec == null) {
            return;
        }
        final PolicyDecision decision = mPolicy.decide(rec, config, now);
        final PolicyDecision previous = rec.lastDecision;
        rec.lastDecision = decision;
        if (!decision.sameOutcome(previous)) {
            mStats.record(decision);
            if (decision.action != PolicyDecision.Action.NONE
                    && !FreezeController.gatesOpen(config)) {
                Slog.i(TAG, "shadow drop " + decision.summarize());
            }
        }
        final boolean wasFrozen = rec.frozenByApm;
        final String detailBefore = rec.lastFreezeDetail;
        reviewFreezeLocked(rec, config, now);
        if (rec.frozenByApm != wasFrozen) {
            mStats.noteExecuted();
        } else if (PARTIAL_ROLLBACK.equals(rec.lastFreezeDetail)
                && !PARTIAL_ROLLBACK.equals(detailBefore)) {
            mStats.noteExecuted();
        }
    }

    private static final String PARTIAL_ROLLBACK = FreezeController.PARTIAL_FREEZE_ROLLBACK;

    private void reviewFreezeLocked(ApmProcessRecord rec, ApmConfig config, long now) {
        mFreeze.review(rec, config, now);
        armPendingLocked(rec);
        if (!rec.frozenByApm) {
            synchronized (mUnfreezeWait) {
                mUnfreezeWait.notifyAll();
            }
        }
    }

    private void armPendingLocked(ApmProcessRecord rec) {
        if (rec == null || rec.pendingFreezeGen <= 0) {
            return;
        }
        final int uid = rec.uid;
        final int generation = rec.pendingFreezeGen;
        final long delay = rec.pendingFreezeDelayMs;
        rec.pendingFreezeGen = 0;
        final Runnable alarm = () -> {
            synchronized (mLock) {
                if (!mFreeze.noteAlarmFired(uid, generation)) {
                    return;
                }
                final ApmProcessRecord current = mTracker.get(uid);
                if (current == null || !mConfig.get().enabled) {
                    return;
                }
                reviewFreezeLocked(current, mConfig.get(), mClock.elapsedRealtime());
            }
        };
        mFreeze.rememberAlarm(uid, generation, alarm);
        postDelayedExternal(alarm, delay);
    }

    private void onGatesChanged() {
        synchronized (mLock) {
            final int before = mFrozenUids.size();
            final ApmConfig config = mConfig.get();
            mFreeze.onGatesChanged(mTracker, config, mClock.elapsedRealtime());
            mMemory.onGatesChanged(config);
            if (!config.enabled || config.shadowMode) {
                mPublishedFg = null;
            }
            final int released = before - mFrozenUids.size();
            for (int i = 0; i < released; i++) {
                mStats.noteExecuted();
            }
            synchronized (mUnfreezeWait) {
                mUnfreezeWait.notifyAll();
            }
        }
    }

    private void registerConfigListener() {
        registerRoleObservers();
        refreshConfigFromDeviceConfig();
        if (mConfigListenerRegistered) {
            return;
        }
        try {
            DeviceConfig.addOnPropertiesChangedListener(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    runnable -> {
                        if (mHandler != null) {
                            mHandler.post(runnable);
                        }
                    },
                    properties -> refreshConfigFromDeviceConfig());
            mConfigListenerRegistered = true;
        } catch (Throwable t) {
            Slog.w(TAG, "APM config listener not registered", t);
        }
    }

    private void refreshConfigFromDeviceConfig() {
        final ApmConfig previous = mConfig.get();
        try {
            final boolean enabled = readBoolean(ApmConstants.KEY_ENABLED,
                    mShellEnabled != null ? mShellEnabled : previous.enabled);
            final boolean shadow = readBoolean(ApmConstants.KEY_SHADOW_MODE,
                    mShellShadow != null ? mShellShadow : previous.shadowMode);
            final boolean freezer = readBoolean(ApmConstants.KEY_FREEZER_ENABLED,
                    mShellFreezer != null ? mShellFreezer : previous.freezerEnabled);
            final boolean memory = readBoolean(ApmConstants.KEY_MEMORY_ENABLED,
                    previous.memoryEnabled);
            final long freezeDelay = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_FREEZE_DELAY_MS, previous.freezeDelayMs);
            final long bigDelay = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_BIG_APP_FREEZE_DELAY_MS, previous.bigAppFreezeDelayMs);
            final int churnLimit = DeviceConfig.getInt(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_CHURN_LIMIT_60S, previous.churnLimit60s);
            final long cooldown = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_CHURN_COOLDOWN_MS, previous.churnCooldownMs);
            final ApmConfig candidate = new ApmConfig(enabled, shadow, freezer, memory, freezeDelay,
                    bigDelay, churnLimit, cooldown, previous.generation);
            if (!mConfig.tryReplace(candidate)) {
                Slog.w(TAG, "rejected APM config; keeping generation " + previous.generation);
            } else {
                onGatesChanged();
            }
        } catch (Throwable t) {
            Slog.w(TAG, "APM config read failed; keeping generation " + previous.generation, t);
        }
    }

    private static boolean readBoolean(String key, boolean fallback) {
        final String raw = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, key);
        if (raw == null) {
            return fallback;
        }
        return Boolean.parseBoolean(raw);
    }

    /**
     * Post an unfreeze for a uid this controller froze. Does not take the activity manager
     * lock and does not wait. Safe to call while holding that lock.
     */
    public void noteStartUnfreeze(int uid) {
        if (uid < 0) {
            return;
        }
        post(() -> unfreezeForStart(uid, "start"));
    }

    /** Same as {@link #noteStartUnfreeze(int)} for every uid recorded under {@code packageName}. */
    public void noteStartUnfreezePackage(String packageName) {
        if (packageName == null || packageName.length() == 0) {
            return;
        }
        post(() -> unfreezePackageForStart(packageName));
    }

    /**
     * Unfreeze every uid this service froze under {@code packageName} and wait up to
     * {@link ApmConstants#UNFREEZE_WAIT_MS}. Caller must not hold the activity manager lock
     * and must not be the APM thread.
     */
    public void awaitUnfreezePackage(String packageName) {
        if (packageName == null || packageName.length() == 0) {
            return;
        }
        final int[] waiting;
        synchronized (mLock) {
            waiting = frozenUidsForPackageLocked(packageName);
        }
        noteStartUnfreezePackage(packageName);
        if (waiting.length == 0) {
            return;
        }
        final long deadline = SystemClock.uptimeMillis() + ApmConstants.UNFREEZE_WAIT_MS;
        synchronized (mUnfreezeWait) {
            while (anyStillFrozen(waiting)) {
                final long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    mUnfreezeWait.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Unfreeze a uid this controller froze and wait up to {@link ApmConstants#UNFREEZE_WAIT_MS}.
     * Caller must not hold the activity manager lock and must not be the APM thread.
     */
    public void awaitUnfreeze(int uid) {
        if (uid < 0 || !mFrozenUids.contains(uid)) {
            noteStartUnfreeze(uid);
            return;
        }
        noteStartUnfreeze(uid);
        final long deadline = SystemClock.uptimeMillis() + ApmConstants.UNFREEZE_WAIT_MS;
        synchronized (mUnfreezeWait) {
            while (mFrozenUids.contains(uid)) {
                final long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    mUnfreezeWait.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public String shellSetEnabled(boolean enabled) {
        return postAndWait(() -> {
            mShellEnabled = enabled;
            if (!mConfig.tryReplace(mConfig.get().withEnabled(enabled))) {
                return "rejected enabled=" + enabled;
            }
            onGatesChanged();
            return "enabled=" + enabled;
        });
    }

    public String shellSetShadow(boolean shadow) {
        return postAndWait(() -> {
            mShellShadow = shadow;
            if (!mConfig.tryReplace(mConfig.get().withShadowMode(shadow))) {
                return "rejected shadow=" + shadow;
            }
            onGatesChanged();
            return "shadowMode=" + shadow;
        });
    }

    public String shellFreeze(String target, int userId) {
        return postAndWait(() -> shellFreezeBody(target, userId, true /* freeze */));
    }

    public String shellUnfreeze(String target, int userId) {
        return postAndWait(() -> shellFreezeBody(target, userId, false /* freeze */));
    }

    @VisibleForTesting
    public void fireDueAlarmsForTest() {
        final long now = mClock.elapsedRealtime();
        final ArrayList<Runnable> due = new ArrayList<>();
        for (int i = mDueAlarms.size() - 1; i >= 0; i--) {
            final DueAlarm alarm = mDueAlarms.get(i);
            if (alarm.at <= now) {
                due.add(alarm.run);
                mDueAlarms.remove(i);
            }
        }
        for (int i = due.size() - 1; i >= 0; i--) {
            due.get(i).run();
        }
    }

    @VisibleForTesting
    public boolean isFrozenForTest(int uid) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec != null && rec.frozenByApm;
        }
    }

    @VisibleForTesting
    @Nullable String getLastFreezeDetailForTest(int uid) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec == null ? null : rec.lastFreezeDetail;
        }
    }

    @VisibleForTesting
    boolean isFreezeDisabledForTest(int uid) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec != null && rec.freezeDisabled;
        }
    }

    private void unfreezeForStart(int uid, String reason) {
        synchronized (mLock) {
            final int before = mFrozenUids.size();
            mFreeze.unfreezeIfOurs(mTracker, mConfig.get(), uid, mClock.elapsedRealtime(), reason);
            noteReleased(before);
        }
    }

    private void unfreezePackageForStart(String packageName) {
        synchronized (mLock) {
            final int before = mFrozenUids.size();
            mFreeze.unfreezePackage(mTracker, mConfig.get(), packageName,
                    mClock.elapsedRealtime(), "activity");
            noteReleased(before);
        }
    }

    private int[] frozenUidsForPackageLocked(String packageName) {
        int count = 0;
        for (int i = 0; i < mTracker.size(); i++) {
            final ApmProcessRecord rec = mTracker.valueAt(i);
            if (rec.frozenByApm && rec.matchesName(packageName)) {
                count++;
            }
        }
        final int[] out = new int[count];
        int write = 0;
        for (int i = 0; i < mTracker.size(); i++) {
            final ApmProcessRecord rec = mTracker.valueAt(i);
            if (rec.frozenByApm && rec.matchesName(packageName)) {
                out[write++] = rec.uid;
            }
        }
        return out;
    }

    private boolean anyStillFrozen(int[] uids) {
        for (int i = 0; i < uids.length; i++) {
            if (mFrozenUids.contains(uids[i])) {
                return true;
            }
        }
        return false;
    }

    private void noteReleased(int frozenBefore) {
        final int released = frozenBefore - mFrozenUids.size();
        for (int i = 0; i < released; i++) {
            mStats.noteExecuted();
        }
        if (released > 0) {
            synchronized (mUnfreezeWait) {
                mUnfreezeWait.notifyAll();
            }
        }
    }

    private String shellFreezeBody(String target, int userId, boolean freeze) {
        synchronized (mLock) {
            final ApmConfig config = mConfig.get();
            final long now = mClock.elapsedRealtime();
            final int before = mFrozenUids.size();
            final String result = freeze
                    ? mFreeze.shellFreeze(mTracker, config, target, userId, now)
                    : mFreeze.shellUnfreeze(mTracker, config, target, userId, now);
            if (mFrozenUids.size() != before) {
                mStats.noteExecuted();
            } else if (freeze && FreezeController.PARTIAL_FREEZE_ROLLBACK.equals(result)) {
                mStats.noteExecuted();
            }
            return result;
        }
    }

    private String postAndWait(java.util.function.Supplier<String> body) {
        if (mHandler == null) {
            return body.get();
        }
        final ArrayBlockingQueue<String> done = new ArrayBlockingQueue<>(1);
        mHandler.post(() -> done.offer(body.get()));
        try {
            final String result = done.poll(SHELL_WAIT_MS, TimeUnit.MILLISECONDS);
            return result != null ? result : "apm: posted, handler busy";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "apm: interrupted";
        }
    }

    private void postDelayedExternal(Runnable runnable, long delayMs) {
        if (mHandler != null) {
            mHandler.postDelayed(runnable, delayMs);
            return;
        }
        mDueAlarms.add(new DueAlarm(mClock.elapsedRealtime() + delayMs, runnable));
    }

    private void removeExternal(Runnable runnable) {
        if (mHandler != null) {
            mHandler.removeCallbacks(runnable);
        }
        for (int i = mDueAlarms.size() - 1; i >= 0; i--) {
            if (mDueAlarms.get(i).run == runnable) {
                mDueAlarms.remove(i);
            }
        }
    }

    private static final class DueAlarm {
        final long at;
        final Runnable run;

        DueAlarm(long at, Runnable run) {
            this.at = at;
            this.run = run;
        }
    }
}
