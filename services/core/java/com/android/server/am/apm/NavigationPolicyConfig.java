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
import android.util.ArraySet;

/**
 * Immutable navigation policy. Callers swap a whole instance.
 *
 * The allowlist holds packages whose GNSS use may be counted as navigation when they are
 * also freshly top. The default is the navigation app type from the ColorOS image
 * ({@code sys_hans_hardcoded_app_type_list.xml}, 高德 and 百度 are type 11). Nothing is
 * guessed beyond that file: an allowlist hit still needs live GNSS, and a location
 * foreground service confirms on its own without any list.
 *
 * The denylist is empty: the image classifies packages by app type, it does not ship a
 * package level navigation denylist, and this port does not invent one.
 */
public final class NavigationPolicyConfig {
    public final boolean enabled;
    public final long enterDebounceMs;
    public final long exitGraceMs;
    public final long recentTopMs;
    public final boolean adjClampEnabled;
    public final int adjClamp;
    public final ArraySet<String> allowlist;
    public final ArraySet<String> denylist;

    public NavigationPolicyConfig(boolean enabled, long enterDebounceMs, long exitGraceMs,
            long recentTopMs, boolean adjClampEnabled, int adjClamp, ArraySet<String> allowlist,
            ArraySet<String> denylist) {
        this.enabled = enabled;
        this.enterDebounceMs = enterDebounceMs;
        this.exitGraceMs = exitGraceMs;
        this.recentTopMs = recentTopMs;
        this.adjClampEnabled = adjClampEnabled;
        this.adjClamp = adjClamp;
        this.allowlist = allowlist;
        this.denylist = denylist;
    }

    public static NavigationPolicyConfig defaults() {
        return new NavigationPolicyConfig(
                ApmConstants.DEFAULT_NAVIGATION_ENABLED,
                ApmConstants.DEFAULT_NAVIGATION_ENTER_DEBOUNCE_MS,
                ApmConstants.DEFAULT_NAVIGATION_EXIT_GRACE_MS,
                ApmConstants.DEFAULT_NAVIGATION_RECENT_TOP_MS,
                ApmConstants.DEFAULT_NAVIGATION_ADJ_CLAMP_ENABLED,
                ApmConstants.DEFAULT_NAVIGATION_ADJ_CLAMP,
                defaultAllowlist(),
                new ArraySet<>());
    }

    static ArraySet<String> defaultAllowlist() {
        final ArraySet<String> set = new ArraySet<>();
        for (int i = 0; i < ApmConstants.NAVIGATION_ALLOWLIST.length; i++) {
            set.add(ApmConstants.NAVIGATION_ALLOWLIST[i]);
        }
        return set;
    }

    public NavigationPolicyConfig withEnabled(boolean value) {
        return new NavigationPolicyConfig(value, enterDebounceMs, exitGraceMs, recentTopMs,
                adjClampEnabled, adjClamp, allowlist, denylist);
    }

    public NavigationPolicyConfig withAdjClampEnabled(boolean value) {
        return new NavigationPolicyConfig(enabled, enterDebounceMs, exitGraceMs, recentTopMs,
                value, adjClamp, allowlist, denylist);
    }

    public NavigationPolicyConfig withAdjClamp(int value) {
        return new NavigationPolicyConfig(enabled, enterDebounceMs, exitGraceMs, recentTopMs,
                adjClampEnabled, value, allowlist, denylist);
    }

    public NavigationPolicyConfig withAllowlist(ArraySet<String> value) {
        return new NavigationPolicyConfig(enabled, enterDebounceMs, exitGraceMs, recentTopMs,
                adjClampEnabled, adjClamp, value, denylist);
    }

    public NavigationPolicyConfig withDenylist(ArraySet<String> value) {
        return new NavigationPolicyConfig(enabled, enterDebounceMs, exitGraceMs, recentTopMs,
                adjClampEnabled, adjClamp, allowlist, value);
    }

    public boolean isAllowed(@Nullable String packageName) {
        return packageName != null && allowlist.contains(packageName);
    }

    public boolean isDenied(@Nullable String packageName) {
        return packageName != null && denylist.contains(packageName);
    }
}
