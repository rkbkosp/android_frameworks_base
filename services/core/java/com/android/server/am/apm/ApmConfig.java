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
 * Immutable policy configuration. Callers swap a whole instance; they do not mutate fields.
 */
public final class ApmConfig {
    public final boolean enabled;
    public final boolean shadowMode;
    public final boolean freezerEnabled;
    public final boolean memoryEnabled;
    public final long freezeDelayMs;
    public final long bigAppFreezeDelayMs;
    public final int churnLimit60s;
    public final long churnCooldownMs;
    /** Owned by {@link NetworkFreezeController}: switches, delays, and the debug uid list. */
    public final boolean networkFreezeEnabled;
    public final boolean netForceSocketDestroy;
    public final long netFreezeDelayMs;
    public final long netFreezeDelayGameMs;
    /** Always non-null. Parsed once from {@code apm_net_relax_uid_list}. */
    public final int[] netRelaxUids;
    public final int generation;

    public ApmConfig(boolean enabled, boolean shadowMode, boolean freezerEnabled,
            boolean memoryEnabled, long freezeDelayMs, long bigAppFreezeDelayMs,
            int churnLimit60s, long churnCooldownMs, boolean networkFreezeEnabled,
            boolean netForceSocketDestroy, long netFreezeDelayMs, long netFreezeDelayGameMs,
            int[] netRelaxUids, int generation) {
        this.enabled = enabled;
        this.shadowMode = shadowMode;
        this.freezerEnabled = freezerEnabled;
        this.memoryEnabled = memoryEnabled;
        this.freezeDelayMs = freezeDelayMs;
        this.bigAppFreezeDelayMs = bigAppFreezeDelayMs;
        this.churnLimit60s = churnLimit60s;
        this.churnCooldownMs = churnCooldownMs;
        this.networkFreezeEnabled = networkFreezeEnabled;
        this.netForceSocketDestroy = netForceSocketDestroy;
        this.netFreezeDelayMs = netFreezeDelayMs;
        this.netFreezeDelayGameMs = netFreezeDelayGameMs;
        this.netRelaxUids = netRelaxUids != null ? netRelaxUids : new int[0];
        this.generation = generation;
    }

    public static ApmConfig defaults() {
        return new ApmConfig(ApmConstants.DEFAULT_ENABLED, ApmConstants.DEFAULT_SHADOW_MODE,
                ApmConstants.DEFAULT_FREEZER_ENABLED, ApmConstants.DEFAULT_MEMORY_ENABLED,
                ApmConstants.DEFAULT_FREEZE_DELAY_MS, ApmConstants.DEFAULT_BIG_APP_FREEZE_DELAY_MS,
                ApmConstants.DEFAULT_CHURN_LIMIT_60S, ApmConstants.DEFAULT_CHURN_COOLDOWN_MS,
                ApmConstants.DEFAULT_NETWORK_FREEZE_ENABLED,
                ApmConstants.DEFAULT_NET_FORCE_SOCKET_DESTROY,
                ApmConstants.DEFAULT_NET_FREEZE_DELAY_MS,
                ApmConstants.DEFAULT_NET_FREEZE_DELAY_GAME_MS, new int[0], 0 /* generation */);
    }

    public ApmConfig withEnabled(boolean enabled) {
        return new ApmConfig(enabled, shadowMode, freezerEnabled, memoryEnabled, freezeDelayMs,
                bigAppFreezeDelayMs, churnLimit60s, churnCooldownMs, networkFreezeEnabled,
                netForceSocketDestroy, netFreezeDelayMs, netFreezeDelayGameMs, netRelaxUids,
                generation);
    }

    public ApmConfig withShadowMode(boolean shadowMode) {
        return new ApmConfig(enabled, shadowMode, freezerEnabled, memoryEnabled, freezeDelayMs,
                bigAppFreezeDelayMs, churnLimit60s, churnCooldownMs, networkFreezeEnabled,
                netForceSocketDestroy, netFreezeDelayMs, netFreezeDelayGameMs, netRelaxUids,
                generation);
    }

    public ApmConfig withFreezerEnabled(boolean freezerEnabled) {
        return new ApmConfig(enabled, shadowMode, freezerEnabled, memoryEnabled, freezeDelayMs,
                bigAppFreezeDelayMs, churnLimit60s, churnCooldownMs, networkFreezeEnabled,
                netForceSocketDestroy, netFreezeDelayMs, netFreezeDelayGameMs, netRelaxUids,
                generation);
    }

    public ApmConfig withMemoryEnabled(boolean memoryEnabled) {
        return new ApmConfig(enabled, shadowMode, freezerEnabled, memoryEnabled, freezeDelayMs,
                bigAppFreezeDelayMs, churnLimit60s, churnCooldownMs, networkFreezeEnabled,
                netForceSocketDestroy, netFreezeDelayMs, netFreezeDelayGameMs, netRelaxUids,
                generation);
    }

    public ApmConfig withNetworkFreezeEnabled(boolean networkFreezeEnabled) {
        return new ApmConfig(enabled, shadowMode, freezerEnabled, memoryEnabled, freezeDelayMs,
                bigAppFreezeDelayMs, churnLimit60s, churnCooldownMs, networkFreezeEnabled,
                netForceSocketDestroy, netFreezeDelayMs, netFreezeDelayGameMs, netRelaxUids,
                generation);
    }

    public ApmConfig withForceSocketDestroy(boolean netForceSocketDestroy) {
        return new ApmConfig(enabled, shadowMode, freezerEnabled, memoryEnabled, freezeDelayMs,
                bigAppFreezeDelayMs, churnLimit60s, churnCooldownMs, networkFreezeEnabled,
                netForceSocketDestroy, netFreezeDelayMs, netFreezeDelayGameMs, netRelaxUids,
                generation);
    }

    public ApmConfig withNetFreezeDelayMs(long netFreezeDelayMs) {
        return new ApmConfig(enabled, shadowMode, freezerEnabled, memoryEnabled, freezeDelayMs,
                bigAppFreezeDelayMs, churnLimit60s, churnCooldownMs, networkFreezeEnabled,
                netForceSocketDestroy, netFreezeDelayMs, netFreezeDelayGameMs, netRelaxUids,
                generation);
    }

    public ApmConfig withNetFreezeDelayGameMs(long netFreezeDelayGameMs) {
        return new ApmConfig(enabled, shadowMode, freezerEnabled, memoryEnabled, freezeDelayMs,
                bigAppFreezeDelayMs, churnLimit60s, churnCooldownMs, networkFreezeEnabled,
                netForceSocketDestroy, netFreezeDelayMs, netFreezeDelayGameMs, netRelaxUids,
                generation);
    }

    public ApmConfig withNetRelaxUids(int[] netRelaxUids) {
        return new ApmConfig(enabled, shadowMode, freezerEnabled, memoryEnabled, freezeDelayMs,
                bigAppFreezeDelayMs, churnLimit60s, churnCooldownMs, networkFreezeEnabled,
                netForceSocketDestroy, netFreezeDelayMs, netFreezeDelayGameMs, netRelaxUids,
                generation);
    }

    public ApmConfig withGeneration(int generation) {
        return new ApmConfig(enabled, shadowMode, freezerEnabled, memoryEnabled, freezeDelayMs,
                bigAppFreezeDelayMs, churnLimit60s, churnCooldownMs, networkFreezeEnabled,
                netForceSocketDestroy, netFreezeDelayMs, netFreezeDelayGameMs, netRelaxUids,
                generation);
    }
}
