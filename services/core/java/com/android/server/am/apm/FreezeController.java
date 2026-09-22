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

import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;

import android.os.Process;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.server.am.ProcessList;
import com.android.server.am.apm.ApmProcessRecord.PidSlot;

import java.util.ArrayList;
import java.util.Set;

/**
 * Schedules cached-uid freezes and rolls back a partial freeze.
 *
 * <p>Caller holds the adaptive process manager lock. The debounce is a handler alarm,
 * not the next oom adj pass. The executor is invoked only when the master switch, the
 * freezer flag, and shadow mode all allow it. Otherwise the decision stays on the record
 * and nothing is frozen.
 */
final class FreezeController {
    static final String PARTIAL_FREEZE_ROLLBACK = "PARTIAL_FREEZE_ROLLBACK";
    static final int MAX_CONSECUTIVE_FAILURES = 3;
    static final long UNFREEZE_WINDOW_MS = 60_000L;

    /** Posted outside the service lock. {@link #remove} must drop a previously posted runnable. */
    interface Scheduler {
        void postDelayed(Runnable runnable, long delayMs);

        void remove(Runnable runnable);
    }

    private final ApmExecutor mExecutor;
    private final Scheduler mScheduler;
    private final Set<Integer> mFrozenUids;
    private final ProtectionArbiter mArbiter;
    /** uid -> runnable currently posted for the debounce alarm. */
    private final ArrayMap<Integer, Runnable> mAlarms = new ArrayMap<>();
    private final ArrayMap<Integer, Integer> mAlarmGen = new ArrayMap<>();

    FreezeController(ApmExecutor executor, Scheduler scheduler, Set<Integer> frozenUids,
            ProtectionArbiter arbiter) {
        mExecutor = executor;
        mScheduler = scheduler;
        mFrozenUids = frozenUids;
        mArbiter = arbiter;
    }

    /**
     * Re-evaluate one uid. May freeze, request a debounce alarm, or unfreeze.
     * When this returns with {@code rec.pendingFreezeGen > 0}, the caller posts that alarm.
     */
    void review(ApmProcessRecord rec, ApmConfig config, long now) {
        if (rec == null) {
            return;
        }
        rec.pendingFreezeGen = 0;
        if (!gatesOpen(config)) {
            cancelAlarm(rec.uid);
            if (rec.frozenByApm) {
                unfreeze(rec, config, now, "gates-closed");
            }
            return;
        }
        final String block = hardBlock(rec, now, config);
        if (block != null) {
            cancelAlarm(rec.uid);
            rec.lastFreezeDetail = block;
            if (rec.frozenByApm) {
                unfreeze(rec, config, now, block);
            }
            return;
        }
        final long readyAt = readyAt(rec, config) + freezeDelayExtra(rec, now);
        if (now < readyAt) {
            requestAlarm(rec, readyAt, now);
            rec.lastFreezeDetail = "debounce-until=" + readyAt;
            return;
        }
        cancelAlarm(rec.uid);
        freezeNow(rec, config, now, false /* shell */);
    }

    /**
     * Alarm body. Caller holds the service lock. Returns false for a stale alarm.
     * Does not review; the caller reviews after this returns so a new alarm can be posted.
     */
    boolean noteAlarmFired(int uid, int generation) {
        if (!alarmMatches(uid, generation)) {
            return false;
        }
        mAlarmGen.remove(uid);
        mAlarms.remove(uid);
        return true;
    }

    /** Top or another start path. Idempotent if this controller did not freeze the uid. */
    void unfreezeIfOurs(ProcessStateTracker tracker, ApmConfig config, int uid, long now,
            String reason) {
        final ApmProcessRecord rec = tracker.get(uid);
        if (rec == null || !rec.frozenByApm) {
            return;
        }
        cancelAlarm(uid);
        unfreeze(rec, config, now, reason);
    }

    void unfreezePackage(ProcessStateTracker tracker, ApmConfig config, String packageName,
            long now, String reason) {
        if (packageName == null) {
            return;
        }
        for (int i = 0; i < tracker.size(); i++) {
            final ApmProcessRecord rec = tracker.valueAt(i);
            if (rec.frozenByApm && rec.matchesName(packageName)) {
                cancelAlarm(rec.uid);
                unfreeze(rec, config, now, reason);
            }
        }
    }

    /** Master switch, freezer flag, or shadow changed. Drop every uid this controller froze. */
    void onGatesChanged(ProcessStateTracker tracker, ApmConfig config, long now) {
        if (gatesOpen(config)) {
            return;
        }
        for (int i = tracker.size() - 1; i >= 0; i--) {
            final ApmProcessRecord rec = tracker.valueAt(i);
            cancelAlarm(rec.uid);
            if (rec.frozenByApm) {
                unfreeze(rec, config, now, "gates-closed");
            }
        }
    }

    String shellFreeze(ProcessStateTracker tracker, ApmConfig config, String target, int userId,
            long now) {
        if (config.shadowMode) {
            return "shadow mode: freeze not applied";
        }
        if (!config.enabled || !config.freezerEnabled) {
            return "freezer not active: freeze not applied";
        }
        final ApmProcessRecord rec = find(tracker, target, userId);
        if (rec == null) {
            return "no uid record for " + target;
        }
        final String block = hardBlock(rec, now, config);
        if (block != null) {
            rec.lastFreezeDetail = block;
            return "not frozen: " + block;
        }
        cancelAlarm(rec.uid);
        return freezeNow(rec, config, now, true /* shell */);
    }

    String shellUnfreeze(ProcessStateTracker tracker, ApmConfig config, String target, int userId,
            long now) {
        if (config.shadowMode) {
            return "shadow mode: unfreeze not applied";
        }
        final ApmProcessRecord rec = find(tracker, target, userId);
        if (rec == null) {
            return "no uid record for " + target;
        }
        if (!rec.frozenByApm) {
            return "not frozen by apm";
        }
        cancelAlarm(rec.uid);
        unfreeze(rec, config, now, "shell");
        return "unfrozen uid=" + rec.uid;
    }

    private String freezeNow(ApmProcessRecord rec, ApmConfig config, long now, boolean shell) {
        if (mExecutor == null) {
            rec.lastFreezeDetail = "no-executor";
            return "no-executor";
        }
        final int[] pids = livePids(rec);
        if (pids.length == 0) {
            rec.lastFreezeDetail = "no-live-pid";
            return "no-live-pid";
        }
        final ApmFreezeResult result;
        try {
            result = mExecutor.freezeUid(rec.uid, pids);
        } catch (RuntimeException e) {
            rec.lastFreezeDetail = "freeze-threw";
            noteFailure(rec);
            Slog.w("Apm", "freeze failed uid=" + rec.uid, e);
            return rec.lastFreezeDetail;
        }
        if (result == null) {
            rec.lastFreezeDetail = "freeze-null";
            noteFailure(rec);
            return rec.lastFreezeDetail;
        }
        if (result.partial()) {
            mExecutor.unfreezeUid(rec.uid, result.frozenPids);
            rec.frozenByApm = false;
            mFrozenUids.remove(rec.uid);
            rec.lastFreezeDetail = PARTIAL_FREEZE_ROLLBACK;
            noteFailure(rec);
            Slog.w("Apm", PARTIAL_FREEZE_ROLLBACK + " uid=" + rec.uid);
            return PARTIAL_FREEZE_ROLLBACK;
        }
        if (!result.committed()) {
            rec.lastFreezeDetail = "freeze-failed";
            noteFailure(rec);
            return rec.lastFreezeDetail;
        }
        rec.consecutiveFreezeFailures = 0;
        rec.frozenByApm = true;
        mFrozenUids.add(rec.uid);
        rec.lastFreezeElapsed = now;
        rec.lastFreezeDetail = shell ? "shell-frozen" : "frozen";
        return rec.lastFreezeDetail;
    }

    private void unfreeze(ApmProcessRecord rec, ApmConfig config, long now, String reason) {
        final int[] pids = livePids(rec);
        if (mExecutor != null && pids.length > 0) {
            mExecutor.unfreezeUid(rec.uid, pids);
        }
        final boolean wasFrozen = rec.frozenByApm;
        rec.frozenByApm = false;
        mFrozenUids.remove(rec.uid);
        rec.lastFreezeDetail = "unfrozen:" + reason;
        if (!wasFrozen) {
            return;
        }
        rec.lastUnfreezeElapsed = now;
        noteUnfreeze(rec, config, now);
    }

    private void noteUnfreeze(ApmProcessRecord rec, ApmConfig config, long now) {
        rec.unfreezeTimes.add(now);
        prune(rec.unfreezeTimes, now - UNFREEZE_WINDOW_MS);
        if (rec.unfreezeTimes.size() > config.churnLimit60s) {
            rec.freezeCooldownUntil = now + config.churnCooldownMs;
            rec.lastFreezeDetail = "churn-cooldown-until=" + rec.freezeCooldownUntil;
        }
    }

    private void noteFailure(ApmProcessRecord rec) {
        rec.consecutiveFreezeFailures++;
        if (rec.consecutiveFreezeFailures >= MAX_CONSECUTIVE_FAILURES) {
            rec.freezeDisabled = true;
            rec.lastFreezeDetail = rec.lastFreezeDetail + ";freeze-disabled-until-reboot";
        }
    }

    private void requestAlarm(ApmProcessRecord rec, long readyAt, long now) {
        final Integer already = mAlarmGen.get(rec.uid);
        if (already != null && rec.pendingFreezeAt == readyAt && mAlarms.get(rec.uid) != null) {
            // Same deadline is already posted. pendingFreezeGen stays 0 so the caller
            // does not post a second alarm.
            return;
        }
        final int next = rec.freezeScheduleGen + 1;
        rec.freezeScheduleGen = next;
        cancelAlarm(rec.uid);
        mAlarmGen.put(rec.uid, next);
        rec.pendingFreezeAt = readyAt;
        rec.pendingFreezeGen = next;
        rec.pendingFreezeDelayMs = Math.max(0L, readyAt - now);
    }

    /** Bind the runnable that the service actually posted for {@code generation}. */
    void rememberAlarm(int uid, int generation, Runnable runnable) {
        if (!alarmMatches(uid, generation)) {
            mScheduler.remove(runnable);
            return;
        }
        final Runnable previous = mAlarms.put(uid, runnable);
        if (previous != null && previous != runnable) {
            mScheduler.remove(previous);
        }
    }

    void cancelAlarm(int uid) {
        final Runnable previous = mAlarms.remove(uid);
        mAlarmGen.remove(uid);
        if (previous != null) {
            mScheduler.remove(previous);
        }
    }

    boolean alarmMatches(int uid, int generation) {
        final Integer armed = mAlarmGen.get(uid);
        return armed != null && armed == generation;
    }

    void noteAlarmFired(int uid) {
        mAlarmGen.remove(uid);
        mAlarms.remove(uid);
    }

    static boolean gatesOpen(ApmConfig config) {
        return config.enabled && config.freezerEnabled && !config.shadowMode;
    }

    /**
     * @return a hard-block reason, or null if every process in the uid may freeze
     */
    String hardBlock(ApmProcessRecord rec, long now, ApmConfig config) {
        if (rec.freezeDisabled) {
            return "freeze-disabled-until-reboot";
        }
        if (now < rec.freezeCooldownUntil) {
            return "churn-cooldown";
        }
        if (UserHandle.getAppId(rec.uid) < Process.FIRST_APPLICATION_UID || rec.systemUid) {
            return "core-app-id";
        }
        if (rec.persistent) {
            return "persistent";
        }
        if (rec.home) {
            return "home-role";
        }
        if (rec.visible || rec.foreground) {
            return "visible-or-top";
        }
        if (rec.foregroundService) {
            return "foreground-service";
        }
        if (rec.minAdj <= ProcessList.PERCEPTIBLE_APP_ADJ) {
            return "perceptible";
        }
        if (deniesFreeze(rec, now)) {
            return "deny-freeze";
        }
        if (rec.pids.size() == 0) {
            return "no-process";
        }
        for (int i = 0; i < rec.pids.size(); i++) {
            final String one = hardBlockPid(rec.pids.valueAt(i));
            if (one != null) {
                return one;
            }
        }
        return null;
    }

    private static String hardBlockPid(PidSlot slot) {
        if (slot.pid <= 0) {
            return "pid-not-live";
        }
        if (slot.persistent) {
            return "persistent-process";
        }
        if (slot.visible || slot.foregroundActivities) {
            return "visible-or-top-process";
        }
        if (slot.foregroundService) {
            return "foreground-service-process";
        }
        if (slot.curAdj <= ProcessList.PERCEPTIBLE_APP_ADJ
                || slot.curAdj < ProcessList.CACHED_APP_MIN_ADJ
                || slot.curAdj > ProcessList.CACHED_APP_MAX_ADJ) {
            return "adj-not-cached";
        }
        if (slot.curProcState < PROCESS_STATE_CACHED_ACTIVITY
                || slot.curProcState > PROCESS_STATE_CACHED_EMPTY) {
            return "proc-state-not-cached";
        }
        return null;
    }

    /**
     * {@code denyFreeze} from the arbiter. A lower source cannot clear a higher ban, so any
     * merged ban blocks the freeze. User lock is not this check; it only adds debounce.
     */
    private boolean deniesFreeze(ApmProcessRecord rec, long now) {
        if (mArbiter == null) {
            return false;
        }
        if (rec.packages.size() == 0) {
            return mArbiter.deniesFreeze(rec.primaryPackage(), rec.userId, now);
        }
        for (int i = 0; i < rec.packages.size(); i++) {
            if (mArbiter.deniesFreeze(rec.packages.valueAt(i), rec.userId, now)) {
                return true;
            }
        }
        return false;
    }

    private long freezeDelayExtra(ApmProcessRecord rec, long now) {
        if (mArbiter == null) {
            return 0L;
        }
        long extra = 0L;
        if (rec.packages.size() == 0) {
            return mArbiter.freezeDelayExtraMs(rec.primaryPackage(), rec.userId, now);
        }
        for (int i = 0; i < rec.packages.size(); i++) {
            final long one = mArbiter.freezeDelayExtraMs(rec.packages.valueAt(i), rec.userId, now);
            if (one > extra) {
                extra = one;
            }
        }
        return extra;
    }

    static long readyAt(ApmProcessRecord rec, ApmConfig config) {
        final long delay = selectedDelay(rec, config);
        final long started = rec.graceStartedElapsed > 0 ? rec.graceStartedElapsed : 0L;
        return started + delay;
    }

    static long selectedDelay(ApmProcessRecord rec, ApmConfig config) {
        if (rec.rssKb >= ApmConstants.BIG_APP_RSS_KB || rec.home) {
            return config.bigAppFreezeDelayMs;
        }
        return config.freezeDelayMs;
    }

    private static int[] livePids(ApmProcessRecord rec) {
        final ArrayList<Integer> pids = new ArrayList<>(rec.pids.size());
        for (int i = 0; i < rec.pids.size(); i++) {
            final int pid = rec.pids.valueAt(i).pid;
            if (pid > 0) {
                pids.add(pid);
            }
        }
        final int[] out = new int[pids.size()];
        for (int i = 0; i < pids.size(); i++) {
            out[i] = pids.get(i);
        }
        return out;
    }

    private static void prune(ArrayList<Long> times, long min) {
        int write = 0;
        for (int i = 0; i < times.size(); i++) {
            if (times.get(i) >= min) {
                times.set(write++, times.get(i));
            }
        }
        while (times.size() > write) {
            times.remove(times.size() - 1);
        }
    }

    private static ApmProcessRecord find(ProcessStateTracker tracker, String target, int userId) {
        if (target == null) {
            return null;
        }
        Integer uid = parseUid(target);
        if (uid != null) {
            final ApmProcessRecord rec = tracker.get(uid);
            if (rec == null) {
                return null;
            }
            if (userId >= 0 && rec.userId >= 0 && rec.userId != userId) {
                return null;
            }
            return rec;
        }
        ApmProcessRecord match = null;
        for (int i = 0; i < tracker.size(); i++) {
            final ApmProcessRecord rec = tracker.valueAt(i);
            if (!rec.matchesName(target)) {
                continue;
            }
            if (userId >= 0 && rec.userId >= 0 && rec.userId != userId) {
                continue;
            }
            if (match != null) {
                return match;
            }
            match = rec;
        }
        return match;
    }

    private static Integer parseUid(String target) {
        final int length = target.length();
        if (length == 0 || length > 9) {
            return null;
        }
        for (int i = 0; i < length; i++) {
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
}
