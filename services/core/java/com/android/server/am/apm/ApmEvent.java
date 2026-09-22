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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable facts copied out of activity manager locks before the policy thread runs.
 * This object never points at a live {@code ProcessRecord}.
 */
public final class ApmEvent {
    public enum Kind {
        PROCESS_STARTED,
        PROCESS_DIED,
        OOM_ADJ_COMPLETED,
        TOP_RESUMED
    }

    /**
     * One process row from a completed adj pass. Values are primitives and strings only.
     */
    public static final class ProcessSnapshot {
        public final int pid;
        public final int uid;
        public final int userId;
        public final String processName;
        public final String packageName;
        public final long startSeq;
        public final int curAdj;
        public final int curProcState;
        public final boolean persistent;
        public final boolean foregroundActivities;
        public final boolean visibleActivities;
        public final boolean foregroundService;
        public final long rssKb;
        public final long swapKb;
        public final boolean home;
        public final boolean hasTask;
        public final boolean forceStopped;
        /**
         * Copied under the activity manager lock from
         * {@code PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL} or a media-playback
         * foreground service. Not an audio-focus callback.
         */
        public final boolean foregroundAudio;

        public ProcessSnapshot(int pid, int uid, int userId, String processName,
                String packageName, long startSeq, int curAdj, int curProcState,
                boolean persistent, boolean foregroundActivities, boolean visibleActivities,
                boolean foregroundService) {
            this(pid, uid, userId, processName, packageName, startSeq, curAdj, curProcState,
                    persistent, foregroundActivities, visibleActivities, foregroundService,
                    0L /* rssKb */, 0L /* swapKb */, false /* home */, false /* hasTask */,
                    false /* forceStopped */);
        }

        public ProcessSnapshot(int pid, int uid, int userId, String processName,
                String packageName, long startSeq, int curAdj, int curProcState,
                boolean persistent, boolean foregroundActivities, boolean visibleActivities,
                boolean foregroundService, long rssKb, long swapKb, boolean home,
                boolean hasTask, boolean forceStopped) {
            this(pid, uid, userId, processName, packageName, startSeq, curAdj, curProcState,
                    persistent, foregroundActivities, visibleActivities, foregroundService,
                    rssKb, swapKb, home, hasTask, forceStopped, false /* foregroundAudio */);
        }

        public ProcessSnapshot(int pid, int uid, int userId, String processName,
                String packageName, long startSeq, int curAdj, int curProcState,
                boolean persistent, boolean foregroundActivities, boolean visibleActivities,
                boolean foregroundService, long rssKb, long swapKb, boolean home,
                boolean hasTask, boolean forceStopped, boolean foregroundAudio) {
            this.pid = pid;
            this.uid = uid;
            this.userId = userId;
            this.processName = processName;
            this.packageName = packageName;
            this.startSeq = startSeq;
            this.curAdj = curAdj;
            this.curProcState = curProcState;
            this.persistent = persistent;
            this.foregroundActivities = foregroundActivities;
            this.visibleActivities = visibleActivities;
            this.foregroundService = foregroundService;
            this.rssKb = rssKb;
            this.swapKb = swapKb;
            this.home = home;
            this.hasTask = hasTask;
            this.forceStopped = forceStopped;
            this.foregroundAudio = foregroundAudio;
        }
    }

    public final Kind kind;
    public final int pid;
    public final int uid;
    public final int userId;
    public final String processName;
    public final String packageName;
    public final long startSeq;
    public final boolean persistent;
    public final int oomAdjReason;
    public final List<ProcessSnapshot> processes;

    private ApmEvent(Kind kind, int pid, int uid, int userId, String processName,
            String packageName, long startSeq, boolean persistent, int oomAdjReason,
            List<ProcessSnapshot> processes) {
        this.kind = kind;
        this.pid = pid;
        this.uid = uid;
        this.userId = userId;
        this.processName = processName;
        this.packageName = packageName;
        this.startSeq = startSeq;
        this.persistent = persistent;
        this.oomAdjReason = oomAdjReason;
        this.processes = processes;
    }

    public static ApmEvent processStarted(int pid, int uid, int userId, String processName,
            String packageName, long startSeq, boolean persistent) {
        return new ApmEvent(Kind.PROCESS_STARTED, pid, uid, userId, processName, packageName,
                startSeq, persistent, 0 /* oomAdjReason */, Collections.emptyList());
    }

    public static ApmEvent processDied(int pid, int uid, int userId, String processName,
            long startSeq) {
        return new ApmEvent(Kind.PROCESS_DIED, pid, uid, userId, processName, null /* package */,
                startSeq, false /* persistent */, 0 /* oomAdjReason */, Collections.emptyList());
    }

    public static ApmEvent oomAdjCompleted(int oomAdjReason, List<ProcessSnapshot> processes) {
        final List<ProcessSnapshot> copy = processes == null
                ? Collections.emptyList() : new ArrayList<>(processes);
        return new ApmEvent(Kind.OOM_ADJ_COMPLETED, -1 /* pid */, -1 /* uid */, -1 /* userId */,
                null /* processName */, null /* packageName */, 0 /* startSeq */,
                false /* persistent */, oomAdjReason, Collections.unmodifiableList(copy));
    }

    public static ApmEvent topResumed(int uid, int pid, int userId, String processName) {
        return new ApmEvent(Kind.TOP_RESUMED, pid, uid, userId, processName, null /* package */,
                0 /* startSeq */, false /* persistent */, 0 /* oomAdjReason */,
                Collections.emptyList());
    }
}
