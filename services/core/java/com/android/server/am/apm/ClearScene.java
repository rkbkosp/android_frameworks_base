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
 * One {@code clear_config} block. Keys that the XML omitted stay absent.
 * A true {@code cc_skip_*} applies only to this scene.
 */
public final class ClearScene {
    public static final String SKIP_ATHENA_WHITE = "cc_skip_athena_white";
    public static final String SKIP_ATHENA_WHITE_NEW = "cc_skip_athena_white_new";
    public static final String SKIP_RECENT_USED_NUM = "cc_skip_recent_used_num";
    public static final String SKIP_RECENT_USED_TIME = "cc_skip_recent_used_time";
    public static final String SKIP_RECENT_LOCK = "cc_skip_recent_lock";
    public static final String SKIP_SYSTEM_MAX_ADJ = "cc_skip_system_process_max_adj";
    public static final String SKIP_HIGH_PRIORITY_COUNT = "cc_skip_high_priority_count";
    public static final String DO_REMOVE_TASK = "cc_do_remove_task";
    public static final String DO_CLEAR_TRASH = "cc_do_clear_trash";
    public static final String DO_SHOW_TOAST = "cc_do_show_toast";
    public static final String REVIVAL_PROTECT = "cc_revival_protect";

    public final String name;
    public final List<String> callers;
    private final ArrayMap<String, String> mValues;

    ClearScene(String name, List<String> callers, ArrayMap<String, String> values) {
        this.name = name;
        this.callers = Collections.unmodifiableList(new ArrayList<>(callers));
        mValues = new ArrayMap<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            mValues.put(values.keyAt(i), values.valueAt(i));
        }
    }

    public boolean has(String key) {
        return mValues.containsKey(key);
    }

    /** True only when the key is present and its text is {@code true}. */
    public boolean flag(String key) {
        return "true".equals(mValues.get(key));
    }

    public int number(String key, int absent) {
        final String raw = mValues.get(key);
        if (raw == null) {
            return absent;
        }
        try {
            if (raw.startsWith("0x") || raw.startsWith("0X")) {
                return Integer.parseInt(raw.substring(2), 16);
            }
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return absent;
        }
    }
}
