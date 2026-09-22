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
 * Decides whether one package is skipped for one scene. Absent keys do not skip.
 * Athena-white bits are not invented: a mask skips a package only when that package's
 * stored bits are non-zero and intersect the mask. Both the old mask and the new mask
 * are tested.
 */
public final class ClearSceneRunner {
    private ClearSceneRunner() {}

    /** Facts the scene can see. Unknown booleans stay false. Unknown ranks stay 0. */
    public static final class Facts {
        public boolean recentLock;
        public boolean inputMethod;
        public boolean foreground;
        public boolean liveWallpaper;
        public boolean bluetooth;
        public boolean widget;
        public boolean home;
        public boolean keyguard;
        public boolean vpn;
        public boolean pip;
        public boolean audio;
        public boolean navigating;
        public boolean downloading;
        public boolean perceptible;
        public boolean persistent;
        public boolean appManager;
        public boolean backgroundProtect;
        public boolean foregroundService;
        public boolean protectedGame;
        public boolean superAliveGame;
        public boolean preloadProtect;
        public boolean accessibility;
        public boolean visibleWindow;
        public boolean nativeProcess;
        public boolean backup;
        public boolean system;
        public boolean importantApp;
        /** 1-based recent rank. 0 means unknown, which does not match a recency skip. */
        public int recentRank;
        public int recentUsedTime;
        public int highPriorityRank;
        public int curAdj;
        /** Per-package old athena-white bits. 0 means the bitmap is absent. */
        public int athenaWhiteBits;
        /** Per-package new athena-white bits. 0 means the bitmap is absent. */
        public int athenaWhiteNewBits;

        boolean is(String key) {
            switch (key) {
                case ClearScene.SKIP_RECENT_LOCK:
                    return recentLock;
                case "cc_skip_inputmethod":
                    return inputMethod;
                case "cc_skip_foreground":
                    return foreground;
                case "cc_skip_live_wallpaper":
                    return liveWallpaper;
                case "cc_skip_bluetooth":
                    return bluetooth;
                case "cc_skip_widget":
                    return widget;
                case "cc_skip_home":
                    return home;
                case "cc_skip_keyguard":
                    return keyguard;
                case "cc_skip_vpn":
                    return vpn;
                case "cc_skip_pip":
                    return pip;
                case "cc_skip_audio":
                    return audio;
                case "cc_skip_navigating":
                    return navigating;
                case "cc_skip_downloading":
                    return downloading;
                case "cc_skip_perceptible":
                    return perceptible;
                case "cc_skip_persistent":
                    return persistent;
                case "cc_skip_appmanager":
                    return appManager;
                case "cc_skip_background_protect":
                    return backgroundProtect;
                case "cc_skip_foreground_service":
                    return foregroundService;
                case "cc_skip_protected_game":
                    return protectedGame;
                case "cc_skip_super_alive_game":
                    return superAliveGame;
                case "cc_skip_preload_protect":
                    return preloadProtect;
                case "cc_skip_accessibility_service":
                    return accessibility;
                case "cc_skip_visible_window":
                    return visibleWindow;
                case "cc_skip_native":
                    return nativeProcess;
                case "cc_skip_backup":
                    return backup;
                case "cc_skip_system":
                    return system;
                case "cc_skip_important_app":
                    return importantApp;
                default:
                    return false;
            }
        }
    }

    private static final String[] FLAGS = {
            ClearScene.SKIP_RECENT_LOCK,
            "cc_skip_inputmethod",
            "cc_skip_foreground",
            "cc_skip_live_wallpaper",
            "cc_skip_bluetooth",
            "cc_skip_widget",
            "cc_skip_home",
            "cc_skip_keyguard",
            "cc_skip_vpn",
            "cc_skip_pip",
            "cc_skip_audio",
            "cc_skip_navigating",
            "cc_skip_downloading",
            "cc_skip_perceptible",
            "cc_skip_persistent",
            "cc_skip_appmanager",
            "cc_skip_background_protect",
            "cc_skip_foreground_service",
            "cc_skip_protected_game",
            "cc_skip_super_alive_game",
            "cc_skip_preload_protect",
            "cc_skip_accessibility_service",
            "cc_skip_visible_window",
            "cc_skip_native",
            "cc_skip_backup",
            "cc_skip_system",
            "cc_skip_important_app",
    };

    public static boolean skipped(ClearScene scene, Facts facts) {
        if (scene == null || facts == null) {
            return false;
        }
        for (int i = 0; i < FLAGS.length; i++) {
            if (scene.flag(FLAGS[i]) && facts.is(FLAGS[i])) {
                return true;
            }
        }
        if (rankSkipped(scene, ClearScene.SKIP_RECENT_USED_NUM, facts.recentRank)) {
            return true;
        }
        if (rankSkipped(scene, ClearScene.SKIP_RECENT_USED_TIME, facts.recentUsedTime)) {
            return true;
        }
        if (rankSkipped(scene, ClearScene.SKIP_HIGH_PRIORITY_COUNT, facts.highPriorityRank)) {
            return true;
        }
        if (scene.has(ClearScene.SKIP_SYSTEM_MAX_ADJ) && facts.system) {
            final int ceiling = scene.number(ClearScene.SKIP_SYSTEM_MAX_ADJ, Integer.MAX_VALUE);
            if (facts.curAdj <= ceiling) {
                return true;
            }
        }
        final int oldMask = scene.number(ClearScene.SKIP_ATHENA_WHITE, 0);
        final int newMask = scene.number(ClearScene.SKIP_ATHENA_WHITE_NEW, 0);
        if (oldMask != 0 && facts.athenaWhiteBits != 0
                && (facts.athenaWhiteBits & oldMask) != 0) {
            return true;
        }
        if (newMask != 0 && facts.athenaWhiteNewBits != 0
                && (facts.athenaWhiteNewBits & newMask) != 0) {
            return true;
        }
        return false;
    }

    /** Negative means every known rank. Zero means this key does not skip. */
    private static boolean rankSkipped(ClearScene scene, String key, int rank) {
        if (!scene.has(key) || rank <= 0) {
            return false;
        }
        final int limit = scene.number(key, 0);
        if (limit < 0) {
            return true;
        }
        return limit > 0 && rank <= limit;
    }
}
