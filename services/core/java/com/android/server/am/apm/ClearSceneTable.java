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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hard-coded {@code sys_athena_config_list.xml} numbers. Scenes are selected by
 * {@code caller_name}. A repeated {@code cc_name} is not a key. Per-package athena-white
 * bitmaps are not in the donor zip and are not invented here.
 */
public final class ClearSceneTable {
    public static final int VERSION = 2026060401;
    public static final int ATHENA_LMK_ADJ_THRESHOLD = 300;
    public static final int ATHENA_LMK_TRIGGER_INV_SEC = 10;
    public static final int SCENE_COUNT = 53;

    private static final ClearSceneTable INSTANCE = new ClearSceneTable();

    private final List<ClearScene> mScenes;
    private final ArrayMap<String, ArrayList<ClearScene>> mByCaller = new ArrayMap<>();
    private final ArrayMap<String, ArrayList<ClearScene>> mByName = new ArrayMap<>();

    public static ClearSceneTable get() {
        return INSTANCE;
    }

    private ClearSceneTable() {
        final ArrayList<ClearScene> scenes = new ArrayList<>(SCENE_COUNT);
        addCore(scenes);
        addOneKey(scenes);
        addAbnormal(scenes);
        addSpec(scenes);
        if (scenes.size() != SCENE_COUNT) {
            throw new IllegalStateException("clear scene count " + scenes.size());
        }
        mScenes = Collections.unmodifiableList(scenes);
        for (int i = 0; i < scenes.size(); i++) {
            final ClearScene scene = scenes.get(i);
            bucket(mByName, scene.name).add(scene);
            for (int c = 0; c < scene.callers.size(); c++) {
                bucket(mByCaller, scene.callers.get(c)).add(scene);
            }
        }
    }

    public int sceneCount() {
        return mScenes.size();
    }

    public List<ClearScene> scenes() {
        return mScenes;
    }

    /** One scene, or null when {@code key} is missing or ambiguous. */
    public ClearScene select(String key) {
        if (key == null) {
            return null;
        }
        final ArrayList<ClearScene> byCaller = mByCaller.get(key);
        if (byCaller != null) {
            return byCaller.size() == 1 ? byCaller.get(0) : null;
        }
        final ArrayList<ClearScene> byName = mByName.get(key);
        if (byName != null && byName.size() == 1) {
            return byName.get(0);
        }
        return null;
    }

    public List<ClearScene> scenesForCaller(String caller) {
        final ArrayList<ClearScene> rows = mByCaller.get(caller);
        if (rows == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(rows);
    }

    public Integer externalStrategy(String module) {
        final ExternalClear row = external(module);
        return row == null ? null : row.strategy;
    }

    public ExternalClear external(String module) {
        if (module == null) {
            return null;
        }
        for (int i = 0; i < EXTERNAL.length; i++) {
            if (module.equals(EXTERNAL[i].module)) {
                return EXTERNAL[i];
            }
        }
        return null;
    }

    public FastClear fastClear(String name) {
        for (int i = 0; i < FAST_CLEAR.length; i++) {
            if (FAST_CLEAR[i].name.equals(name)) {
                return FAST_CLEAR[i];
            }
        }
        return null;
    }

    /** Greatest RAM-GB floor {@code k <= ramGb} that has a value. Missing cells are skipped. */
    public static int bucketMb(int ramGb, int[] floors, int[] values) {
        int best = values[0];
        for (int i = 0; i < floors.length; i++) {
            if (floors[i] <= ramGb) {
                best = values[i];
            }
        }
        return best;
    }

    public static int athenaLmkMemMb(int ramGb) {
        return bucketMb(ramGb, new int[] {2, 3, 4, 6, 8, 10},
                new int[] {600, 1024, 1380, 1500, 2048, 2048});
    }

    public static int autoCleanupMb(int ramGb) {
        return bucketMb(ramGb, new int[] {2, 3, 4, 6, 8, 10},
                new int[] {600, 600, 800, 1024, 1024, 1024});
    }

    public static int sappKillerMb(int ramGb) {
        return bucketMb(ramGb, new int[] {2, 3, 4, 6, 8, 10},
                new int[] {400, 400, 600, 800, 800, 800});
    }

    /** App-specific killer threshold in MB. -1 when the package is not in the list. */
    public static int sappShouldKillMb(String packageName) {
        if ("com.tencent.mm".equals(packageName)
                || "com.tencent.mobileqq".equals(packageName)) {
            return 400;
        }
        if ("com.eg.android.AlipayGphone".equals(packageName)
                || "com.feiliao.flipchat.android".equals(packageName)) {
            return 200;
        }
        return -1;
    }

    public static int memoryOnTriggerMb(int ramGb) {
        return bucketMb(ramGb, new int[] {2, 3, 4, 6, 8, 12},
                new int[] {600, 800, 1000, 1600, 2000, 3000});
    }

    public static int memoryOnPurposeMb(int ramGb) {
        return bucketMb(ramGb, new int[] {2, 3, 4, 6, 8, 12},
                new int[] {800, 1000, 1200, 1800, 2200, 3500});
    }

    public static int restartProcKillAvailMb(int ramGb) {
        return bucketMb(ramGb, new int[] {2, 3, 4, 6, 8, 12, 16, 24},
                new int[] {500, 600, 800, 1000, 1200, 1300, 1800, 2300});
    }

    public static int powerAdaptiveRecentProtect(int ramGb) {
        return bucketMb(ramGb, new int[] {2, 3, 4, 6, 8, 12, 16},
                new int[] {5, 5, 5, 5, 6, 8, 12});
    }

    public static final String[] SUPER_CRITICAL_KILL = {
            "com.heytap.themestore",
            "com.coloros.smartsidebar",
            "com.heytap.pictorial",
            "com.coloros.colordirectservice",
            "com.coloros.sceneservice",
            "com.coloros.floatassistant",
            "com.heytap.openid",
            "com.opos.ads",
            "com.coloros.assistantscreen",
    };

    public static final String[] SKIP_KILL_SYSTEM_AUDIO = {
            "com.oplus.ocar",
            "com.oplus.aicall",
    };

    public static final String[] SWIPE_UP_PROTECT = {
            "com.tencent.mm",
            "com.tencent.mobileqq",
    };

    public static final String[] FW_MIDDLE_PRIORITY = {
            "com.tencent.mm",
            "com.tencent.mobileqq",
    };

    public static final class FastClear {
        public final String name;
        public final int code;
        public final int delta;
        public final int recentKeep;

        FastClear(String name, int code, int delta, int recentKeep) {
            this.name = name;
            this.code = code;
            this.delta = delta;
            this.recentKeep = recentKeep;
        }
    }

    public static final FastClear[] FAST_CLEAR = {
            new FastClear("com.oppo.camera.camera_startup", 2000, 400, 3),
            new FastClear("com.oppo.camera.camera_mode_changed", 2001, 500, 3),
            new FastClear("com.oppo.camera.camera_video_recorder", 2002, 500, 3),
            new FastClear("com.oneplus.camera.camera_startup", 2003, 400, 3),
            new FastClear("com.oneplus.camera.camera_mode_changed", 2004, 500, 3),
            new FastClear("com.oneplus.camera.camera_video_recorder", 2005, 500, 3),
            new FastClear("com.oplus.camera.camera_startup", 2006, 400, 3),
            new FastClear("com.oplus.camera.camera_mode_changed", 2007, 500, 3),
            new FastClear("com.oplus.camera.camera_video_recorder", 2008, 500, 3),
            new FastClear("com.oplus.games.game_startup", 2009, 100, 3),
            new FastClear("com.oplus.aiunit.aigc", 2010, 400, 3),
            new FastClear("com.realme.aiengine.ai_engine_clear", 2011, 400, 3),
            new FastClear("com.tencent.mm.plugin.recordvideo.activity.MMRecordUI", -1, 300, 3),
    };

    public static final class ExternalClear {
        public final String pkg;
        public final String module;
        public final int strategy;
        public final int type;

        ExternalClear(String pkg, String module, int strategy, int type) {
            this.pkg = pkg;
            this.module = module;
            this.strategy = strategy;
            this.type = type;
        }
    }

    /** strategy 1 is the force-stop shape. Commented-out athena rows are omitted. */
    public static final ExternalClear[] EXTERNAL = {
            new ExternalClear("android.preload_kill", "android.preload_kill.forcestop", 0, 500),
            new ExternalClear("android.ams.provider", "android.ams.provider", 1, 501),
            new ExternalClear("android.oguard", "android.oguard.kill", 3, 502),
            new ExternalClear("android.oguard", "android.oguard.abnormalAudio.kill", 3, 502),
            new ExternalClear("android.oguard", "android.oguard.gameInFocus.kill", 1, 502),
            new ExternalClear("android.cpulimit.cpuhighload", "android.cpulimit.cpuhighload", 2, 503),
            new ExternalClear("com.oplus.battery", "com.oplus.battery.safety.hightemperature", 0, 504),
            new ExternalClear("android.bbds", "android.bbds.thread_kill", 2, 505),
            new ExternalClear("com.coloros.shortcuts", "com.coloros.shortcuts", 4, 506),
            new ExternalClear("android.bbds", "android.bbds.lowmem_kill", 2, 507),
            new ExternalClear("com.coloros.remoteguardservice", "com.coloros.remoteguardservice", 4, 508),
            new ExternalClear("com.coloros.remoteguardservice",
                    "com.coloros.remoteguardservice_stop", 1, 509),
            new ExternalClear("revival.app.control", "revival.slot.app.kill", 3, 510),
            new ExternalClear("com.coloros.shortcuts", "com.coloros.shortcuts.force_stop", 1, 511),
            new ExternalClear("com.oplus.atlas", "com.oplus.atlas", 2, 512),
            new ExternalClear("com.oplus.mediaturbo", "com.oplus.mediaturbo.service", 4, 513),
            new ExternalClear("com.coloros.phonemanager",
                    "com.coloros.phonemanager.networkdetect", 4, 514),
            new ExternalClear("com.oplus.games", "com.oplus.games.exit_game", 1, 515),
            new ExternalClear("com.oplus.camera", "com.oplus.camera.fallback.kill", 4, 516),
            new ExternalClear("com.oplus.camera", "com.oplus.camera.fallback.kill.uid", 3, 516),
            new ExternalClear("com.oplus.camera", "com.oplus.camera.fallback.kill.pid", 2, 516),
            new ExternalClear("com.oplus.camera", "com.oplus.camera.fallback.kill.forcestop", 1, 516),
    };

    public static final String[] EXTERNAL_MIN_THRESHOLD_ZERO = {
            "clear_spec#android.bbds.lowmem_kill",
            "clear_spec#com.coloros.shortcuts",
            "clear_spec#revival.slot.app.kill",
            "clear_spec#config_com.coloros.shortcuts",
            "clear_spec#config_com.coloros.shortcuts.force_stop",
            "clear_spec#com.oplus.camera.fallback.kill",
            "clear_spec#com.oplus.camera.fallback.kill.uid",
            "clear_spec#com.oplus.camera.fallback.kill.pid",
            "clear_spec#com.oplus.camera.fallback.kill.forcestop",
    };

    private static ArrayList<ClearScene> bucket(ArrayMap<String, ArrayList<ClearScene>> map,
            String key) {
        ArrayList<ClearScene> rows = map.get(key);
        if (rows == null) {
            rows = new ArrayList<>();
            map.put(key, rows);
        }
        return rows;
    }

    private static void addCore(ArrayList<ClearScene> out) {
        out.add(b("athena_lmk")
                .n("cc_skip_athena_white", 259)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", -1)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_navigating", "cc_skip_downloading",
                        "cc_skip_persistent", "cc_skip_appmanager", "cc_skip_background_protect")
                .f("cc_skip_perceptible", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_foreground_service")
                .build());
        out.add(deep("deep_clear").build());
        out.add(deep("app_level_periodic_deep_clear").build());
        out.add(deep("sys_level_periodic_deep_clear").build());
        out.add(b("deep_clear_excessive_mem_app")
                .n("cc_skip_athena_white", 0)
                .n("cc_skip_recent_used_num", 1)
                .t("cc_skip_foreground", "cc_skip_audio")
                .f("cc_skip_recent_lock", "cc_skip_super_alive_game", "cc_skip_inputmethod",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_navigating", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_skip_persistent", "cc_skip_appmanager", "cc_do_remove_task",
                        "cc_do_clear_trash", "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_preload_protect", "cc_skip_foreground_service")
                .build());
        out.add(b("deep_clear_force_kill")
                .n("cc_skip_athena_white", 0)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_foreground", "cc_skip_bluetooth", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_navigating")
                .f("cc_skip_recent_lock", "cc_skip_super_alive_game", "cc_skip_inputmethod",
                        "cc_skip_live_wallpaper", "cc_skip_widget", "cc_skip_home",
                        "cc_skip_keyguard", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_skip_persistent", "cc_skip_appmanager", "cc_do_remove_task",
                        "cc_do_clear_trash", "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service", "cc_skip_protected_game")
                .build());
        out.add(b("smart_clear")
                .n("cc_skip_athena_white", 259)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", -1)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_navigating", "cc_skip_downloading",
                        "cc_skip_perceptible", "cc_skip_persistent", "cc_skip_background_protect",
                        "cc_skip_protected_game")
                .f("cc_skip_appmanager", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_foreground_service")
                .build());
        out.add(b("ai_battery_adaptive_clear")
                .n("cc_skip_athena_white", 8193)
                .n("cc_skip_athena_white_new", 18939904)
                .n("cc_skip_recent_used_num", 5)
                .t("cc_skip_recent_lock", "cc_skip_super_alive_game", "cc_skip_inputmethod",
                        "cc_skip_foreground", "cc_skip_live_wallpaper", "cc_skip_bluetooth",
                        "cc_skip_widget", "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn",
                        "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_skip_persistent",
                        "cc_skip_appmanager", "cc_skip_preload_protect",
                        "cc_skip_foreground_service", "cc_skip_accessibility_service")
                .f("cc_do_remove_task", "cc_do_show_toast")
                .build());
        out.add(b("swipeup_forcestop_clear")
                .n("cc_skip_athena_white", 512)
                .n("cc_skip_athena_white_new", 262144)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_inputmethod", "cc_skip_live_wallpaper", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_persistent",
                        "cc_do_clear_trash")
                .f("cc_skip_recent_lock", "cc_skip_super_alive_game", "cc_skip_foreground",
                        "cc_skip_bluetooth", "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio",
                        "cc_skip_navigating", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_skip_appmanager", "cc_do_remove_task", "cc_do_show_toast",
                        "cc_skip_background_protect", "cc_skip_foreground_service")
                .build());
    }

    private static B deep(String name) {
        return b(name)
                .n("cc_skip_athena_white", 4)
                .n("cc_skip_athena_white_new", 131072)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_persistent", "cc_skip_background_protect",
                        "cc_skip_protected_game", "cc_do_clear_trash")
                .f("cc_skip_navigating", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_skip_appmanager", "cc_do_remove_task", "cc_do_show_toast",
                        "cc_skip_foreground_service");
    }

    private static void addOneKey(ArrayList<ClearScene> out) {
        out.add(b("one_key#config_1")
                .c("com.oplus.childrenspace", "com.oplus.battery.appspowerissue")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_widget", "cc_skip_home",
                        "cc_skip_keyguard", "cc_skip_persistent", "cc_skip_appmanager",
                        "cc_skip_background_protect")
                .f("cc_skip_bluetooth", "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio",
                        "cc_skip_navigating", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_do_remove_task", "cc_do_clear_trash", "cc_do_show_toast",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_2")
                .c("com.coloros.recents", "com.oplus.recents")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_home", "cc_skip_keyguard",
                        "cc_skip_pip", "cc_skip_persistent", "cc_skip_appmanager",
                        "cc_do_remove_task", "cc_do_show_toast", "cc_skip_protected_game",
                        "cc_revival_protect")
                .f("cc_skip_bluetooth", "cc_skip_widget", "cc_skip_vpn", "cc_skip_audio",
                        "cc_skip_navigating", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_do_clear_trash", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_3")
                .c("com.coloros.athena.scroffclear", "com.coloros.athena.lightclear",
                        "com.coloros.gamespace", "com.oplus.athena.scroffclear",
                        "com.oplus.athena.lightclear", "com.oplus.games")
                .n("cc_skip_athena_white", 3)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", -1)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_navigating", "cc_skip_downloading",
                        "cc_skip_persistent", "cc_skip_appmanager", "cc_skip_background_protect",
                        "cc_skip_protected_game")
                .f("cc_skip_perceptible", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_4")
                .c("com.coloros.findmyphone", "com.oplus.findmyphone",
                        "com.coloros.speechassist", "com.heytap.speechassist",
                        "com.oplus.daydreamvideo.scroffclear")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_widget", "cc_skip_home",
                        "cc_skip_keyguard", "cc_skip_pip", "cc_skip_persistent",
                        "cc_skip_appmanager", "cc_do_remove_task")
                .f("cc_skip_bluetooth", "cc_skip_vpn", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_5")
                .c("com.coloros.oppoguardelf.lowmem", "com.oplus.battery.lowmem")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 3)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_widget", "cc_skip_home",
                        "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip", "cc_skip_persistent",
                        "cc_skip_background_protect")
                .f("cc_skip_bluetooth", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_skip_appmanager",
                        "cc_do_remove_task", "cc_do_clear_trash", "cc_do_show_toast",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_6")
                .c("com.coloros.oppoguardelf.hightemperature",
                        "com.coloros.oppoguardelf.thermalcontrol_highlevel",
                        "com.oplus.battery.hightemperature",
                        "com.oplus.battery.thermalcontrol_highlevel")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_inputmethod", "cc_skip_home", "cc_skip_keyguard",
                        "cc_skip_persistent")
                .f("cc_skip_recent_lock", "cc_skip_super_alive_game", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_skip_appmanager",
                        "cc_do_remove_task", "cc_do_clear_trash", "cc_do_show_toast",
                        "cc_skip_background_protect", "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_7")
                .c("com.coloros.oppoguardelf.highperform", "com.oplus.battery.highperform")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_inputmethod", "cc_skip_foreground", "cc_skip_live_wallpaper",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_pip", "cc_skip_persistent",
                        "cc_skip_appmanager", "cc_do_remove_task")
                .f("cc_skip_recent_lock", "cc_skip_super_alive_game", "cc_skip_bluetooth",
                        "cc_skip_widget", "cc_skip_vpn", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_8")
                .c("com.coloros.safecenter", "com.oplus.safecenter")
                .n("cc_skip_athena_white", 2048)
                .n("cc_skip_athena_white_new", 524288)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_inputmethod", "cc_skip_live_wallpaper", "cc_skip_bluetooth",
                        "cc_skip_persistent", "cc_skip_appmanager")
                .f("cc_skip_recent_lock", "cc_skip_super_alive_game", "cc_skip_foreground",
                        "cc_skip_widget", "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn",
                        "cc_skip_pip", "cc_skip_audio", "cc_skip_downloading", "cc_skip_backup",
                        "cc_skip_perceptible", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_preload_protect", "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_9")
                .c("com.coloros.athena.supersavingpowerscroffclear",
                        "com.oplus.athena.supersavingpowerscroffclear")
                .n("cc_skip_athena_white", 4096)
                .n("cc_skip_athena_white_new", 1048576)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_inputmethod", "cc_skip_foreground", "cc_skip_live_wallpaper",
                        "cc_skip_bluetooth", "cc_skip_persistent", "cc_skip_appmanager")
                .f("cc_skip_recent_lock", "cc_skip_super_alive_game", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_downloading", "cc_skip_backup",
                        "cc_skip_perceptible", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_preload_protect", "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_10")
                .c("com.oppo.camera", "com.oneplus.camera", "com.oplus.camera")
                .n("cc_skip_athena_white", 259)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 3)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_navigating", "cc_skip_downloading",
                        "cc_skip_persistent", "cc_skip_appmanager")
                .f("cc_skip_perceptible", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_11")
                .c("com.coloros.athena.bootclear", "com.oplus.athena.bootclear")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", -1)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_home",
                        "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio",
                        "cc_skip_navigating", "cc_skip_persistent", "cc_skip_appmanager")
                .f("cc_skip_widget", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_do_remove_task", "cc_do_clear_trash", "cc_do_show_toast",
                        "cc_skip_background_protect", "cc_skip_foreground_service",
                        "cc_skip_protected_game")
                .build());
        out.add(b("one_key#config_12")
                .c("com.oplus.battery.powersavemode")
                .n("cc_skip_athena_white", 131073)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", -1)
                .n("cc_skip_high_priority_count", -1)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_home", "cc_skip_keyguard",
                        "cc_skip_bluetooth", "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio",
                        "cc_skip_navigating", "cc_skip_persistent", "cc_skip_appmanager")
                .f("cc_skip_widget", "cc_skip_downloading", "cc_skip_backup",
                        "cc_skip_perceptible", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service", "cc_skip_protected_game")
                .build());
        out.add(b("one_key#config_13")
                .c("com.oplus.themestore", "com.oplus.ipspace")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_navigating", "cc_skip_downloading",
                        "cc_skip_persistent", "cc_skip_appmanager", "cc_do_remove_task",
                        "cc_do_show_toast", "cc_skip_protected_game")
                .f("cc_skip_perceptible", "cc_do_clear_trash", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_14")
                .c("com.coloros.phonemanager", "com.oplus.phonemanager")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_widget", "cc_skip_home",
                        "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio",
                        "cc_skip_navigating", "cc_skip_downloading", "cc_skip_persistent",
                        "cc_skip_appmanager", "cc_skip_background_protect")
                .f("cc_skip_bluetooth", "cc_skip_perceptible", "cc_do_remove_task",
                        "cc_do_clear_trash", "cc_do_show_toast", "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_15")
                .c("com.oplus.games.tournamentmode")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_widget", "cc_skip_home",
                        "cc_skip_keyguard", "cc_skip_pip", "cc_skip_persistent",
                        "cc_skip_appmanager", "cc_do_remove_task", "cc_skip_protected_game")
                .f("cc_skip_bluetooth", "cc_skip_vpn", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_16")
                .c("com.oplus.battery.safety.hightemperature")
                .n("cc_skip_athena_white", 0)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_inputmethod", "cc_skip_home", "cc_skip_keyguard",
                        "cc_skip_persistent", "cc_do_remove_task")
                .f("cc_skip_recent_lock", "cc_skip_super_alive_game", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_skip_appmanager",
                        "cc_do_clear_trash", "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_17")
                .c("com.coloros.phonemanager.systemopt", "com.oplus.phonemanager.systemopt")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_widget", "cc_skip_home",
                        "cc_skip_keyguard", "cc_skip_pip", "cc_skip_persistent",
                        "cc_skip_appmanager", "cc_do_remove_task", "cc_skip_protected_game")
                .f("cc_skip_bluetooth", "cc_skip_vpn", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("one_key#config_18")
                .c("com.oplus.phonemanager.onekeyrenew", "com.coloros.phonemanager.onekeyrenew")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_home", "cc_skip_keyguard",
                        "cc_skip_pip", "cc_skip_persistent", "cc_skip_appmanager",
                        "cc_do_remove_task", "cc_skip_protected_game")
                .f("cc_skip_bluetooth", "cc_skip_widget", "cc_skip_vpn", "cc_skip_audio",
                        "cc_skip_navigating", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_do_clear_trash", "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build());
    }

    private static void addAbnormal(ArrayList<ClearScene> out) {
        out.add(b("abnormal_memory_clear")
                .n("cc_skip_athena_white", 0)
                .n("cc_skip_recent_used_num", 4)
                .n("cc_skip_recent_used_time", 5)
                .t("cc_skip_foreground", "cc_skip_audio", "cc_skip_visible_window")
                .f("cc_skip_recent_lock", "cc_skip_super_alive_game", "cc_skip_inputmethod",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_navigating", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_skip_persistent", "cc_skip_appmanager", "cc_do_remove_task",
                        "cc_do_clear_trash", "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_preload_protect", "cc_skip_foreground_service")
                .build());
        out.add(b("abnormal_memory_periodic_clear")
                .n("cc_skip_athena_white", 0)
                .n("cc_skip_recent_used_num", 4)
                .n("cc_skip_recent_used_time", 5)
                .t("cc_skip_foreground", "cc_skip_bluetooth", "cc_skip_home", "cc_skip_vpn",
                        "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating", "cc_skip_native",
                        "cc_skip_system", "cc_skip_visible_window")
                .f("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_live_wallpaper",
                        "cc_skip_widget", "cc_skip_keyguard", "cc_skip_downloading",
                        "cc_skip_perceptible", "cc_skip_persistent", "cc_skip_appmanager",
                        "cc_skip_backup", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_preload_protect", "cc_skip_foreground_service")
                .build());
        out.add(b("abnormal_memory_clear_config_1")
                .n("cc_skip_athena_white", 0)
                .n("cc_skip_recent_used_num", 3)
                .n("cc_skip_recent_used_time", 0)
                .t("cc_skip_foreground", "cc_skip_audio", "cc_skip_visible_window")
                .f("cc_skip_recent_lock", "cc_skip_super_alive_game", "cc_skip_inputmethod",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_navigating", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_skip_persistent", "cc_skip_appmanager", "cc_do_remove_task",
                        "cc_do_clear_trash", "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_preload_protect", "cc_skip_foreground_service")
                .build());
        out.add(b("abnormal_memory_periodic_clear_config_1")
                .n("cc_skip_athena_white", 0)
                .n("cc_skip_recent_used_num", 3)
                .n("cc_skip_recent_used_time", 0)
                .t("cc_skip_foreground", "cc_skip_bluetooth", "cc_skip_home", "cc_skip_vpn",
                        "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating", "cc_skip_native",
                        "cc_skip_system", "cc_skip_visible_window")
                .f("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_live_wallpaper",
                        "cc_skip_widget", "cc_skip_keyguard", "cc_skip_downloading",
                        "cc_skip_perceptible", "cc_skip_persistent", "cc_skip_appmanager",
                        "cc_skip_backup", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_preload_protect", "cc_skip_foreground_service")
                .build());
        out.add(b("screen_off_clear_excessive_mem_app")
                .n("cc_skip_athena_white", 0)
                .n("cc_skip_recent_used_num", 1)
                .t("cc_skip_foreground", "cc_skip_bluetooth", "cc_skip_home", "cc_skip_vpn",
                        "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating", "cc_skip_native",
                        "cc_skip_system")
                .f("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_live_wallpaper",
                        "cc_skip_widget", "cc_skip_keyguard", "cc_skip_downloading",
                        "cc_skip_perceptible", "cc_skip_persistent", "cc_skip_appmanager",
                        "cc_skip_backup", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_preload_protect", "cc_skip_foreground_service")
                .build());
        out.add(b("memory_guard")
                .n("cc_skip_athena_white", 8451)
                .n("cc_skip_athena_white_new", 2162688)
                .n("cc_skip_recent_used_num", -1)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_home",
                        "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio",
                        "cc_skip_navigating", "cc_skip_persistent", "cc_skip_appmanager")
                .f("cc_skip_widget", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_do_remove_task", "cc_do_clear_trash", "cc_do_show_toast",
                        "cc_skip_background_protect", "cc_skip_preload_protect",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("memory_guard_2")
                .n("cc_skip_athena_white", 8192)
                .n("cc_skip_athena_white_new", 2097152)
                .n("cc_skip_recent_used_num", 0)
                .t("cc_skip_inputmethod", "cc_skip_foreground", "cc_skip_live_wallpaper",
                        "cc_skip_home", "cc_skip_navigating", "cc_skip_persistent",
                        "cc_skip_appmanager", "cc_skip_accessibility_service")
                .f("cc_skip_recent_lock", "cc_skip_super_alive_game", "cc_skip_bluetooth",
                        "cc_skip_widget", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_do_remove_task", "cc_do_clear_trash", "cc_do_show_toast",
                        "cc_skip_background_protect", "cc_skip_preload_protect",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("quick_bootup_clear")
                .n("cc_skip_athena_white", 1)
                .n("cc_skip_athena_white_new", 65536)
                .n("cc_skip_recent_used_num", 1)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_home",
                        "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio",
                        "cc_skip_persistent", "cc_skip_appmanager", "cc_skip_protected_game")
                .f("cc_skip_widget", "cc_skip_navigating", "cc_skip_downloading",
                        "cc_skip_perceptible", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build());
        out.add(b("clear_spec_common")
                .n("cc_skip_athena_white", 0)
                .n("cc_skip_recent_used_num", 8)
                .n("cc_skip_system_process_max_adj", 200)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_navigating", "cc_skip_downloading",
                        "cc_skip_persistent", "cc_skip_appmanager", "cc_skip_protected_game",
                        "cc_skip_important_app")
                .f("cc_skip_perceptible", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build());
    }

    private static void addSpec(ArrayList<ClearScene> out) {
        out.add(spec("clear_spec#android.preload_kill.forcestop",
                "android.preload_kill.forcestop")
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", 200)
                .t("cc_skip_recent_lock", "cc_skip_foreground", "cc_skip_persistent",
                        "cc_skip_appmanager")
                .f("cc_skip_inputmethod", "cc_skip_live_wallpaper", "cc_skip_bluetooth",
                        "cc_skip_widget", "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn",
                        "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_do_remove_task",
                        "cc_do_clear_trash", "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service", "cc_skip_protected_game",
                        "cc_skip_important_app")
                .build());
        out.add(spec("clear_spec#clear_spec_all_kill_common", "android.ams.provider")
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", -10001)
                .t("cc_skip_foreground", "cc_skip_persistent")
                .f("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_live_wallpaper",
                        "cc_skip_bluetooth", "cc_skip_widget", "cc_skip_home", "cc_skip_keyguard",
                        "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_backup", "cc_skip_perceptible",
                        "cc_skip_appmanager", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service", "cc_skip_protected_game",
                        "cc_skip_important_app")
                .build());
        out.add(spec("clear_spec#config_1", "android.oguard.kill")
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", 200)
                .t("cc_skip_foreground", "cc_skip_audio", "cc_skip_persistent")
                .f("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_live_wallpaper",
                        "cc_skip_bluetooth", "cc_skip_widget", "cc_skip_home", "cc_skip_keyguard",
                        "cc_skip_vpn", "cc_skip_pip", "cc_skip_navigating", "cc_skip_downloading",
                        "cc_skip_backup", "cc_skip_perceptible", "cc_skip_appmanager",
                        "cc_do_remove_task", "cc_do_clear_trash", "cc_do_show_toast",
                        "cc_skip_background_protect", "cc_skip_foreground_service",
                        "cc_skip_protected_game", "cc_skip_important_app")
                .build());
        out.add(spec("clear_spec#config_1", "android.oguard.abnormalAudio.kill")
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", 99)
                .t("cc_skip_recent_lock", "cc_skip_foreground", "cc_skip_persistent")
                .f("cc_skip_inputmethod", "cc_skip_live_wallpaper", "cc_skip_bluetooth",
                        "cc_skip_widget", "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn",
                        "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_backup", "cc_skip_perceptible",
                        "cc_skip_appmanager", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service", "cc_skip_protected_game",
                        "cc_skip_important_app")
                .build());
        out.add(spec("clear_spec#config_1", "android.oguard.gameInFocus.kill")
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", 100)
                .t("cc_skip_recent_lock", "cc_skip_foreground", "cc_skip_audio",
                        "cc_skip_persistent")
                .f("cc_skip_inputmethod", "cc_skip_live_wallpaper", "cc_skip_bluetooth",
                        "cc_skip_widget", "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn",
                        "cc_skip_pip", "cc_skip_navigating", "cc_skip_downloading",
                        "cc_skip_backup", "cc_skip_perceptible", "cc_skip_appmanager",
                        "cc_do_remove_task", "cc_do_clear_trash", "cc_do_show_toast",
                        "cc_skip_background_protect", "cc_skip_foreground_service",
                        "cc_skip_protected_game", "cc_skip_important_app")
                .build());
        out.add(spec("clear_spec#com.oplus.battery.safety.hightemperature",
                "com.oplus.battery.safety.hightemperature")
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", 200)
                .t("cc_skip_persistent", "cc_skip_appmanager")
                .f("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_navigating", "cc_skip_downloading",
                        "cc_skip_perceptible", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service", "cc_skip_protected_game",
                        "cc_skip_important_app")
                .build());
        out.add(shortcutSpec("com.coloros.shortcuts"));
        out.add(shortcutSpec("com.coloros.shortcuts.force_stop"));
        out.add(bbds("android.bbds.thread_kill"));
        out.add(bbds("android.bbds.lowmem_kill"));
        out.add(b("clear_spec#config_com.coloros.remoteguardservice")
                .c("com.coloros.remoteguardservice", "com.coloros.remoteguardservice_stop")
                .n("cc_skip_athena_white", 0)
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", 200)
                .t("cc_skip_keyguard")
                .f("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio",
                        "cc_skip_navigating", "cc_skip_downloading", "cc_skip_perceptible",
                        "cc_skip_persistent", "cc_skip_appmanager", "cc_do_remove_task",
                        "cc_do_clear_trash", "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service", "cc_skip_protected_game",
                        "cc_skip_important_app")
                .build());
        out.add(spec("clear_spec#config_revival.app.control", "revival.slot.app.kill")
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", 0)
                .t("cc_skip_foreground")
                .f("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_live_wallpaper",
                        "cc_skip_bluetooth", "cc_skip_widget", "cc_skip_home", "cc_skip_keyguard",
                        "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_skip_persistent",
                        "cc_skip_appmanager", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service", "cc_skip_protected_game",
                        "cc_skip_important_app")
                .build());
        out.add(spec("clear_spec#config_com.oplus.atlas", "com.oplus.atlas")
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", 200)
                .t("cc_skip_foreground", "cc_skip_persistent")
                .f("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_live_wallpaper",
                        "cc_skip_bluetooth", "cc_skip_widget", "cc_skip_home", "cc_skip_keyguard",
                        "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_skip_appmanager",
                        "cc_do_remove_task", "cc_do_clear_trash", "cc_do_show_toast",
                        "cc_skip_background_protect", "cc_skip_foreground_service",
                        "cc_skip_protected_game", "cc_skip_important_app")
                .build());
        out.add(spec("clear_spec#config_com.oplus.mediaturbo.service",
                "com.oplus.mediaturbo.service")
                .n("cc_skip_recent_used_num", 5)
                .n("cc_skip_system_process_max_adj", 0)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_pip", "cc_skip_audio",
                        "cc_skip_downloading")
                .f("cc_skip_bluetooth", "cc_skip_widget", "cc_skip_home", "cc_skip_keyguard",
                        "cc_skip_vpn", "cc_skip_navigating", "cc_skip_perceptible",
                        "cc_skip_persistent", "cc_skip_appmanager", "cc_do_remove_task",
                        "cc_do_clear_trash", "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service", "cc_skip_protected_game",
                        "cc_skip_important_app")
                .build());
        out.add(spec("clear_spec#config_com.coloros.phonemanager",
                "com.coloros.phonemanager.networkdetect")
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", 0)
                .t("cc_skip_inputmethod", "cc_skip_foreground", "cc_skip_live_wallpaper",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_persistent")
                .f("cc_skip_recent_lock", "cc_skip_widget", "cc_skip_bluetooth", "cc_skip_vpn",
                        "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_skip_appmanager",
                        "cc_do_remove_task", "cc_do_clear_trash", "cc_do_show_toast",
                        "cc_skip_background_protect", "cc_skip_foreground_service")
                .build());
        out.add(spec("clear_spec#config_com.oplus.games", "com.oplus.games.exit_game")
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", 0)
                .t("cc_skip_foreground", "cc_do_remove_task")
                .f("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_live_wallpaper",
                        "cc_skip_bluetooth", "cc_skip_widget", "cc_skip_home", "cc_skip_keyguard",
                        "cc_skip_vpn", "cc_skip_pip", "cc_skip_audio", "cc_skip_navigating",
                        "cc_skip_downloading", "cc_skip_perceptible", "cc_skip_persistent",
                        "cc_skip_appmanager", "cc_do_clear_trash", "cc_do_show_toast",
                        "cc_skip_background_protect", "cc_skip_foreground_service",
                        "cc_skip_protected_game", "cc_skip_important_app")
                .build());
        out.add(b("clear_spec#config_com.oplus.camera")
                .c("com.oplus.camera.fallback.kill", "com.oplus.camera.fallback.kill.uid",
                        "com.oplus.camera.fallback.kill.pid",
                        "com.oplus.camera.fallback.kill.forcestop")
                .n("cc_skip_athena_white", 0)
                .n("cc_skip_recent_used_num", 3)
                .n("cc_skip_system_process_max_adj", 99)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_navigating", "cc_skip_persistent",
                        "cc_skip_appmanager", "cc_skip_protected_game", "cc_skip_important_app")
                .f("cc_skip_downloading", "cc_skip_perceptible", "cc_do_remove_task",
                        "cc_do_clear_trash", "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build());
    }

    private static ClearScene shortcutSpec(String caller) {
        return spec(caller.endsWith("force_stop")
                        ? "clear_spec#config_com.coloros.shortcuts.force_stop"
                        : "clear_spec#config_com.coloros.shortcuts", caller)
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", 200)
                .t("cc_do_remove_task")
                .f("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_navigating", "cc_skip_downloading",
                        "cc_skip_backup", "cc_skip_perceptible", "cc_skip_persistent",
                        "cc_skip_appmanager", "cc_do_clear_trash", "cc_do_show_toast",
                        "cc_skip_background_protect", "cc_skip_foreground_service",
                        "cc_skip_protected_game", "cc_skip_important_app")
                .build();
    }

    private static ClearScene bbds(String caller) {
        return spec("clear_spec#config_android.bbds", caller)
                .n("cc_skip_recent_used_num", 0)
                .n("cc_skip_system_process_max_adj", 200)
                .t("cc_skip_recent_lock", "cc_skip_inputmethod", "cc_skip_foreground",
                        "cc_skip_live_wallpaper", "cc_skip_bluetooth", "cc_skip_widget",
                        "cc_skip_home", "cc_skip_keyguard", "cc_skip_vpn", "cc_skip_pip",
                        "cc_skip_audio", "cc_skip_navigating", "cc_skip_downloading",
                        "cc_skip_persistent", "cc_skip_appmanager", "cc_skip_protected_game",
                        "cc_skip_important_app")
                .f("cc_skip_perceptible", "cc_do_remove_task", "cc_do_clear_trash",
                        "cc_do_show_toast", "cc_skip_background_protect",
                        "cc_skip_foreground_service")
                .build();
    }

    private static B spec(String name, String caller) {
        return b(name).c(caller).n("cc_skip_athena_white", 0);
    }

    private static B b(String name) {
        return new B(name);
    }

    private static final class B {
        final String name;
        final ArrayList<String> callers = new ArrayList<>();
        final ArrayMap<String, String> values = new ArrayMap<>();

        B(String name) {
            this.name = name;
        }

        B c(String... callers) {
            for (int i = 0; i < callers.length; i++) {
                this.callers.add(callers[i]);
            }
            return this;
        }

        B t(String... keys) {
            for (int i = 0; i < keys.length; i++) {
                values.put(keys[i], "true");
            }
            return this;
        }

        B f(String... keys) {
            for (int i = 0; i < keys.length; i++) {
                values.put(keys[i], "false");
            }
            return this;
        }

        B n(String key, int value) {
            values.put(key, Integer.toString(value));
            return this;
        }

        ClearScene build() {
            return new ClearScene(name, callers, values);
        }
    }
}
