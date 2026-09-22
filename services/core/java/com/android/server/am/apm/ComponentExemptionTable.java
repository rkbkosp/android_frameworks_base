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

import android.util.ArrayMap;
import android.util.ArraySet;

/**
 * Caller and called exemption lists. They ship empty except the defaults that are
 * actually known. An empty list exempts nobody. A black hit overrides a white hit
 * on the same call. Third-white, oplus-white, GMS, and protect are not {@code denyKill}.
 */
public final class ComponentExemptionTable {
    public enum Kind {
        ACTIVITY, SERVICE, PROVIDER, BROADCAST, JOB, ALARM
    }

    public enum Decision {
        DEFAULT, ALLOW, DENY
    }

    public static final int APP_CLASS_THIRD = 1;
    public static final int APP_CLASS_THIRD_WHITE = 2;
    public static final int APP_CLASS_OPLUS_WHITE = 4;
    public static final int APP_CLASS_GMS = 8;
    public static final int APP_CLASS_SYS_BLACK = 16;
    public static final int APP_CLASS_PROTECT = 32;

    public static final int MASK_NET_WHITE = 1;
    public static final int MASK_KILL_WHITE = 2;
    public static final int MASK_PROXY_ACCESSIBILITY = 4;
    public static final int MASK_PROXY_DISPLAY = 8;
    public static final int MASK_WAKELOCK_WHITE = 16;
    public static final int MASK_TOAST_WHITE = 32;
    public static final int MASK_GAME_NET_WHITE = 64;
    public static final int MASK_SYNC_JOB_BLACK = 128;
    public static final int MASK_SKIP_FROZEN_WHITE = 256;
    public static final int MASK_JOB_WHITE = 512;
    public static final int MASK_SYNC_WHITE = 1024;
    public static final int MASK_TELEPHONY_BLACK = 2048;
    public static final int MASK_AIRPLANE_WHITE = 4096;
    public static final int MASK_PROXY_SELF_SERVICE = 8192;
    public static final int MASK_ASYNC_BINDER_CALLSYS = 16384;
    public static final int MASK_ACCESSIBILITY_BLACK = 32768;
    public static final int MASK_KEEP_ALIVE_BLACK = 65536;
    public static final int MASK_OPLUS_APP_PREFIX = 131072;
    public static final int MASK_PRIVILEGE_THIRD_APP = 262144;
    public static final int MASK_HIGH_EXTREME_STRICT = 524288;
    public static final int MASK_HIGH_EXTREME_STRICT_CALLED = 1048576;
    public static final int MASK_HIGH_EXTREME_STRICT_CALLING = 2097152;
    public static final int MASK_HEYTAP_APP_NET_BLACK = 4194304;
    public static final int MASK_MULTI_WINDOW_SCENE_WHITE = 8388608;
    public static final int MASK_KEEP_ALIVE_WHITE = 16777216;

    public static final int PREVENT_ACTIVITY = 1;
    public static final int PREVENT_START_SERVICE = 2;
    public static final int PREVENT_BINDER_SERVICE = 4;
    public static final int PREVENT_PROVIDER = 8;
    public static final int PREVENT_BROADCAST = 16;
    public static final int PREVENT_JOB = 32;
    public static final int PREVENT_SYNC = 64;
    public static final int PREVENT_ALARM = 128;
    public static final int PREVENT_ASYNC_BINDER = 256;
    public static final int PREVENT_CLOSE_SOCKET = 512;
    public static final int PREVENT_GPS = 2048;
    public static final int PREVENT_FLAG_MAX = 4096;

    public static final int PROXY_SERVICE = 1;
    public static final int PROXY_BROADCAST = 2;
    public static final int PROXY_JOB = 4;
    public static final int PROXY_SENSOR = 8;
    public static final int PROXY_BINDER = 16;
    public static final int PROXY_WAKELOCK = 64;
    public static final int PROXY_GPS = 128;
    public static final int PROXY_AUDIO = 256;
    public static final int PROXY_BT_SCAN = 512;
    public static final int PROXY_BROADCAST_PROXY = 1024;

    public static final int SCENE_FLAG_NIGHT = 1;
    public static final int SCENE_FLAG_LCD_OFF = 2;
    public static final int SCENE_FLAG_LCD_ON = 4;
    public static final int SCENE_FLAG_CHARGING = 8;
    public static final int SCENE_FLAG_EXTREME_FG = 16;
    public static final int SCENE_FLAG_FAST_FREEZE = 32;
    public static final int SCENE_FLAG_MIN_SYSTEM = 256;
    public static final int SCENE_FLAG_MAX = 512;

    public static final int DEFAULT_FF_TIMEOUT = 1500;
    public static final int KILL_FROZEN_INTERVAL_MS = 1_800_000;
    public static final int DELAY_FREEZE_MS = 2000;
    public static final int PROXY_BC_MAX = 10;
    public static final int MAX_PENDING_BC = 10;
    public static final int AUDIO_BUFFER_MS = 15_000;
    public static final int AUDIO_BUF_STRICT_MS = 1000;
    public static final int IM_BOOT_EXEMPT_MS = 60_000;
    public static final int CONNECTIVITY_EXEMPT_MS = 5000;
    public static final int WAKELOCK_GRACE_MS = 3000;
    public static final int WAKELOCK_MAX_HOLD_MS = 30_000;
    public static final int CHECK_RECENT_R_TO_M_MS = 20_000;
    public static final int CHECK_R_TO_M_MS = 10_000;
    public static final int CHECK_RECENT_M_TO_F_MS = 10_000;
    public static final int CHECK_M_TO_F_MS = 5000;
    public static final int THERMAL_ENTER = 10;
    public static final int THERMAL_EXIT = 9;

    public static final String DEPENDENCY_BACKUP = "com.coloros.backuprestore";
    public static final String DOZE_WHITE = "com.oplus.riderMode";
    public static final String WAKELOCK_AUDIO_MIX = "AudioMix";
    public static final String WAKELOCK_AUDIO_SPATIAL = "AudioSpatial";

    /** Kill-white raises a score. It is not {@code denyKill}. */
    public static final int KILL_WHITE_SCORE = 100;

    private final ArrayMap<String, ArraySet<String>> mActivityCallerWhite = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mActivityCallerBlack = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mActivityCalledWhite = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mActivityCalledBlack = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mServiceCallerWhite = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mServiceCallerBlack = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mServiceCalledWhite = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mServiceCalledBlack = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mProviderCallerWhite = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mProviderCallerBlack = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mProviderCalledWhite = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mProviderCalledBlack = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mBroadcastCallerWhite = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mBroadcastCallerBlack = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mBroadcastCalledWhite = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mBroadcastCalledBlack = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mJobWhite = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mAlarmWhite = new ArrayMap<>();
    private final ArrayMap<String, ArraySet<String>> mAlarmBlack = new ArrayMap<>();
    private final ArraySet<String> mSyncJobBlack = new ArraySet<>();
    private final ArraySet<String> mFastWhite = new ArraySet<>();
    private final ArraySet<String> mFastBlack = new ArraySet<>();
    private final ArrayMap<String, Integer> mFastTimeout = new ArrayMap<>();
    private final ArraySet<String> mDependency = new ArraySet<>();
    private final ArraySet<String> mDozeWhite = new ArraySet<>();
    private final ArraySet<String> mWakelockTags = new ArraySet<>();
    private final ArrayMap<String, Integer> mAppClass = new ArrayMap<>();
    private final ArrayMap<String, Integer> mResourceMask = new ArrayMap<>();

    public ComponentExemptionTable() {
        mDependency.add(DEPENDENCY_BACKUP);
        mDozeWhite.add(DOZE_WHITE);
        mWakelockTags.add(WAKELOCK_AUDIO_MIX);
        mWakelockTags.add(WAKELOCK_AUDIO_SPATIAL);
    }

    /** Third-white (2) or oplus-white (4) only. GMS and protect are not this whitelist. */
    public static boolean isWhitelistApp(int appClass) {
        return (appClass & (APP_CLASS_THIRD_WHITE | APP_CLASS_OPLUS_WHITE)) != 0;
    }

    public void setAppClass(String packageName, int appClass) {
        if (packageName == null) {
            return;
        }
        mAppClass.put(packageName, appClass);
    }

    public int appClass(String packageName) {
        final Integer value = mAppClass.get(packageName);
        return value == null ? 0 : value;
    }

    /**
     * Resource mask for one package. Never sets {@code denyKill}.
     * {@link #MASK_SYNC_JOB_BLACK} beats {@link #MASK_JOB_WHITE}.
     * {@link #MASK_KEEP_ALIVE_BLACK} beats {@link #MASK_KEEP_ALIVE_WHITE}.
     */
    public AppProtectionPolicy maskPolicy(String packageName, int userId, int mask) {
        if (packageName != null) {
            mResourceMask.put(packageName, mask);
        }
        final boolean denyJob = (mask & MASK_SYNC_JOB_BLACK) != 0;
        final boolean keepBlack = (mask & MASK_KEEP_ALIVE_BLACK) != 0;
        return AppProtectionPolicy.builder(packageName, userId, ProtectionArbiter.Layer.STATIC)
                .denyFreeze((mask & MASK_SKIP_FROZEN_WHITE) != 0)
                .denyKill(false)
                .allowNetworkWhileFrozen((mask & MASK_NET_WHITE) != 0)
                .allowJobWakeup((mask & MASK_JOB_WHITE) != 0 && !denyJob)
                .taskRestore((mask & MASK_KEEP_ALIVE_WHITE) != 0 && !keepBlack)
                .protectionScore((mask & MASK_KILL_WHITE) != 0 ? KILL_WHITE_SCORE : 0)
                .source("config")
                .reason("resource-mask")
                .expiresElapsed(Long.MAX_VALUE)
                .build();
    }

    public boolean jobDenied(String packageName) {
        return packageName != null && (mSyncJobBlack.contains(packageName)
                || ((mask(packageName) & MASK_SYNC_JOB_BLACK) != 0));
    }

    public boolean jobAllowed(String packageName, String component) {
        if (jobDenied(packageName)) {
            return false;
        }
        return hit(mJobWhite, packageName, component)
                || (mask(packageName) & MASK_JOB_WHITE) != 0;
    }

    public boolean alarmAllowed(String packageName, String action) {
        if (hit(mAlarmBlack, packageName, action)) {
            return false;
        }
        return hit(mAlarmWhite, packageName, action);
    }

    public boolean mayDeliver(Kind kind, String callerPackage, String targetPackage, String name,
            int sceneId) {
        if (check(kind, true, callerPackage, name, sceneId) == Decision.DENY) {
            return false;
        }
        return check(kind, false, targetPackage, name, sceneId) != Decision.DENY;
    }

    public Decision check(Kind kind, boolean calling, String packageName, String name,
            int sceneId) {
        final ArrayMap<String, ArraySet<String>> black = black(kind, calling);
        final ArrayMap<String, ArraySet<String>> white = white(kind, calling);
        if (hit(black, packageName, name)) {
            return Decision.DENY;
        }
        if (hit(white, packageName, name)) {
            return Decision.ALLOW;
        }
        return Decision.DEFAULT;
    }

    /** Test hook. Production lists stay empty apart from the known defaults. */
    public void put(Kind kind, boolean calling, boolean black, String packageName, String name) {
        final ArrayMap<String, ArraySet<String>> map = black ? black(kind, calling)
                : white(kind, calling);
        if (map == null || packageName == null || name == null) {
            return;
        }
        ArraySet<String> set = map.get(packageName);
        if (set == null) {
            set = new ArraySet<>();
            map.put(packageName, set);
        }
        set.add(name);
    }

    public boolean inFastFreezeWhite(String packageName) {
        return packageName != null && mFastWhite.contains(packageName);
    }

    public boolean skipFastFreeze(String packageName) {
        return packageName != null && mFastBlack.contains(packageName);
    }

    public int fastFreezeTimeout(String packageName) {
        final Integer specific = packageName == null ? null : mFastTimeout.get(packageName);
        if (specific != null) {
            return specific;
        }
        final Integer star = mFastTimeout.get("*");
        return star != null ? star : DEFAULT_FF_TIMEOUT;
    }

    public boolean isDependency(String packageName) {
        return mDependency.contains(packageName);
    }

    public boolean isDozeWhite(String packageName) {
        return mDozeWhite.contains(packageName);
    }

    public boolean ignoresProxyWakelock(String tag) {
        return tag != null && mWakelockTags.contains(tag);
    }

    /** Scene id used for broadcast lists. Screen on is 4, deep sleep is 1, otherwise 2. */
    public static int broadcastSceneId(boolean screenOn, boolean deepSleepOrDisnet) {
        if (screenOn) {
            return SCENE_FLAG_LCD_ON;
        }
        if (deepSleepOrDisnet) {
            return SCENE_FLAG_NIGHT;
        }
        return SCENE_FLAG_LCD_OFF;
    }

    private int mask(String packageName) {
        final Integer value = mResourceMask.get(packageName);
        return value == null ? 0 : value;
    }

    private ArrayMap<String, ArraySet<String>> white(Kind kind, boolean calling) {
        switch (kind) {
            case ACTIVITY:
                return calling ? mActivityCallerWhite : mActivityCalledWhite;
            case SERVICE:
                return calling ? mServiceCallerWhite : mServiceCalledWhite;
            case PROVIDER:
                return calling ? mProviderCallerWhite : mProviderCalledWhite;
            case BROADCAST:
                return calling ? mBroadcastCallerWhite : mBroadcastCalledWhite;
            case JOB:
                return calling ? mJobWhite : mJobWhite;
            case ALARM:
                return calling ? mAlarmWhite : mAlarmWhite;
            default:
                return null;
        }
    }

    private ArrayMap<String, ArraySet<String>> black(Kind kind, boolean calling) {
        switch (kind) {
            case ACTIVITY:
                return calling ? mActivityCallerBlack : mActivityCalledBlack;
            case SERVICE:
                return calling ? mServiceCallerBlack : mServiceCalledBlack;
            case PROVIDER:
                return calling ? mProviderCallerBlack : mProviderCalledBlack;
            case BROADCAST:
                return calling ? mBroadcastCallerBlack : mBroadcastCalledBlack;
            case JOB:
                return null;
            case ALARM:
                return calling ? mAlarmBlack : mAlarmBlack;
            default:
                return null;
        }
    }

    private static boolean hit(ArrayMap<String, ArraySet<String>> map, String packageName,
            String name) {
        if (map == null || packageName == null) {
            return false;
        }
        final ArraySet<String> set = map.get(packageName);
        if (set == null || set.isEmpty()) {
            return false;
        }
        if (set.contains("*")) {
            return true;
        }
        return name != null && set.contains(name);
    }
}
