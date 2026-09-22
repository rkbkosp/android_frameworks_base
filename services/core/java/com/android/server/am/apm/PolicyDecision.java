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

import com.android.server.am.apm.ApmConstants.ManagedState;

import java.util.List;

/**
 * What the policy would have done. {@link #dropped} is always true in this CL:
 * the service logs the action and does not run it.
 */
public final class PolicyDecision {
    public enum Action {
        NONE,
        FREEZE,
        KILL,
        DEFER
    }

    public final int uid;
    public final String packageName;
    public final Action action;
    public final ManagedState state;
    public final int freezeScore;
    public final int killScore;
    public final List<String> exemptions;
    public final int ruleVersion;
    public final int configGeneration;
    public final long nextFreezeElapsed;
    public final boolean shadow;
    public final boolean dropped;
    public final long elapsedRealtime;

    public PolicyDecision(int uid, String packageName, Action action, ManagedState state,
            int freezeScore, int killScore, List<String> exemptions, int ruleVersion,
            int configGeneration, long nextFreezeElapsed, boolean shadow, boolean dropped,
            long elapsedRealtime) {
        this.uid = uid;
        this.packageName = packageName;
        this.action = action;
        this.state = state;
        this.freezeScore = freezeScore;
        this.killScore = killScore;
        this.exemptions = exemptions;
        this.ruleVersion = ruleVersion;
        this.configGeneration = configGeneration;
        this.nextFreezeElapsed = nextFreezeElapsed;
        this.shadow = shadow;
        this.dropped = dropped;
        this.elapsedRealtime = elapsedRealtime;
    }

    public boolean sameOutcome(PolicyDecision other) {
        if (other == null) {
            return false;
        }
        return action == other.action
                && state == other.state
                && shadow == other.shadow
                && configGeneration == other.configGeneration
                && exemptions.equals(other.exemptions);
    }

    public String summarize() {
        return "uid=" + uid
                + " pkg=" + packageName
                + " action=" + action
                + " state=" + state
                + " freezeScore=" + freezeScore
                + " killScore=" + killScore
                + " exemptions=" + exemptions
                + " rule=" + ruleVersion
                + " gen=" + configGeneration
                + " shadow=" + shadow
                + " dropped=" + dropped
                + " nextFreezeElapsed=" + nextFreezeElapsed
                + " at=" + elapsedRealtime;
    }
}
