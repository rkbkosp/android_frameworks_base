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
 * Defaults for the shadow adaptive process manager.
 *
 * <p>Adj cutoffs match {@code ProcessList} so a snapshot can be scored without
 * holding the activity manager lock. They are not a second OOM implementation.
 */
public final class ApmConstants {
    public static final int SCHEMA_VERSION = 1;

    /** Master switch. Off suppresses even shadow action logging. */
    public static final boolean DEFAULT_ENABLED = false;
    /** Decisions are recorded and dropped. There is no freezer in this CL. */
    public static final boolean DEFAULT_SHADOW_MODE = true;

    public static final long DEFAULT_FREEZE_DELAY_MS = 1500L;
    public static final long DEFAULT_BIG_APP_FREEZE_DELAY_MS = 3000L;
    public static final int DEFAULT_CHURN_LIMIT_60S = 4;
    public static final long DEFAULT_CHURN_COOLDOWN_MS = 600_000L;

    public static final long MAX_DELAY_MS = 60L * 60L * 1000L;
    public static final long MAX_COOLDOWN_MS = 24L * 60L * 60L * 1000L;
    public static final int MAX_CHURN_LIMIT = 100;

    public static final int EVENT_RING_SIZE = 2000;

    /** Cached and adj at or above the cached floor. */
    public static final int FREEZE_SCORE_THRESHOLD = 100;
    /** Reported on the decision even when the action chosen is freeze. */
    public static final int KILL_SCORE_THRESHOLD = 80;

    public static final String KEY_ENABLED = "apm_enabled";
    public static final String KEY_SHADOW_MODE = "apm_shadow_mode";
    public static final String KEY_FREEZE_DELAY_MS = "apm_freeze_delay_ms";
    public static final String KEY_BIG_APP_FREEZE_DELAY_MS = "apm_big_app_freeze_delay_ms";
    public static final String KEY_CHURN_LIMIT_60S = "apm_churn_limit_60s";
    public static final String KEY_CHURN_COOLDOWN_MS = "apm_churn_cooldown_ms";

    /**
     * ProcessRecord does not expose the current input method. This exemption is
     * intentionally not evaluated.
     */
    public static final String IME_EXEMPTION_GAP =
            "current-input-method exemption not applied; no IME fact on the process snapshot";

    public enum ManagedState {
        ACTIVE,
        GRACE,
        CACHED,
        EXEMPT
    }

    private ApmConstants() {}
}
