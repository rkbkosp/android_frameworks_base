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

package com.android.server.am;

import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;

import android.app.ApplicationExitInfo;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArraySet;

import com.android.server.am.apm.ApmConstants;
import com.android.server.am.apm.ApmExecutor;
import com.android.server.am.apm.ApmFreezeResult;

import java.util.ArrayList;

/**
 * Runs APM freeze and unfreeze on the activity manager locks, then calls the existing
 * cached-app freezer. Does not write a cgroup itself.
 */
final class AmApmBridge implements ApmExecutor {
    private final ActivityManagerService mAm;

    AmApmBridge(ActivityManagerService am) {
        mAm = am;
    }

    @Override
    public ApmFreezeResult freezeUid(int uid, int[] pids) {
        final ArraySet<Integer> wanted = toSet(pids);
        final ArraySet<Integer> frozen = new ArraySet<>();
        final ArraySet<Integer> failed = new ArraySet<>();
        synchronized (mAm) {
            synchronized (mAm.mProcLock) {
                final UidRecord uidRec = mAm.mProcessList.getUidRecordLOSP(uid);
                if (uidRec == null) {
                    return failAll(pids);
                }
                uidRec.forEachProcess(app -> {
                    final int pid = app.getPid();
                    if (!wanted.contains(pid)) {
                        return;
                    }
                    if (mAm.getCachedAppOptimizerLSP().requestApmFreezeLSP(app)) {
                        frozen.add(pid);
                    } else {
                        failed.add(pid);
                    }
                });
            }
        }
        for (int i = 0; i < pids.length; i++) {
            if (!frozen.contains(pids[i]) && !failed.contains(pids[i])) {
                failed.add(pids[i]);
            }
        }
        return new ApmFreezeResult(toArray(frozen), toArray(failed));
    }

    /**
     * What the freezer actually holds for this uid. {@link #freezeUid} only queues the
     * freezer's work on its own handler, so this is the read that separates a frozen uid from
     * a queued one: nothing is frozen and nothing is queued means the request was lost.
     */
    @Override
    public int actualFreezeState(int uid) {
        final int[] live = new int[1];
        final int[] frozen = new int[1];
        final int[] pending = new int[1];
        synchronized (mAm) {
            synchronized (mAm.mProcLock) {
                final UidRecord uidRec = mAm.mProcessList.getUidRecordLOSP(uid);
                if (uidRec == null) {
                    return ApmFreezeResult.STATE_FAILED;
                }
                uidRec.forEachProcess(app -> {
                    if (app.getPid() <= 0 || app.isKilled()) {
                        return;
                    }
                    live[0]++;
                    if (app.isFrozen()) {
                        frozen[0]++;
                    } else if (app.mOptRecord.isPendingFreeze()) {
                        pending[0]++;
                    }
                });
            }
        }
        if (live[0] == 0) {
            return ApmFreezeResult.STATE_FAILED;
        }
        if (frozen[0] == live[0]) {
            return ApmFreezeResult.STATE_FROZEN;
        }
        // Partly applied, or still queued: the commit stands, this is not a lost request.
        return frozen[0] > 0 || pending[0] > 0
                ? ApmFreezeResult.STATE_PENDING : ApmFreezeResult.STATE_FAILED;
    }

    @Override
    public boolean unfreezeUid(int uid, int[] pids) {
        final ArraySet<Integer> wanted = toSet(pids);
        synchronized (mAm) {
            synchronized (mAm.mProcLock) {
                final UidRecord uidRec = mAm.mProcessList.getUidRecordLOSP(uid);
                if (uidRec == null) {
                    return true;
                }
                uidRec.forEachProcess(app -> {
                    if (wanted.contains(app.getPid())) {
                        mAm.getCachedAppOptimizerLSP().requestApmUnfreezeLSP(app,
                                CachedAppOptimizer.UNFREEZE_REASON_NONE);
                    }
                });
            }
        }
        return true;
    }

    @Override
    public int compactUid(int uid, int[] pids) {
        final int[] queued = new int[1];
        synchronized (mAm) {
            synchronized (mAm.mProcLock) {
                final ArrayList<ProcessRecord> victims = cachedVictimsLocked(uid, pids);
                if (victims == null) {
                    return 0;
                }
                for (int i = 0; i < victims.size(); i++) {
                    if (mAm.getCachedAppOptimizerLSP().compactApp(victims.get(i),
                            CachedAppOptimizer.CompactProfile.FULL,
                            CachedAppOptimizer.CompactSource.APP, false /* force */)) {
                        queued[0]++;
                    }
                }
            }
        }
        return queued[0];
    }

    @Override
    public boolean killCachedUid(int uid, int[] pids, String reason) {
        if (reason == null || !reason.startsWith(ApmConstants.KILL_REASON_PREFIX)) {
            return false;
        }
        synchronized (mAm) {
            synchronized (mAm.mProcLock) {
                final ArrayList<ProcessRecord> victims = cachedVictimsLocked(uid, pids);
                if (victims == null || victims.isEmpty()) {
                    return false;
                }
                for (int i = 0; i < victims.size(); i++) {
                    // Same kill ProcessRecord path OomAdjuster uses for a cached process.
                    victims.get(i).killLocked(reason, reason,
                            ApplicationExitInfo.REASON_OTHER,
                            ApplicationExitInfo.SUBREASON_TOO_MANY_CACHED,
                            true /* noisy */);
                }
                return true;
            }
        }
    }

    @Override
    public boolean killForScene(int uid, int[] pids, String reason) {
        if (reason == null) {
            return false;
        }
        synchronized (mAm) {
            synchronized (mAm.mProcLock) {
                final ArrayList<ProcessRecord> victims = cachedVictimsLocked(uid, pids);
                if (victims == null || victims.isEmpty()) {
                    return false;
                }
                for (int i = 0; i < victims.size(); i++) {
                    victims.get(i).killLocked(reason, reason,
                            ApplicationExitInfo.REASON_OTHER,
                            ApplicationExitInfo.SUBREASON_TOO_MANY_CACHED,
                            true /* noisy */);
                }
                return true;
            }
        }
    }

    @Override
    public void removeTasksForPackage(String packageName, int userId) {
        if (packageName == null || mAm.mAtmInternal == null) {
            return;
        }
        mAm.mAtmInternal.removeRecentTasksByPackageName(packageName, userId);
    }

    @Override
    public void forceStopForScene(String packageName, int userId, String reason) {
        if (packageName == null) {
            return;
        }
        synchronized (mAm) {
            mAm.forceStopPackageLocked(packageName, -1 /* appId */,
                    false /* callerWillRestart */, false /* purgeCache */, true /* doit */,
                    false /* evenPersistent */, false /* uninstalling */,
                    false /* packageStateStopped */, userId,
                    reason == null ? "apm-scene" : reason);
        }
    }

    /**
     * Live processes of {@code uid} that are still cached, or null if any live process
     * fails that check. Null means compact or kill none of them.
     */
    private ArrayList<ProcessRecord> cachedVictimsLocked(int uid, int[] pids) {
        final UidRecord uidRec = mAm.mProcessList.getUidRecordLOSP(uid);
        if (uidRec == null) {
            return null;
        }
        final ArraySet<Integer> wanted = toSet(pids);
        final boolean filter = !wanted.isEmpty();
        final ArrayList<ProcessRecord> victims = new ArrayList<>();
        final boolean[] blocked = new boolean[1];
        uidRec.forEachProcess(app -> {
            if (app.getPid() <= 0 || app.isKilled() || app.isKilledByAm()) {
                return;
            }
            if (!liveCached(app)) {
                blocked[0] = true;
                return;
            }
            if (filter && !wanted.contains(app.getPid())) {
                return;
            }
            victims.add(app);
        });
        if (blocked[0]) {
            return null;
        }
        return victims;
    }

    private static boolean liveCached(ProcessRecord app) {
        if (app.isPersistent() || app.isHomeProcess()) {
            return false;
        }
        if (UserHandle.getAppId(app.uid) < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        if (app.getHasForegroundActivities() || app.getHasVisibleActivities()) {
            return false;
        }
        if (app.getServices().hasForegroundServices()) {
            return false;
        }
        final int adj = app.getCurAdj();
        if (adj < ProcessList.CACHED_APP_MIN_ADJ || adj > ProcessList.CACHED_APP_MAX_ADJ) {
            return false;
        }
        final int state = app.getCurProcState();
        return state >= PROCESS_STATE_CACHED_ACTIVITY && state <= PROCESS_STATE_CACHED_EMPTY;
    }

    private static ApmFreezeResult failAll(int[] pids) {
        return new ApmFreezeResult(new int[0], pids == null ? new int[0] : pids.clone());
    }

    private static ArraySet<Integer> toSet(int[] pids) {
        final ArraySet<Integer> set = new ArraySet<>();
        if (pids == null) {
            return set;
        }
        for (int i = 0; i < pids.length; i++) {
            set.add(pids[i]);
        }
        return set;
    }

    private static int[] toArray(ArraySet<Integer> values) {
        final int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.valueAt(i);
        }
        return out;
    }
}
