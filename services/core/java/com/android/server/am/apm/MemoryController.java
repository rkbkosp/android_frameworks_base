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

import android.os.Process;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import com.android.server.am.ProcessList;
import com.android.server.am.apm.ApmConstants.ManagedState;
import com.android.server.am.apm.ApmProcessRecord.PidSlot;

import java.util.ArrayList;

/**
 * Compact or kill cached uids when the activity manager reports memory pressure.
 *
 * <p>Caller holds the adaptive process manager lock and is on its handler thread.
 * Normal pressure leaves the freezer in charge. Moderate and low compact. Critical
 * kills one cached uid, waits, then reads pressure again. The emergency batch stops
 * at {@link ApmConstants#EMERGENCY_KILL_BATCH} or as soon as pressure drops.
 * Shadow mode logs and does not compact, kill, or write swappiness.
 *
 * <p>The compaction, kill and kernel writes are handed to {@link OffLockPlatform}, so they
 * run once that lock is released: the caller only decides and snapshots. The counters and
 * the logs stay per sweep.
 */
final class MemoryController {
    private static final String TAG = "Apm";

    /** Posted outside the service lock by {@link FreezeController.Scheduler}. */
    interface Recheck {
        void run(int generation);
    }

    private final ApmExecutor mExecutor;
    private final FreezeController mFreeze;
    private final FreezeController.Scheduler mScheduler;
    private final OffLockPlatform mPlatform;
    private final KernelKnobWriter mKnobs;
    private final ApmPressure mPressure;
    private final ApmStats mStats;
    private final Recheck mRecheck;
    private final ProtectionArbiter mArbiter;
    private final ArraySet<Integer> mKilledThisBatch = new ArraySet<>();

    private Runnable mRecheckRunnable;
    private int mRecheckGen;
    private int mLevel = -1;
    private int mBatch;
    private boolean mCompactedForSpike;
    private int mLastSwappiness = -1;

    MemoryController(ApmExecutor executor, FreezeController freeze,
            FreezeController.Scheduler scheduler, OffLockPlatform platform,
            KernelKnobWriter knobs, ApmPressure pressure, ApmStats stats, Recheck recheck,
            ProtectionArbiter arbiter) {
        mExecutor = executor;
        mFreeze = freeze;
        mScheduler = scheduler;
        mPlatform = platform;
        mKnobs = knobs;
        mPressure = pressure;
        mStats = stats;
        mRecheck = recheck;
        mArbiter = arbiter;
    }

    static boolean gatesOpen(ApmConfig config) {
        return config.enabled && config.memoryEnabled && !config.shadowMode;
    }

    void onPressureLocked(int level, ApmConfig config, ProcessStateTracker tracker, long now) {
        if (config.shadowMode) {
            if (level >= ApmConstants.PRESSURE_MODERATE) {
                Slog.i(TAG, "shadow drop memory level=" + level);
            }
            stopBatch();
            return;
        }
        if (!gatesOpen(config)) {
            stopBatch();
            return;
        }
        final int previous = mLevel;
        mLevel = level;
        writeSwappiness(level);
        if (level < ApmConstants.PRESSURE_MODERATE) {
            stopBatch();
            mCompactedForSpike = false;
            return;
        }
        if (level < ApmConstants.PRESSURE_CRITICAL) {
            stopBatch();
            if (!mCompactedForSpike || previous < ApmConstants.PRESSURE_MODERATE) {
                compactCached(tracker);
                mCompactedForSpike = true;
            }
            return;
        }
        mCompactedForSpike = false;
        if (mBatch == 0 && mRecheckRunnable == null) {
            killOneLocked(config, tracker, now);
        }
    }

    void handleRecheckLocked(int generation, ApmConfig config, ProcessStateTracker tracker,
            long now) {
        if (generation != mRecheckGen) {
            return;
        }
        mRecheckRunnable = null;
        if (!gatesOpen(config)) {
            stopBatch();
            return;
        }
        final int live = readPressure();
        mLevel = live;
        writeSwappiness(live);
        if (live < ApmConstants.PRESSURE_CRITICAL) {
            mBatch = 0;
            mKilledThisBatch.clear();
            if (live >= ApmConstants.PRESSURE_MODERATE && !mCompactedForSpike) {
                compactCached(tracker);
                mCompactedForSpike = true;
            }
            if (live < ApmConstants.PRESSURE_MODERATE) {
                mCompactedForSpike = false;
            }
            return;
        }
        killOneLocked(config, tracker, now);
    }

    /** Master switch, memory flag, or shadow changed. Does not unfreeze; the freezer does. */
    void onGatesChanged(ApmConfig config) {
        if (gatesOpen(config)) {
            return;
        }
        stopBatch();
    }

    private void killOneLocked(ApmConfig config, ProcessStateTracker tracker, long now) {
        if (mBatch >= ApmConstants.EMERGENCY_KILL_BATCH) {
            return;
        }
        final int uid = selectKillUid(tracker, now);
        if (uid < 0) {
            return;
        }
        mKilledThisBatch.add(uid);
        mBatch++;
        final ApmProcessRecord rec = tracker.get(uid);
        if (rec != null && rec.frozenByApm) {
            // The unfreeze is queued first, so it runs before the kill below.
            mFreeze.unfreezeIfOurs(tracker, config, uid, now, "kill");
        }
        final int[] pids = livePids(rec);
        final String reason = ApmConstants.KILL_REASON_PREFIX + uid;
        mPlatform.run(() -> {
            boolean killed = false;
            if (mExecutor != null) {
                try {
                    killed = mExecutor.killCachedUid(uid, pids, reason);
                } catch (RuntimeException e) {
                    Slog.w(TAG, "kill failed uid=" + uid, e);
                }
            }
            if (killed) {
                mStats.noteExecuted();
                Slog.i(TAG, "killed cached uid=" + uid + " reason=" + reason);
            }
        });
        if (mBatch < ApmConstants.EMERGENCY_KILL_BATCH) {
            scheduleRecheck();
        }
    }

    private void compactCached(ProcessStateTracker tracker) {
        if (mExecutor == null) {
            return;
        }
        final int top = tracker.getTopUid();
        final ArrayList<CompactVictim> victims = new ArrayList<>();
        for (int i = 0; i < tracker.size(); i++) {
            final ApmProcessRecord rec = tracker.valueAt(i);
            if (!reclaimEligible(rec, top)) {
                continue;
            }
            victims.add(new CompactVictim(rec.uid, livePids(rec)));
        }
        if (victims.isEmpty()) {
            return;
        }
        // One queued sweep: the compactions run without the service lock, and the counter and
        // the log stay one per sweep, exactly as they were when this ran under the lock.
        mPlatform.run(() -> {
            int queued = 0;
            for (int i = 0; i < victims.size(); i++) {
                final CompactVictim victim = victims.get(i);
                try {
                    queued += mExecutor.compactUid(victim.uid, victim.pids);
                } catch (RuntimeException e) {
                    Slog.w(TAG, "compact failed uid=" + victim.uid, e);
                }
            }
            if (queued > 0) {
                mStats.noteExecuted();
                Slog.i(TAG, "compacted cached processes count=" + queued);
            }
        });
    }

    /** One queued compaction: a uid that was eligible and its pids, read under the lock. */
    private static final class CompactVictim {
        final int uid;
        final int[] pids;

        CompactVictim(int uid, int[] pids) {
            this.uid = uid;
            this.pids = pids;
        }
    }

    private void scheduleRecheck() {
        cancelRecheck();
        final int generation = ++mRecheckGen;
        final Runnable runnable = () -> mRecheck.run(generation);
        mRecheckRunnable = runnable;
        mScheduler.postDelayed(runnable, ApmConstants.KILL_RECHECK_MS);
    }

    private void stopBatch() {
        cancelRecheck();
        mBatch = 0;
        mKilledThisBatch.clear();
    }

    private void cancelRecheck() {
        mRecheckGen++;
        final Runnable previous = mRecheckRunnable;
        mRecheckRunnable = null;
        if (previous != null) {
            mScheduler.remove(previous);
        }
    }

    private int readPressure() {
        if (mPressure != null) {
            final int live = mPressure.currentLevel();
            if (live >= 0) {
                return live;
            }
        }
        return mLevel < 0 ? ApmConstants.PRESSURE_NORMAL : mLevel;
    }

    private void writeSwappiness(int level) {
        final int value = ApmConstants.swappinessForPressure(level);
        if (value == mLastSwappiness || mKnobs == null) {
            return;
        }
        mLastSwappiness = value;
        mPlatform.run(() -> mKnobs.writeSwappiness(value));
    }

    /**
     * Highest of rss, cached age, and freeze churn. A missing counter contributes 0.
     * Ties break toward the lower uid.
     */
    private int selectKillUid(ProcessStateTracker tracker, long now) {
        int bestUid = -1;
        long bestScore = -1L;
        final int top = tracker.getTopUid();
        for (int i = 0; i < tracker.size(); i++) {
            final ApmProcessRecord rec = tracker.valueAt(i);
            if (mKilledThisBatch.contains(rec.uid) || !reclaimEligible(rec, top)) {
                continue;
            }
            // denyKill spares a cached kill. User force-stop still wins, so a force-stopped
            // uid is not spared even when a lower layer set the ban.
            if (sparedByPolicy(rec, now)) {
                continue;
            }
            final long score = killScore(rec, now) - protectionScore(rec, now);
            if (bestUid < 0 || score > bestScore || (score == bestScore && rec.uid < bestUid)) {
                bestScore = score;
                bestUid = rec.uid;
            }
        }
        return bestUid;
    }

    private boolean sparedByPolicy(ApmProcessRecord rec, long now) {
        if (mArbiter == null || rec.forceStopped) {
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

    private int protectionScore(ApmProcessRecord rec, long now) {
        if (mArbiter == null) {
            return 0;
        }
        int score = 0;
        if (rec.packages.size() == 0) {
            return mArbiter.protectionScore(rec.primaryPackage(), rec.userId, now);
        }
        for (int i = 0; i < rec.packages.size(); i++) {
            final int one = mArbiter.protectionScore(rec.packages.valueAt(i), rec.userId, now);
            if (one > score) {
                score = one;
            }
        }
        return score;
    }

    static long killScore(ApmProcessRecord rec, long now) {
        final long rss = Math.max(0L, rec.rssKb);
        long ageSec = 0L;
        if (rec.cachedSinceElapsed > 0 && now >= rec.cachedSinceElapsed) {
            ageSec = (now - rec.cachedSinceElapsed) / 1000L;
        }
        final long churn = rec.unfreezeTimes.size();
        return rss + ageSec + churn * 10_000L;
    }

    /**
     * Cached uid that is not the top app, not visible, not a foreground service,
     * not persistent, and not a system or core uid. One ineligible process rejects the uid.
     */
    static boolean reclaimEligible(ApmProcessRecord rec, int topUid) {
        if (rec == null || rec.uid == topUid) {
            return false;
        }
        if (rec.systemUid || rec.persistent || rec.home) {
            return false;
        }
        if (UserHandle.getAppId(rec.uid) < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        if (rec.visible || rec.foreground || rec.foregroundService) {
            return false;
        }
        if (rec.state != ManagedState.CACHED) {
            return false;
        }
        if (rec.minAdj < ProcessList.CACHED_APP_MIN_ADJ
                || rec.minAdj > ProcessList.CACHED_APP_MAX_ADJ) {
            return false;
        }
        if (rec.pids.size() == 0) {
            return false;
        }
        for (int i = 0; i < rec.pids.size(); i++) {
            if (!pidEligible(rec.pids.valueAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean pidEligible(PidSlot slot) {
        if (slot.pid <= 0 || slot.persistent || slot.visible || slot.foregroundActivities
                || slot.foregroundService) {
            return false;
        }
        if (slot.curAdj < ProcessList.CACHED_APP_MIN_ADJ
                || slot.curAdj > ProcessList.CACHED_APP_MAX_ADJ) {
            return false;
        }
        return true;
    }

    private static int[] livePids(ApmProcessRecord rec) {
        if (rec == null || rec.pids.size() == 0) {
            return new int[0];
        }
        int n = 0;
        for (int i = 0; i < rec.pids.size(); i++) {
            if (rec.pids.valueAt(i).pid > 0) {
                n++;
            }
        }
        final int[] out = new int[n];
        int w = 0;
        for (int i = 0; i < rec.pids.size(); i++) {
            final int pid = rec.pids.valueAt(i).pid;
            if (pid > 0) {
                out[w++] = pid;
            }
        }
        return out;
    }
}
