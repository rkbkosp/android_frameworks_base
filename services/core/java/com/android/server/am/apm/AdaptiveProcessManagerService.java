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
import com.android.server.am.apm.ApmConstants.ManagedState;
import com.android.server.am.apm.ApmEvent.ProcessSnapshot;

import java.io.PrintWriter;
import java.util.List;

/**
 * Shadow adaptive process manager.
 *
 * <p>AMS remains the source of truth. Hooks copy pid, uid, name, and adj facts, then this
 * service scores them on its own thread. Every FREEZE, KILL, and DEFER decision is logged
 * and dropped. This class has no freezer, killer, or defer executor.
 *
 * <p>The master switch defaults off. While it is off, hooks do not copy further snapshots
 * and action logging stops. Nothing was frozen, so turning it off does not unfreeze anyone.
 * Shadow mode defaults on for when the switch is enabled.
 */
public final class AdaptiveProcessManagerService {
    private static final String TAG = "Apm";

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
    private final Object mLock = new Object();
    private final Object mQueueLock = new Object();

    @Nullable private final Handler mHandler;
    @Nullable private ApmEvent mPendingOom;
    private boolean mOomQueued;
    private boolean mSystemReady;
    private boolean mConfigListenerRegistered;

    public AdaptiveProcessManagerService() {
        this(Clock.SYSTEM, true /* startThread */);
    }

    @VisibleForTesting
    public AdaptiveProcessManagerService(Clock clock, boolean startThread) {
        mClock = clock != null ? clock : Clock.SYSTEM;
        if (startThread) {
            final ServiceThread thread = new ServiceThread("apm",
                    Process.THREAD_PRIORITY_BACKGROUND, false /* allowIo */);
            thread.start();
            mHandler = new Handler(thread.getLooper());
        } else {
            mHandler = null;
        }
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
        }
    }

    public void explain(PrintWriter pw, String target) {
        synchronized (mLock) {
            ApmShellCommand.explain(pw, mConfig.get(), mTracker, target);
        }
    }

    @VisibleForTesting
    public void setEnabledForTest(boolean enabled) {
        if (!mConfig.tryReplace(mConfig.get().withEnabled(enabled))) {
            throw new IllegalStateException("rejected enabled=" + enabled);
        }
    }

    @VisibleForTesting
    public void setShadowModeForTest(boolean shadowMode) {
        if (!mConfig.tryReplace(mConfig.get().withShadowMode(shadowMode))) {
            throw new IllegalStateException("rejected shadowMode=" + shadowMode);
        }
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

    private void post(ApmEvent event) {
        if (mHandler != null) {
            mHandler.post(() -> handleEvent(event));
        } else {
            handleEvent(event);
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
        }
    }

    private void considerLocked(int uid, ApmConfig config, long now) {
        final ApmProcessRecord rec = mTracker.get(uid);
        if (rec == null) {
            return;
        }
        final PolicyDecision decision = mPolicy.decide(rec, config, now);
        // Dropped on purpose. Do not call a freezer, killer, or defer executor from here.
        final PolicyDecision previous = rec.lastDecision;
        rec.lastDecision = decision;
        if (decision.sameOutcome(previous)) {
            return;
        }
        mStats.record(decision);
        if (decision.action != PolicyDecision.Action.NONE) {
            Slog.i(TAG, "shadow drop " + decision.summarize());
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
            final boolean enabled = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_ENABLED, previous.enabled);
            final boolean shadow = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_SHADOW_MODE, previous.shadowMode);
            final long freezeDelay = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_FREEZE_DELAY_MS, previous.freezeDelayMs);
            final long bigDelay = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_BIG_APP_FREEZE_DELAY_MS, previous.bigAppFreezeDelayMs);
            final int churnLimit = DeviceConfig.getInt(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_CHURN_LIMIT_60S, previous.churnLimit60s);
            final long cooldown = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_CHURN_COOLDOWN_MS, previous.churnCooldownMs);
            final ApmConfig candidate = new ApmConfig(enabled, shadow, freezeDelay, bigDelay,
                    churnLimit, cooldown, previous.generation);
            if (!mConfig.tryReplace(candidate)) {
                Slog.w(TAG, "rejected APM config; keeping generation " + previous.generation);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "APM config read failed; keeping generation " + previous.generation, t);
        }
    }
}
