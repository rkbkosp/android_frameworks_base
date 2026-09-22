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

import static android.app.ActivityManager.PROCESS_STATE_TOP;

import com.android.server.am.ProcessList;
import com.android.server.am.apm.ApmConstants.ManagedState;
import com.android.server.am.apm.PolicyDecision.Action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scores one uid and names the action that would be taken. Hard exemptions outrank the score.
 * The returned decision is never executed by this class.
 */
public final class PolicyEngine {
    public PolicyDecision decide(ApmProcessRecord rec, ApmConfig config, long nowElapsed) {
        final int freezeScore = freezeScore(rec);
        final int killScore = killScore(rec);
        final List<String> exemptions = exemptions(rec);
        final Action action = actionFor(rec, exemptions, freezeScore, killScore);
        final long nextFreezeElapsed = nextFreezeElapsed(rec, action, nowElapsed);
        return new PolicyDecision(rec.uid, rec.primaryPackage(), action, rec.state, freezeScore,
                killScore, exemptions, ApmConstants.SCHEMA_VERSION, config.generation,
                nextFreezeElapsed, config.shadowMode, true /* dropped */, nowElapsed);
    }

    private static int freezeScore(ApmProcessRecord rec) {
        if (rec.state == ManagedState.CACHED && rec.minAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
            return ApmConstants.FREEZE_SCORE_THRESHOLD;
        }
        return 0;
    }

    private static int killScore(ApmProcessRecord rec) {
        if (rec.state != ManagedState.CACHED || rec.minAdj < ProcessList.CACHED_APP_MIN_ADJ) {
            return 0;
        }
        final int span = rec.minAdj - ProcessList.CACHED_APP_MIN_ADJ;
        return Math.min(100, ApmConstants.KILL_SCORE_THRESHOLD + span / 5);
    }

    private static List<String> exemptions(ApmProcessRecord rec) {
        final ArrayList<String> reasons = new ArrayList<>(3);
        if (rec.visible || rec.minAdj <= ProcessList.VISIBLE_APP_ADJ) {
            reasons.add("visible");
        }
        if (rec.foreground || rec.foregroundService
                || (rec.procState >= 0 && rec.procState <= PROCESS_STATE_TOP)) {
            reasons.add("foreground");
        }
        if (rec.systemUid) {
            reasons.add("system-uid");
        }
        if (rec.persistent) {
            reasons.add("persistent-system");
        }
        if (reasons.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(reasons);
    }

    private static Action actionFor(ApmProcessRecord rec, List<String> exemptions,
            int freezeScore, int killScore) {
        if (!exemptions.isEmpty()) {
            return Action.NONE;
        }
        if (rec.state == ManagedState.CACHED
                && freezeScore >= ApmConstants.FREEZE_SCORE_THRESHOLD) {
            return Action.FREEZE;
        }
        if (rec.state == ManagedState.CACHED && killScore >= ApmConstants.KILL_SCORE_THRESHOLD) {
            return Action.KILL;
        }
        if (rec.state == ManagedState.GRACE) {
            return Action.DEFER;
        }
        return Action.NONE;
    }

    private static long nextFreezeElapsed(ApmProcessRecord rec, Action action, long nowElapsed) {
        if (action == Action.FREEZE || action == Action.KILL) {
            return nowElapsed;
        }
        if (action == Action.DEFER) {
            return rec.graceUntilElapsed;
        }
        return -1L;
    }
}
