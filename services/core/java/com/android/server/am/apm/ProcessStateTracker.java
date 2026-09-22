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

import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.PROCESS_STATE_TOP;

import android.util.SparseArray;

import com.android.server.am.ProcessList;
import com.android.server.am.apm.ApmConstants.ManagedState;
import com.android.server.am.apm.ApmEvent.ProcessSnapshot;
import com.android.server.am.apm.ApmProcessRecord.PidSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * Folds copied start, death, top, and adj-pass events into per-uid records.
 *
 * <p>Callers must not invoke this while holding the activity manager lock, and must not
 * hand it a live process object. Start sequences are remembered so a duplicate or late
 * event cannot resurrect a pid that AMS has already reported dead.
 */
final class ProcessStateTracker {
    private final SparseArray<ApmProcessRecord> mRecords = new SparseArray<>();
    private int mTopUid = -1;

    ApmProcessRecord get(int uid) {
        return mRecords.get(uid);
    }

    int size() {
        return mRecords.size();
    }

    ApmProcessRecord valueAt(int index) {
        return mRecords.valueAt(index);
    }

    int pidCount(int uid) {
        final ApmProcessRecord rec = mRecords.get(uid);
        return rec == null ? 0 : rec.pids.size();
    }

    int getTopUid() {
        return mTopUid;
    }

    /**
     * Foreground uids, top first. Capped at {@link ApmConstants#FG_UID_CAP}.
     * Order is stable for a given tracker so an unchanged set is not rewritten.
     */
    int[] copyForegroundUids() {
        int count = mTopUid >= 0 ? 1 : 0;
        for (int i = 0; i < mRecords.size(); i++) {
            final ApmProcessRecord rec = mRecords.valueAt(i);
            if (rec.foreground && rec.uid >= 0 && rec.uid != mTopUid) {
                count++;
            }
        }
        if (count > ApmConstants.FG_UID_CAP) {
            count = ApmConstants.FG_UID_CAP;
        }
        final int[] out = new int[count];
        int write = 0;
        if (mTopUid >= 0 && write < out.length) {
            out[write++] = mTopUid;
        }
        for (int i = 0; i < mRecords.size() && write < out.length; i++) {
            final ApmProcessRecord rec = mRecords.valueAt(i);
            if (rec.foreground && rec.uid >= 0 && rec.uid != mTopUid) {
                out[write++] = rec.uid;
            }
        }
        return out;
    }

    /**
     * @return the uid that stopped being top, or -1 if the top uid did not change.
     */
    int onTopResumed(int uid, int userId, String processName, long now, long graceMs) {
        final int previous = mTopUid;
        if (uid < 0) {
            mTopUid = -1;
            demoteTop(previous, now, graceMs);
            return previous;
        }
        if (uid == previous) {
            markTop(getOrCreate(uid, userId, processName, null), now);
            return -1;
        }
        mTopUid = uid;
        demoteTop(previous, now, graceMs);
        markTop(getOrCreate(uid, userId, processName, null), now);
        return previous;
    }

    void onProcessStarted(int pid, int uid, int userId, String processName, String packageName,
            long startSeq, boolean persistent, long now, long graceMs) {
        final ApmProcessRecord rec = getOrCreate(uid, userId, processName, packageName);
        if (persistent) {
            rec.persistent = true;
        }
        if (pid <= 0) {
            acceptPendingStart(rec, processName, startSeq);
        } else if (!acceptLiveStart(rec, pid, processName, packageName, startSeq)) {
            ensureInitialState(rec, now, graceMs);
            return;
        }
        ensureInitialState(rec, now, graceMs);
    }

    void onProcessDied(int pid, int uid, int userId, String processName, long startSeq) {
        final ApmProcessRecord rec = getOrCreate(uid, userId, processName, null);
        if (processName != null) {
            final Long deadName = rec.deadNameSeq.get(processName);
            if (deadName == null || startSeq >= deadName) {
                rec.deadNameSeq.put(processName, startSeq);
            }
            final Long pending = rec.pendingStartSeq.get(processName);
            if (pending != null && pending <= startSeq) {
                rec.pendingStartSeq.remove(processName);
            }
        }
        if (pid <= 0) {
            return;
        }
        final long dead = rec.deadPidSeq.get(pid, -1L);
        if (dead >= 0 && startSeq < dead) {
            return;
        }
        final PidSlot slot = rec.pids.get(pid);
        if (slot == null) {
            rec.deadPidSeq.put(pid, startSeq);
            return;
        }
        if (startSeq < slot.startSeq) {
            return;
        }
        rec.pids.remove(pid);
        rec.deadPidSeq.put(pid, Math.max(startSeq, slot.startSeq));
        foldSlots(rec);
    }

    void onOomAdj(List<ProcessSnapshot> processes, long now, long graceMs) {
        if (processes == null || processes.isEmpty()) {
            return;
        }
        final SparseArray<ArrayList<ProcessSnapshot>> byUid = new SparseArray<>();
        for (int i = 0, n = processes.size(); i < n; i++) {
            final ProcessSnapshot snap = processes.get(i);
            if (snap == null) {
                continue;
            }
            ArrayList<ProcessSnapshot> group = byUid.get(snap.uid);
            if (group == null) {
                group = new ArrayList<>();
                byUid.put(snap.uid, group);
            }
            group.add(snap);
        }
        for (int i = 0; i < byUid.size(); i++) {
            applyUidSnapshot(byUid.keyAt(i), byUid.valueAt(i), now, graceMs);
        }
    }

    private void applyUidSnapshot(int uid, ArrayList<ProcessSnapshot> group, long now,
            long graceMs) {
        int userId = group.get(0).userId;
        String processName = group.get(0).processName;
        String packageName = group.get(0).packageName;
        final ApmProcessRecord rec = getOrCreate(uid, userId, processName, packageName);
        for (int i = 0; i < group.size(); i++) {
            applyProcessSnapshot(rec, group.get(i));
        }
        foldSlots(rec);
        if (uid == mTopUid) {
            rec.foreground = true;
        }
        transitionFromFacts(rec, now, graceMs);
    }

    private void applyProcessSnapshot(ApmProcessRecord rec, ProcessSnapshot snap) {
        rememberNames(rec, snap.processName, snap.packageName);
        if (snap.persistent) {
            rec.persistent = true;
        }
        if (snap.pid <= 0) {
            acceptPendingStart(rec, snap.processName, snap.startSeq);
            return;
        }
        final long dead = rec.deadPidSeq.get(snap.pid, -1L);
        PidSlot slot = rec.pids.get(snap.pid);
        if (dead >= 0 && (snap.startSeq <= 0 || snap.startSeq <= dead)
                && (slot == null || slot.startSeq <= dead)) {
            return;
        }
        if (slot != null && snap.startSeq > 0 && slot.startSeq > snap.startSeq) {
            return;
        }
        if (slot == null) {
            slot = new PidSlot();
            slot.pid = snap.pid;
            rec.pids.put(snap.pid, slot);
        }
        if (snap.startSeq > 0) {
            slot.startSeq = snap.startSeq;
        }
        slot.processName = snap.processName;
        slot.packageName = snap.packageName;
        slot.curAdj = snap.curAdj;
        slot.curProcState = snap.curProcState;
        slot.visible = snap.visibleActivities;
        slot.foregroundActivities = snap.foregroundActivities;
        slot.foregroundService = snap.foregroundService;
        slot.persistent = snap.persistent;
        slot.home = snap.home;
        slot.hasTask = snap.hasTask;
        slot.forceStopped = snap.forceStopped;
        slot.foregroundAudio = snap.foregroundAudio;
        slot.locationFgs = snap.locationFgs;
        slot.rssKb = snap.rssKb;
        slot.swapKb = snap.swapKb;
        if (snap.startSeq > dead) {
            rec.deadPidSeq.delete(snap.pid);
        }
        if (snap.processName != null) {
            rec.pendingStartSeq.remove(snap.processName);
        }
    }

    private static void foldSlots(ApmProcessRecord rec) {
        if (rec.pids.size() == 0) {
            rec.minAdj = ProcessList.UNKNOWN_ADJ;
            rec.procState = PROCESS_STATE_NONEXISTENT;
            rec.visible = false;
            rec.foreground = false;
            rec.foregroundService = false;
            rec.foregroundAudio = false;
            rec.locationFgs = false;
            rec.rssKb = 0L;
            rec.swapKb = 0L;
            return;
        }
        int minAdj = ProcessList.UNKNOWN_ADJ;
        int procState = PROCESS_STATE_NONEXISTENT;
        boolean visible = false;
        boolean foreground = false;
        boolean foregroundService = false;
        boolean persistent = rec.persistent;
        boolean home = false;
        boolean hasTask = false;
        boolean forceStopped = false;
        boolean foregroundAudio = false;
        boolean locationFgs = false;
        long rssKb = 0L;
        long swapKb = 0L;
        for (int i = 0; i < rec.pids.size(); i++) {
            final PidSlot slot = rec.pids.valueAt(i);
            if (slot.curAdj < minAdj) {
                minAdj = slot.curAdj;
            }
            if (slot.curProcState >= 0 && slot.curProcState < procState) {
                procState = slot.curProcState;
            }
            visible |= slot.visible;
            foreground |= slot.foregroundActivities;
            foregroundService |= slot.foregroundService;
            persistent |= slot.persistent;
            home |= slot.home;
            hasTask |= slot.hasTask;
            forceStopped |= slot.forceStopped;
            foregroundAudio |= slot.foregroundAudio;
            locationFgs |= slot.locationFgs;
            rssKb += Math.max(0L, slot.rssKb);
            swapKb += Math.max(0L, slot.swapKb);
        }
        rec.minAdj = minAdj;
        rec.procState = procState;
        rec.visible = visible;
        rec.foreground = foreground;
        rec.foregroundService = foregroundService;
        rec.persistent = persistent;
        rec.home = home;
        rec.hasTask = hasTask;
        rec.forceStopped = forceStopped;
        rec.foregroundAudio = foregroundAudio;
        rec.locationFgs = locationFgs;
        rec.rssKb = rssKb;
        rec.swapKb = swapKb;
    }

    private void transitionFromFacts(ApmProcessRecord rec, long now, long graceMs) {
        if (rec.systemUid || rec.persistent) {
            rec.state = ManagedState.EXEMPT;
            rec.cachedSinceElapsed = 0L;
            rec.initialized = true;
            return;
        }
        if (isActive(rec)) {
            rec.state = ManagedState.ACTIVE;
            rec.cachedSinceElapsed = 0L;
            rec.initialized = true;
            rec.lastTopElapsed = now;
            rec.graceUntilElapsed = 0;
            return;
        }
        if (!rec.initialized || rec.state == ManagedState.ACTIVE
                || rec.state == ManagedState.EXEMPT) {
            rec.initialized = true;
            rec.state = ManagedState.GRACE;
            rec.cachedSinceElapsed = 0L;
            rec.graceStartedElapsed = now;
            rec.graceUntilElapsed = now + graceMs;
            return;
        }
        if (rec.state == ManagedState.GRACE && now >= rec.graceUntilElapsed
                && rec.minAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
            rec.state = ManagedState.CACHED;
            if (rec.cachedSinceElapsed == 0L) {
                rec.cachedSinceElapsed = now;
            }
        }
    }

    static boolean isActive(ApmProcessRecord rec) {
        if (rec.foreground || rec.visible || rec.foregroundService) {
            return true;
        }
        if (rec.minAdj <= ProcessList.VISIBLE_APP_ADJ) {
            return true;
        }
        return rec.procState >= 0 && rec.procState <= PROCESS_STATE_TOP;
    }

    private void ensureInitialState(ApmProcessRecord rec, long now, long graceMs) {
        if (rec.systemUid || rec.persistent) {
            rec.state = ManagedState.EXEMPT;
            rec.initialized = true;
            return;
        }
        if (!rec.initialized) {
            rec.initialized = true;
            rec.state = ManagedState.GRACE;
            rec.graceStartedElapsed = now;
            rec.graceUntilElapsed = now + graceMs;
        }
    }

    private void markTop(ApmProcessRecord rec, long now) {
        rec.initialized = true;
        rec.foreground = true;
        rec.cachedSinceElapsed = 0L;
        rec.lastTopElapsed = now;
        if (rec.systemUid || rec.persistent) {
            rec.state = ManagedState.EXEMPT;
        } else {
            rec.state = ManagedState.ACTIVE;
            rec.graceUntilElapsed = 0;
        }
    }

    private void demoteTop(int previousUid, long now, long graceMs) {
        if (previousUid < 0) {
            return;
        }
        final ApmProcessRecord prev = mRecords.get(previousUid);
        if (prev == null || prev.state != ManagedState.ACTIVE) {
            return;
        }
        if (prev.systemUid || prev.persistent) {
            prev.state = ManagedState.EXEMPT;
            return;
        }
        prev.foreground = false;
        prev.cachedSinceElapsed = 0L;
        prev.state = ManagedState.GRACE;
        prev.graceStartedElapsed = now;
        prev.graceUntilElapsed = now + graceMs;
    }

    private boolean acceptLiveStart(ApmProcessRecord rec, int pid, String processName,
            String packageName, long startSeq) {
        final long dead = rec.deadPidSeq.get(pid, -1L);
        if (dead >= 0 && startSeq <= dead) {
            return false;
        }
        final PidSlot existing = rec.pids.get(pid);
        if (existing != null) {
            if (startSeq < existing.startSeq) {
                return false;
            }
            if (startSeq == existing.startSeq) {
                return false;
            }
            existing.startSeq = startSeq;
            existing.processName = processName;
            existing.packageName = packageName;
            rec.deadPidSeq.delete(pid);
            return true;
        }
        final PidSlot slot = new PidSlot();
        slot.pid = pid;
        slot.startSeq = startSeq;
        slot.processName = processName;
        slot.packageName = packageName;
        rec.pids.put(pid, slot);
        rec.deadPidSeq.delete(pid);
        if (processName != null) {
            rec.pendingStartSeq.remove(processName);
        }
        return true;
    }

    private void acceptPendingStart(ApmProcessRecord rec, String processName, long startSeq) {
        if (processName == null) {
            return;
        }
        final Long dead = rec.deadNameSeq.get(processName);
        if (dead != null && startSeq <= dead) {
            return;
        }
        final Long pending = rec.pendingStartSeq.get(processName);
        if (pending != null && startSeq <= pending) {
            return;
        }
        rec.pendingStartSeq.put(processName, startSeq);
    }

    private ApmProcessRecord getOrCreate(int uid, int userId, String processName,
            String packageName) {
        ApmProcessRecord rec = mRecords.get(uid);
        if (rec == null) {
            rec = new ApmProcessRecord(uid);
            mRecords.put(uid, rec);
        }
        if (rec.userId < 0 && userId >= 0) {
            rec.userId = userId;
        }
        rememberNames(rec, processName, packageName);
        return rec;
    }

    private static void rememberNames(ApmProcessRecord rec, String processName,
            String packageName) {
        if (processName != null) {
            rec.processNames.add(processName);
        }
        if (packageName != null) {
            rec.packages.add(packageName);
        }
    }
}
