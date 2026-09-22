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
 * One source's contribution for one package. A lower source must not clear a higher
 * source's {@link #denyFreeze} or {@link #denyKill}. User force-stop is a separate flag
 * and wins over allows; it is not itself {@code denyKill}.
 *
 * <p>{@code expiresElapsed == 0} is only legal for a role whose lifetime is "while the
 * role exists". The caller removes that row when the role ends.
 */
public final class AppProtectionPolicy {
    public final String packageName;
    public final int userId;
    public final ProtectionArbiter.Layer layer;
    public final boolean denyFreeze;
    public final boolean denyKill;
    public final boolean allowNetworkWhileFrozen;
    public final boolean allowJobWakeup;
    public final boolean allowAlarmWakeup;
    public final boolean allowServiceWakeup;
    public final boolean taskRestore;
    public final RevivalBudget revival;
    /** Elapsed realtime at which this row dies. 0 means "while the role exists". */
    public final long expiresElapsed;
    public final String source;
    public final String reason;
    /** User explicit force-stop. Wins over every allow. Does not clear a higher ban bit. */
    public final boolean forceStopped;
    /** User background restriction. Same allow-blocking effect as force-stop. */
    public final boolean backgroundRestricted;
    /** Recent-task lock. Delays freeze and raises a score. Not {@code denyKill}. */
    public final boolean userLocked;
    /**
     * Added to the kill-selection score as protection. Higher is harder to pick.
     * Not {@code denyKill}. {@code MASK_KILL_WHITE} uses this, not a permanent ban.
     */
    public final int protectionScore;
    /** Extra freeze debounce. Not {@code denyFreeze}. */
    public final long freezeDelayExtraMs;

    private AppProtectionPolicy(Builder builder) {
        packageName = builder.packageName;
        userId = builder.userId;
        layer = builder.layer;
        denyFreeze = builder.denyFreeze;
        denyKill = builder.denyKill;
        allowNetworkWhileFrozen = builder.allowNetworkWhileFrozen;
        allowJobWakeup = builder.allowJobWakeup;
        allowAlarmWakeup = builder.allowAlarmWakeup;
        allowServiceWakeup = builder.allowServiceWakeup;
        taskRestore = builder.taskRestore;
        revival = builder.revival;
        expiresElapsed = builder.expiresElapsed;
        source = builder.source;
        reason = builder.reason;
        forceStopped = builder.forceStopped;
        backgroundRestricted = builder.backgroundRestricted;
        userLocked = builder.userLocked;
        protectionScore = builder.protectionScore;
        freezeDelayExtraMs = builder.freezeDelayExtraMs;
    }

    public boolean expired(long nowElapsed) {
        return expiresElapsed > 0 && nowElapsed >= expiresElapsed;
    }

    public static Builder builder(String packageName, int userId, ProtectionArbiter.Layer layer) {
        return new Builder(packageName, userId, layer);
    }

    public static final class Builder {
        private final String packageName;
        private final int userId;
        private final ProtectionArbiter.Layer layer;
        private boolean denyFreeze;
        private boolean denyKill;
        private boolean allowNetworkWhileFrozen;
        private boolean allowJobWakeup;
        private boolean allowAlarmWakeup;
        private boolean allowServiceWakeup;
        private boolean taskRestore;
        private RevivalBudget revival;
        private long expiresElapsed = Long.MAX_VALUE;
        private String source = "config";
        private String reason = "";
        private boolean forceStopped;
        private boolean backgroundRestricted;
        private boolean userLocked;
        private int protectionScore;
        private long freezeDelayExtraMs;

        private Builder(String packageName, int userId, ProtectionArbiter.Layer layer) {
            this.packageName = packageName;
            this.userId = userId;
            this.layer = layer == null ? ProtectionArbiter.Layer.STATIC : layer;
        }

        public Builder denyFreeze(boolean value) {
            denyFreeze = value;
            return this;
        }

        public Builder denyKill(boolean value) {
            denyKill = value;
            return this;
        }

        public Builder allowNetworkWhileFrozen(boolean value) {
            allowNetworkWhileFrozen = value;
            return this;
        }

        public Builder allowJobWakeup(boolean value) {
            allowJobWakeup = value;
            return this;
        }

        public Builder allowAlarmWakeup(boolean value) {
            allowAlarmWakeup = value;
            return this;
        }

        public Builder allowServiceWakeup(boolean value) {
            allowServiceWakeup = value;
            return this;
        }

        public Builder taskRestore(boolean value) {
            taskRestore = value;
            return this;
        }

        public Builder revival(RevivalBudget value) {
            revival = value;
            return this;
        }

        public Builder expiresElapsed(long value) {
            expiresElapsed = value;
            return this;
        }

        public Builder source(String value) {
            source = value == null ? "" : value;
            return this;
        }

        public Builder reason(String value) {
            reason = value == null ? "" : value;
            return this;
        }

        public Builder forceStopped(boolean value) {
            forceStopped = value;
            return this;
        }

        public Builder backgroundRestricted(boolean value) {
            backgroundRestricted = value;
            return this;
        }

        public Builder userLocked(boolean value) {
            userLocked = value;
            return this;
        }

        public Builder protectionScore(int value) {
            protectionScore = value;
            return this;
        }

        public Builder freezeDelayExtraMs(long value) {
            freezeDelayExtraMs = value;
            return this;
        }

        public AppProtectionPolicy build() {
            return new AppProtectionPolicy(this);
        }
    }
}
