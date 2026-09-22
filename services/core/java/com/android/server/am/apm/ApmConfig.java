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
 * Immutable policy configuration. Callers swap a whole instance; they do not mutate fields.
 */
public final class ApmConfig {
    public final boolean enabled;
    public final boolean shadowMode;
    public final long freezeDelayMs;
    public final long bigAppFreezeDelayMs;
    public final int churnLimit60s;
    public final long churnCooldownMs;
    public final int generation;

    public ApmConfig(boolean enabled, boolean shadowMode, long freezeDelayMs,
            long bigAppFreezeDelayMs, int churnLimit60s, long churnCooldownMs, int generation) {
        this.enabled = enabled;
        this.shadowMode = shadowMode;
        this.freezeDelayMs = freezeDelayMs;
        this.bigAppFreezeDelayMs = bigAppFreezeDelayMs;
        this.churnLimit60s = churnLimit60s;
        this.churnCooldownMs = churnCooldownMs;
        this.generation = generation;
    }

    public static ApmConfig defaults() {
        return new ApmConfig(ApmConstants.DEFAULT_ENABLED, ApmConstants.DEFAULT_SHADOW_MODE,
                ApmConstants.DEFAULT_FREEZE_DELAY_MS, ApmConstants.DEFAULT_BIG_APP_FREEZE_DELAY_MS,
                ApmConstants.DEFAULT_CHURN_LIMIT_60S, ApmConstants.DEFAULT_CHURN_COOLDOWN_MS,
                0 /* generation */);
    }

    public ApmConfig withEnabled(boolean enabled) {
        return new ApmConfig(enabled, shadowMode, freezeDelayMs, bigAppFreezeDelayMs,
                churnLimit60s, churnCooldownMs, generation);
    }

    public ApmConfig withShadowMode(boolean shadowMode) {
        return new ApmConfig(enabled, shadowMode, freezeDelayMs, bigAppFreezeDelayMs,
                churnLimit60s, churnCooldownMs, generation);
    }

    public ApmConfig withGeneration(int generation) {
        return new ApmConfig(enabled, shadowMode, freezeDelayMs, bigAppFreezeDelayMs,
                churnLimit60s, churnCooldownMs, generation);
    }
}
