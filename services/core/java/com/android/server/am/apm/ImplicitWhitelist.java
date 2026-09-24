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
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The packages that hold every {@link ApmWhitelist} column without a row of their own.
 *
 * <p>Two kinds, both of which the device keeps alive and the manager must not freeze, kill,
 * defer or refuse to start:
 *
 * <ul>
 *   <li>every pre-installed app: system, system_ext, product, vendor and odm. The user cannot
 *       take them off the ROM, so the manager does not manage them either.</li>
 *   <li>a KernelSU manager. KernelSU and KernelSU-Next both label themselves "KernelSU...",
 *       and a repackaged fork keeps that label while changing the package name, so the
 *       package name, the label and the APK file name are all matched.</li>
 * </ul>
 *
 * <p>The merge happens where the table is read, {@link ApmWhitelistTable#read}, so the
 * protection snapshot the service publishes, the auto-start allow list and the dumps all see
 * one table. Nothing is written back: the Settings row keeps only the user's own entries, and
 * a package that is implicit cannot be managed out of the list, because its row is not what
 * puts it there.
 */
final class ImplicitWhitelist {
    private static final String TAG = "Apm";

    /** A package name, label or APK name holding one of these is a KernelSU manager. */
    private static final String[] MANAGER_TOKENS = {"kernelsu", "ksunext"};

    private ImplicitWhitelist() {}

    /** @return true when {@code value} names a KernelSU manager. Case insensitive. */
    static boolean matches(@Nullable String value) {
        if (value == null) {
            return false;
        }
        final String lower = value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < MANAGER_TOKENS.length; i++) {
            if (lower.contains(MANAGER_TOKENS[i])) {
                return true;
            }
        }
        return false;
    }

    /** Pre-installed on the system, system_ext, product, vendor or odm partition. */
    private static boolean isPreinstalled(@Nullable ApplicationInfo info) {
        return info != null
                && (info.isSystemApp() || info.isSystemExt() || info.isProduct() || info.isVendor()
                        || (info.privateFlags & ApplicationInfo.PRIVATE_FLAG_ODM) != 0);
    }

    /**
     * @return the packages that hold every column, or an empty set when the package manager is
     *         not up yet or its copy of the package list cannot be read. An empty set never
     *         removes a column from a package that the user did whitelist.
     */
    static ArraySet<String> packages(@Nullable Context context) {
        final ArraySet<String> implicit = new ArraySet<>();
        if (context == null) {
            return implicit;
        }
        final PackageManager packageManager = context.getPackageManager();
        final List<ApplicationInfo> installed;
        try {
            // Every user's packages, not only the one the table is being read for: the table
            // is per user but a pre-installed app or a KernelSU manager is the same package
            // whichever user's row is being built.
            installed = packageManager.getInstalledApplicationsAsUser(
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, UserHandle.USER_SYSTEM);
        } catch (Throwable t) {
            Slog.w(TAG, "implicit whitelist read failed", t);
            return implicit;
        }
        for (int i = 0; i < installed.size(); i++) {
            final ApplicationInfo info = installed.get(i);
            if (info == null || info.packageName == null) {
                continue;
            }
            // The label is only read for a package that is not pre-installed, which is the
            // smaller half of the list and the only half a repackaged manager can be in.
            if (isPreinstalled(info) || matches(info.packageName) || matches(info.sourceDir)
                    || matches(info.loadLabel(packageManager).toString())) {
                implicit.add(info.packageName);
            }
        }
        return implicit;
    }

    /**
     * {@code table} plus every implicit package at {@link ApmWhitelist#BITS_ALL}, or the table
     * itself when there is nothing to add.
     */
    static Map<String, Integer> merge(@Nullable Context context, Map<String, Integer> table) {
        final ArraySet<String> implicit = packages(context);
        if (implicit.isEmpty()) {
            return table;
        }
        final Map<String, Integer> merged = new HashMap<>(table.size() + implicit.size());
        merged.putAll(table);
        for (int i = 0; i < implicit.size(); i++) {
            merged.put(implicit.valueAt(i), ApmWhitelist.BITS_ALL);
        }
        return merged;
    }
}
