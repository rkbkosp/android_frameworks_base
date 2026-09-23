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

import android.annotation.Nullable;
import android.apm.ApmWhitelist;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Slog;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The per user whitelist table, {@code Settings.Secure.apm_whitelist}, as the adaptive
 * process manager uses it. {@link ApmWhitelist} owns the encoding; this is only the read and
 * write side of it.
 *
 * <p>One instance is shared by the service, which turns a row into a protection policy, and
 * by {@link AutoStartPolicy}, which reads the AUTO_START column as its allow list. Every
 * call runs on the APM service thread. No gate reads the table: a gate reads the snapshot
 * each reader publishes.
 */
interface ApmWhitelistTable {
    String TAG = "Apm";

    /** Alive user ids. The service's own user list: the table is per user. */
    int[] userIds();

    /**
     * One user's table.
     *
     * @return package to bits, or null when the value could not be read. The two are not the
     *         same answer: an unreadable table leaves the previous state in place, while an
     *         empty one really has no entries.
     */
    @Nullable
    Map<String, Integer> read(int userId);

    /**
     * Replaces one package's row for one user, or removes it when {@code bits} is 0.
     *
     * @return null when the write was accepted, else a one line shell error.
     */
    @Nullable
    String write(int userId, String packageName, int bits);

    /**
     * {@code Settings.Secure} backed table. {@code userIds} is the service's own user list,
     * so the two readers cannot disagree about which users exist.
     */
    static ApmWhitelistTable secure(@Nullable Context context, Supplier<int[]> userIds) {
        final ContentResolver resolver = context == null ? null : context.getContentResolver();
        return new ApmWhitelistTable() {
            @Override
            public int[] userIds() {
                return userIds.get();
            }

            @Override
            @Nullable
            public Map<String, Integer> read(int userId) {
                if (resolver == null) {
                    return Collections.emptyMap();
                }
                try {
                    return ApmWhitelist.parse(Settings.Secure.getStringForUser(resolver,
                            ApmWhitelist.SETTING, userId));
                } catch (Throwable t) {
                    Slog.w(TAG, "whitelist read failed for user " + userId, t);
                    return null;
                }
            }

            @Override
            @Nullable
            public String write(int userId, String packageName, int bits) {
                if (resolver == null) {
                    return "Error: settings are not available";
                }
                // ApmWhitelist reads the table, replaces the one row, and writes it back. A
                // rejected write is logged there and leaves the table as it was.
                ApmWhitelist.setBits(resolver, userId, packageName, bits);
                return null;
            }
        };
    }
}
