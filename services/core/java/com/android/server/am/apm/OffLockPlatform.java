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

import java.util.function.Consumer;

/**
 * Platform calls and writes a controller decided on while the service lock was held.
 *
 * <p>The service owns the queue. A call made under the lock is enqueued and runs when that
 * lock is released, on the same thread and in the order it was decided. The activity manager
 * locks, the cgroup writes and the proc-node writes therefore never nest inside the service
 * lock, and a slow one can only delay the next decision of this service: a binder thread
 * waiting for the service lock is never stuck behind a platform call.
 *
 * <p>{@code commit} runs back under the service lock. It must not call this interface, and
 * it must not be slow: it is the section a binder thread can queue behind.
 */
interface OffLockPlatform {
    /** Run this platform work with the service lock released. */
    void run(Runnable work);

    /**
     * Freeze these pids. {@code commit} receives the request result, or null when the
     * executor is absent or threw. The request only queues the freezer's work; see
     * {@link ApmExecutor#actualFreezeState}.
     */
    void freeze(int uid, int[] pids, Consumer<ApmFreezeResult> commit);

    /** Freeze state of this uid, as one of the {@code ApmFreezeResult.STATE_*} values. */
    void freezeState(int uid, Consumer<Integer> commit);

    /**
     * Unfreeze these pids. Idempotent. {@code commit} runs back under the service lock once
     * the request has been issued, so the frozen state is dropped only after the platform was
     * asked to release the uid.
     */
    void unfreeze(int uid, int[] pids, Runnable commit);
}
