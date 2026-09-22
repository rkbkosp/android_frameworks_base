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

import com.android.server.am.ProcessList;

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
     * Navigation protection. GNSS provider usage is the primary fact; a location
     * foreground service, or an allowlisted package that was recently top, confirms it.
     * The allowlist is the navigation app type 11 of the ColorOS image,
     * {@code config/sys_hans_hardcoded_app_type_list.xml}, which lists only these two
     * packages. Nothing else is guessed here: an allowlist hit still needs live GNSS.
     */
    public static final boolean DEFAULT_NAVIGATION_ENABLED = true;
    /** A candidate must hold GNSS this long before it is protected. */
    public static final long DEFAULT_NAVIGATION_ENTER_DEBOUNCE_MS = 3_000L;
    /**
     * Protection outlives the confirmation by this long. The image uses 10 and 30 minute
     * windows; the port starts shorter because a false keep-alive costs more than a late one.
     */
    public static final long DEFAULT_NAVIGATION_EXIT_GRACE_MS = 180_000L;
    /** How recently an allowlisted package must have been top to confirm GNSS. */
    public static final long DEFAULT_NAVIGATION_RECENT_TOP_MS = 120_000L;
    /** Upper bound applied to the adj of a protected uid. */
    public static final boolean DEFAULT_NAVIGATION_ADJ_CLAMP_ENABLED = true;
    public static final int DEFAULT_NAVIGATION_ADJ_CLAMP = ProcessList.PERCEPTIBLE_APP_ADJ;
    /** No clamp is applied above this adj, so a value above it would be unverifiable. */
    public static final int MAX_NAVIGATION_ADJ = ProcessList.PERCEPTIBLE_APP_ADJ;
    public static final String[] NAVIGATION_ALLOWLIST = {
            "com.autonavi.minimap",
            "com.baidu.BaiduMap",
    };

    public static final String KEY_NAVIGATION_ENABLED = "apm_navigation_enabled";
    public static final String KEY_NAVIGATION_ADJ_CLAMP_ENABLED =
            "apm_navigation_adj_clamp_enabled";
    public static final String KEY_NAVIGATION_ADJ = "apm_navigation_adj";

    /**
     * Adj floor for a package that is exempt for as long as it is installed
     * ({@link ProtectionArbiter#isAlwaysExempt}). AOSP's {@code SERVICE_ADJ}: LMKD leaves
     * the uid alone until memory pressure is critical, and it is not promoted into the
     * perceptible range, which would be a real memory cost for a background push service.
     * Not measured on a device.
     */
    public static final int ALWAYS_EXEMPT_ADJ_FLOOR = ProcessList.SERVICE_ADJ;

    /**
     * Facts this tree still cannot see without guessing a package list or binding
     * into an app. The navigation notification chain is the one navigation signal left
     * out: it lives in NotificationManagerInternal and its notification ids have no AOSP
     * source. Audio focus that sets neither
     * {@code PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL} nor a media-playback foreground
     * service is not on the adj snapshot. This tree has no
     * {@code PROCESS_CAPABILITY_FOREGROUND_AUDIO} constant.
     */
    public static final String REMAINING_ROLE_GAPS =
            "the navigation notification chain has no AOSP source; audio focus without "
                    + "PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL or a media-playback "
                    + "foreground service is not visible";

    /**
     * Ordinary jobs and alarms are not deferred. These stay false. An allow bit
     * unfreezes a frozen uid; it does not turn deferral on.
     */
    public static final boolean DEFAULT_DEFER_JOBS = false;
    public static final boolean DEFAULT_DEFER_ALARMS = false;

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
