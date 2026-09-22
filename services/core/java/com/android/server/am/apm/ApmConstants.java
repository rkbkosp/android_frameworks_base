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
 * Defaults for the adaptive process manager.
 *
 * <p>The mechanism runs unless DeviceConfig or the shell turns the master switch off
 * or turns shadow mode on. Adj cutoffs match {@code ProcessList} so a snapshot can be
 * scored without holding the activity manager lock. They are not a second OOM
 * implementation.
 */
public final class ApmConstants {
    public static final int SCHEMA_VERSION = 1;

    /** Master switch. Off suppresses new snapshots and does not freeze or kill. */
    public static final boolean DEFAULT_ENABLED = true;
    /**
     * When true, decisions are logged and not applied. Freezer, kill, compact, and
     * kernel writes stay off while this is set, even if their own flags are on.
     */
    public static final boolean DEFAULT_SHADOW_MODE = false;
    /** Cached-uid freezer. Requires the master switch and shadow mode off. */
    public static final boolean DEFAULT_FREEZER_ENABLED = true;
    /**
     * Compact and cached kill under memory pressure. Requires the master switch
     * and shadow mode off. DeviceConfig key {@link #KEY_MEMORY_ENABLED}.
     */
    public static final boolean DEFAULT_MEMORY_ENABLED = true;

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
    public static final String KEY_FREEZER_ENABLED = "apm_freezer_enabled";
    public static final String KEY_MEMORY_ENABLED = "apm_memory_controller_enabled";
    public static final String KEY_FREEZE_DELAY_MS = "apm_freeze_delay_ms";
    public static final String KEY_BIG_APP_FREEZE_DELAY_MS = "apm_big_app_freeze_delay_ms";
    public static final String KEY_CHURN_LIMIT_60S = "apm_churn_limit_60s";
    public static final String KEY_CHURN_COOLDOWN_MS = "apm_churn_cooldown_ms";

    /**
     * RSS at or above this uses {@link #DEFAULT_BIG_APP_FREEZE_DELAY_MS}.
     * The value is a size threshold, not a package list. Units are kilobytes,
     * matching {@code ProcessRecord#getLastRss()}.
     */
    public static final long BIG_APP_RSS_KB = 200L * 1024L;

    /** Synchronous provider callers wait at most this long for an APM unfreeze. */
    public static final long UNFREEZE_WAIT_MS = 200L;

    /**
     * ProcessRecord does not expose the current input method. This exemption is
     * intentionally not evaluated.
     */
    public static final String IME_EXEMPTION_GAP =
            "current-input-method exemption not applied; no IME fact on the process snapshot";

    /**
     * Mem-factor values. These match {@code ProcessStats.ADJ_MEM_FACTOR_*}.
     * Normal is freezer-only. Moderate and low compact. Critical kills.
     */
    public static final int PRESSURE_NORMAL = 0;
    public static final int PRESSURE_MODERATE = 1;
    public static final int PRESSURE_LOW = 2;
    public static final int PRESSURE_CRITICAL = 3;

    /** Pause after a critical kill before reading pressure again. */
    public static final long KILL_RECHECK_MS = 400L;
    /** Emergency batch cap while pressure stays critical. */
    public static final int EMERGENCY_KILL_BATCH = 3;
    /** Cached-process kill reason. Callers must keep this prefix. */
    public static final String KILL_REASON_PREFIX = "apm:critical:";

    /**
     * {@code oplus_bsp_zram_opt.c} clamps the value it applies to 0..200.
     * 100 is that module's default. 160 and 180 are its kswapd and direct
     * low-bucket values, both inside the clamp.
     */
    public static final int SWAPPINESS_MIN = 0;
    public static final int SWAPPINESS_MAX = 200;
    public static final int SWAPPINESS_DEFAULT = 100;
    public static final int SWAPPINESS_MODERATE = 160;
    public static final int SWAPPINESS_CRITICAL = 180;

    /** One decimal uid per line, the format {@code schedinfo.c} parses and reads back. */
    public static final String FG_UIDS_PATH = "/proc/fg_info/fg_uids";
    public static final String SWAPPINESS_PATH =
            "/sys/module/oplus_bsp_zram_opt/parameters/vm_swappiness";
    /** {@code OPLUS_UID_CAP} in the ported schedinfo parser. */
    public static final int FG_UID_CAP = 128;

    /** Higher while pressure is at or above moderate. Default once it clears. */
    public static int swappinessForPressure(int level) {
        if (level >= PRESSURE_CRITICAL) {
            return SWAPPINESS_CRITICAL;
        }
        if (level >= PRESSURE_MODERATE) {
            return SWAPPINESS_MODERATE;
        }
        return SWAPPINESS_DEFAULT;
    }

    public enum ManagedState {
        ACTIVE,
        GRACE,
        CACHED,
        EXEMPT
    }

    private ApmConstants() {}
}
