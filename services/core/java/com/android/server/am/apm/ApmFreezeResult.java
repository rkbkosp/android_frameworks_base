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
 * Per-pid result of one uid freeze request. Partial success is a failed freeze:
 * the controller unfreezes {@link #frozenPids}.
 */
public final class ApmFreezeResult {
    public final int[] frozenPids;
    public final int[] failedPids;

    public ApmFreezeResult(int[] frozenPids, int[] failedPids) {
        this.frozenPids = frozenPids == null ? new int[0] : frozenPids;
        this.failedPids = failedPids == null ? new int[0] : failedPids;
    }

    public boolean partial() {
        return frozenPids.length > 0 && failedPids.length > 0;
    }

    public boolean committed() {
        return failedPids.length == 0 && frozenPids.length > 0;
    }
}
