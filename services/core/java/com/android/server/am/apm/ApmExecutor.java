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

/**
 * Process actions APM is allowed to ask for. The activity manager implementation takes the
 * freezer locks and calls {@code CachedAppOptimizer}. Tests supply a fake. This interface
 * does not write a cgroup itself.
 */
public interface ApmExecutor {
    /** Freeze these live pids. A pid omitted from both arrays was not requested. */
    ApmFreezeResult freezeUid(int uid, int[] pids);

    /**
     * The freeze state the platform freezer actually reports for this uid, as one of
     * {@link ApmFreezeResult#STATE_FROZEN}, {@link ApmFreezeResult#STATE_PENDING},
     * {@link ApmFreezeResult#STATE_FAILED} and {@link ApmFreezeResult#STATE_UNKNOWN}.
     * {@link #freezeUid} only queues the freezer's work, so this is the only way to tell a
     * frozen uid from a queued one. A fake with no real freezer keeps the default.
     */
    default int actualFreezeState(int uid) {
        return ApmFreezeResult.STATE_UNKNOWN;
    }

    /**
     * Unfreeze these pids. Idempotent: a pid that is not frozen is success, not an error.
     *
     * @return false only when the request could not be issued at all
     */
    boolean unfreezeUid(int uid, int[] pids);

    /**
     * Queue compaction for cached processes of this uid. Returns how many were queued.
     * Zero means nothing was queued, including when compaction is unavailable.
     */
    int compactUid(int uid, int[] pids);

    /**
     * Kill one cached uid through the activity manager's cached-process kill.
     * {@code reason} starts with {@link ApmConstants#KILL_REASON_PREFIX}.
     * Not {@code forceStopPackage}.
     *
     * @return true if at least one process was killed
     */
    boolean killCachedUid(int uid, int[] pids, String reason);

    /**
     * Cached-process kill for a clear scene. Not a user force-stop.
     * Default does nothing so a test fake can ignore it.
     */
    default boolean killForScene(int uid, int[] pids, String reason) {
        return false;
    }

    /** Drop the recent task. The process is not kept alive by this call. */
    default void removeTasksForPackage(String packageName, int userId) {
    }

    /**
     * Scene strategy 1. Runs the activity manager force-stop path. Task-restore may
     * rewrite the clean type later; this method does not keep the process resident.
     */
    default void forceStopForScene(String packageName, int userId, String reason) {
    }
}
