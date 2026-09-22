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
 * Holds one immutable {@link ApmConfig}. Failed validation keeps the previous instance.
 */
public final class ApmConfigManager {
    private final Object mLock = new Object();
    private volatile ApmConfig mCurrent = ApmConfig.defaults();

    public ApmConfig get() {
        return mCurrent;
    }

    /**
     * Atomically replace the current config after range checks. Generation is assigned here
     * so callers cannot roll it backwards.
     *
     * @return false if {@code candidate} is rejected. The previous config stays in place.
     */
    public boolean tryReplace(ApmConfig candidate) {
        if (!isValid(candidate)) {
            return false;
        }
        synchronized (mLock) {
            if (sameValues(mCurrent, candidate)) {
                return true;
            }
            mCurrent = candidate.withGeneration(mCurrent.generation + 1);
        }
        return true;
    }

    private static boolean sameValues(ApmConfig current, ApmConfig candidate) {
        return current.enabled == candidate.enabled
                && current.shadowMode == candidate.shadowMode
                && current.freezeDelayMs == candidate.freezeDelayMs
                && current.bigAppFreezeDelayMs == candidate.bigAppFreezeDelayMs
                && current.churnLimit60s == candidate.churnLimit60s
                && current.churnCooldownMs == candidate.churnCooldownMs;
    }

    public static boolean isValid(ApmConfig candidate) {
        if (candidate == null) {
            return false;
        }
        if (candidate.freezeDelayMs < 0 || candidate.freezeDelayMs > ApmConstants.MAX_DELAY_MS) {
            return false;
        }
        if (candidate.bigAppFreezeDelayMs < candidate.freezeDelayMs
                || candidate.bigAppFreezeDelayMs > ApmConstants.MAX_DELAY_MS) {
            return false;
        }
        if (candidate.churnLimit60s < 0 || candidate.churnLimit60s > ApmConstants.MAX_CHURN_LIMIT) {
            return false;
        }
        if (candidate.churnCooldownMs < 0
                || candidate.churnCooldownMs > ApmConstants.MAX_COOLDOWN_MS) {
            return false;
        }
        return true;
    }
}
