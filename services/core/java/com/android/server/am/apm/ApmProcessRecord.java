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

import android.os.Process;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseLongArray;

import com.android.server.am.ProcessList;
import com.android.server.am.apm.ApmConstants.ManagedState;

/**
 * Per-uid shadow record. AMS remains the source of truth; this is a copy of facts, not a
 * second process list, and it is never published to another thread while half-updated.
 */
final class ApmProcessRecord {
    static final class PidSlot {
        int pid;
        long startSeq;
        String processName;
        String packageName;
        int curAdj = ProcessList.UNKNOWN_ADJ;
        int curProcState = PROCESS_STATE_NONEXISTENT;
        boolean visible;
        boolean foregroundActivities;
        boolean foregroundService;
        boolean persistent;
    }

    final int uid;
    final boolean systemUid;
    int userId = UserHandle.USER_NULL;
    ManagedState state = ManagedState.GRACE;
    boolean initialized;
    int minAdj = ProcessList.UNKNOWN_ADJ;
    int procState = PROCESS_STATE_NONEXISTENT;
    boolean visible;
    boolean foreground;
    boolean foregroundService;
    boolean persistent;
    long lastTopElapsed;
    long graceUntilElapsed;
    final ArraySet<String> packages = new ArraySet<>();
    final ArraySet<String> processNames = new ArraySet<>();
    final SparseArray<PidSlot> pids = new SparseArray<>();
    /** processName -> startSeq for starts observed before a pid existed. */
    final ArrayMap<String, Long> pendingStartSeq = new ArrayMap<>();
    /** pid -> highest startSeq already observed dead. Missing key is -1. */
    final SparseLongArray deadPidSeq = new SparseLongArray();
    final ArrayMap<String, Long> deadNameSeq = new ArrayMap<>();
    PolicyDecision lastDecision;

    ApmProcessRecord(int uid) {
        this.uid = uid;
        this.systemUid = UserHandle.getAppId(uid) == Process.SYSTEM_UID;
    }

    String primaryPackage() {
        if (packages.size() > 0) {
            return packages.valueAt(0);
        }
        if (processNames.size() > 0) {
            return processNames.valueAt(0);
        }
        return null;
    }

    boolean matchesName(String name) {
        if (name == null) {
            return false;
        }
        return packages.contains(name) || processNames.contains(name);
    }
}
