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
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;
import com.android.server.am.ProcessList;
import com.android.server.am.apm.ApmConstants.ManagedState;
import com.android.server.am.apm.ApmEvent.ProcessSnapshot;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
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
    /** Armed for the next oom-adj trim. Athena LMK adj 300. Not set in shadow mode. */
    private volatile String mArmedAdjScene;
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
    @Nullable private ApmEvent mPendingOom;
    private boolean mOomQueued;
    private boolean mSystemReady;
    private boolean mConfigListenerRegistered;
    @Nullable private Boolean mShellEnabled;
    @Nullable private Boolean mShellShadow;
    @Nullable private Boolean mShellFreezer;
    /** Last foreground list written, or null if gates were closed and the next open must write. */
    @Nullable private int[] mPublishedFg;

    public AdaptiveProcessManagerService() {
        this(Clock.SYSTEM, true /* startThread */, null /* executor */, null /* knobs */,
                null /* pressure */);
    }

    public AdaptiveProcessManagerService(@Nullable ApmExecutor executor) {
        this(Clock.SYSTEM, true /* startThread */, executor, null /* knobs */, null /* pressure */);
    }

    public AdaptiveProcessManagerService(@Nullable ApmExecutor executor,
            @Nullable ApmPressure pressure) {
        this(Clock.SYSTEM, true /* startThread */, executor, null /* knobs */, pressure);
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
                null /* pressure */);
    }

    @VisibleForTesting
    AdaptiveProcessManagerService(Clock clock, boolean startThread,
            @Nullable ApmExecutor executor, @Nullable KernelKnobWriter knobs,
            @Nullable ApmPressure pressure) {
        mClock = clock != null ? clock : Clock.SYSTEM;
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
        mFreeze = new FreezeController(mExecutor, mScheduler, mFrozenUids, mArbiter);
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
        if (!shadow && "athena_lmk".equals(scene.name)) {
            mArmedAdjScene = scene.name;
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

    private static ClearSceneRunner.Facts factsFor(ApmProcessRecord rec) {
        final ClearSceneRunner.Facts facts = new ClearSceneRunner.Facts();
        facts.foreground = rec.foreground || rec.visible;
        facts.foregroundService = rec.foregroundService;
        facts.home = rec.home;
        facts.persistent = rec.persistent;
        facts.visibleWindow = rec.visible;
        facts.curAdj = rec.minAdj;
        facts.perceptible = rec.minAdj <= ProcessList.PERCEPTIBLE_APP_ADJ;
        facts.system = rec.systemUid;
        return facts;
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
        final long now = mClock.elapsedRealtime();
        final long graceMs = config.freezeDelayMs;
        synchronized (mLock) {
            switch (event.kind) {
                case PROCESS_STARTED:
                    mTracker.onProcessStarted(event.pid, event.uid, event.userId, event.processName,
                            event.packageName, event.startSeq, event.persistent, now, graceMs);
                    if (event.packageName != null) {
                        mArbiter.setUserForceStop(event.packageName, event.userId, false);
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
