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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Upgrade adj from {@code sys_res_control_config.xml} and {@code sys_oomadj_config.xml}.
 * Previous-app slots are 450, 475, 704 and stop at 3. Recent-task slots are
 * 430, 435, 704, 705, 706. These are not {@code denyKill}. System apps and an
 * empty black list are skipped for the slots. A lower number is a stronger upgrade.
 */
public final class RecentAdjPolicy {
    public static final int[] PREVIOUS_APP_SLOTS = {450, 475, 704};
    public static final int PREVIOUS_MAX = 3;
    public static final int[] RECENT_TASK_SLOTS = {430, 435, 704, 705, 706};
    /** Array length, and never above the loader cap of 32. */
    public static final int RECENT_MAX = Math.min(32, RECENT_TASK_SLOTS.length);
    /** Main user in the oomadj file. {@code all} is any user. */
    public static final int USER_MAIN = 0;
    public static final int USER_ALL = -1;

    private RecentAdjPolicy() {}

    public static final class Candidate {
        public final int index;
        public final String packageName;
        public final String processName;
        public final int userId;
        public final int uid;
        public final boolean systemApp;
        public final boolean previousApp;
        public final boolean hasRecentTask;
        public final int curAdj;
        /** Process start, uptime ms. 0 if unknown. */
        public final long startUptimeMs;
        /** Last interaction, uptime ms. 0 if unknown. */
        public final long lastInteractiveUptimeMs;
        public final int totalRamGb;

        public Candidate(int index, String packageName, String processName, int userId, int uid,
                boolean systemApp, boolean previousApp, boolean hasRecentTask, int curAdj,
                long startUptimeMs, long lastInteractiveUptimeMs, int totalRamGb) {
            this.index = index;
            this.packageName = packageName;
            this.processName = processName;
            this.userId = userId;
            this.uid = uid;
            this.systemApp = systemApp;
            this.previousApp = previousApp;
            this.hasRecentTask = hasRecentTask;
            this.curAdj = curAdj;
            this.startUptimeMs = startUptimeMs;
            this.lastInteractiveUptimeMs = lastInteractiveUptimeMs;
            this.totalRamGb = totalRamGb;
        }
    }

    public static final class Assignment {
        public final int index;
        /** Best upgrade, or -1 when nothing applies. */
        public final int adj;
        /** Slot value from previousAppAdj, or -1. */
        public final int previousAdj;
        /** Slot value from recentTaskAdj, or -1. */
        public final int recentAdj;
        public final String staticRule;

        Assignment(int index, int adj, int previousAdj, int recentAdj, String staticRule) {
            this.index = index;
            this.adj = adj;
            this.previousAdj = previousAdj;
            this.recentAdj = recentAdj;
            this.staticRule = staticRule;
        }

        public boolean previous() {
            return previousAdj >= 0;
        }
    }

    /**
     * One static upgrade. {@code runningMem} is stored and not applied: the XML gives
     * {@code 10} with no unit.
     */
    public static final class StaticRule {
        public final String packageName;
        public final String processName;
        public final int userId;
        public final int adj;
        public final int afterProcActiveSec;
        public final int afterAppInteractiveSec;
        public final int minRamGb;
        public final boolean runningMem;

        StaticRule(String packageName, String processName, int userId, int adj,
                int afterProcActiveSec, int afterAppInteractiveSec, int minRamGb,
                boolean runningMem) {
            this.packageName = packageName;
            this.processName = processName;
            this.userId = userId;
            this.adj = adj;
            this.afterProcActiveSec = afterProcActiveSec;
            this.afterAppInteractiveSec = afterAppInteractiveSec;
            this.minRamGb = minRamGb;
            this.runningMem = runningMem;
        }
    }

    private static final StaticRule[] RULES = {
            new StaticRule("com.heytap.quicksearchbox", "com.heytap.quicksearchbox",
                    USER_ALL, 710, -1, -1, -1, false),
            new StaticRule("com.heytap.health", "com.heytap.health:transport",
                    USER_ALL, 800, -1, -1, -1, false),
            new StaticRule("com.oppo.instant.local.service", "com.oppo.instant.local.service",
                    USER_ALL, 480, -1, -1, -1, true),
            new StaticRule("com.tencent.mm", "com.tencent.mm:push",
                    USER_MAIN, 450, 43200, -1, -1, false),
            new StaticRule("com.tencent.mm", "com.tencent.mm",
                    USER_MAIN, 455, -1, 3600, -1, false),
            new StaticRule("com.teamtalk.im", "com.teamtalk.im",
                    USER_MAIN, 780, -1, 3600, -1, false),
            new StaticRule("com.heytap.openid", "com.heytap.openid",
                    USER_ALL, 200, -1, -1, -1, false),
            new StaticRule("com.tencent.tmgp.dfm", "com.tencent.tmgp.dfm",
                    USER_MAIN, 460, -1, 300, 12, false),
            new StaticRule("com.tencent.tmgp.sgame", "com.tencent.tmgp.sgame",
                    USER_MAIN, 460, -1, 300, 12, false),
    };

    public static StaticRule[] staticRules() {
        return RULES.clone();
    }

    /**
     * {@code candidates} is most-recent first. At most {@link #PREVIOUS_MAX} previous
     * slots are filled, in order 450, 475, 704.
     */
    public static List<Assignment> assign(List<Candidate> candidates, long nowUptimeMs) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        final int count = candidates.size();
        final int[] previousAdj = new int[count];
        final int[] recentAdj = new int[count];
        final int[] best = new int[count];
        final String[] ruleName = new String[count];
        for (int i = 0; i < count; i++) {
            previousAdj[i] = -1;
            recentAdj[i] = -1;
            best[i] = -1;
        }
        int previousUsed = 0;
        int recentUsed = 0;
        for (int i = 0; i < count; i++) {
            final Candidate candidate = candidates.get(i);
            if (candidate.systemApp) {
                continue;
            }
            if (candidate.previousApp && previousUsed < PREVIOUS_MAX) {
                previousAdj[i] = PREVIOUS_APP_SLOTS[previousUsed];
                best[i] = improve(best[i], previousAdj[i]);
                previousUsed++;
            }
            if (candidate.hasRecentTask && recentUsed < RECENT_MAX) {
                recentAdj[i] = RECENT_TASK_SLOTS[recentUsed];
                best[i] = improve(best[i], recentAdj[i]);
                recentUsed++;
            }
        }
        for (int i = 0; i < count; i++) {
            final Candidate candidate = candidates.get(i);
            for (int r = 0; r < RULES.length; r++) {
                if (!matches(RULES[r], candidate, nowUptimeMs)) {
                    continue;
                }
                best[i] = improve(best[i], RULES[r].adj);
                ruleName[i] = RULES[r].packageName + "/" + RULES[r].processName;
            }
        }
        final ArrayList<Assignment> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (best[i] < 0 && previousAdj[i] < 0 && recentAdj[i] < 0) {
                continue;
            }
            out.add(new Assignment(candidates.get(i).index, best[i], previousAdj[i], recentAdj[i],
                    ruleName[i]));
        }
        return out;
    }

    private static boolean matches(StaticRule rule, Candidate candidate, long nowUptimeMs) {
        if (rule.runningMem) {
            return false;
        }
        if (candidate.packageName == null || !candidate.packageName.equals(rule.packageName)) {
            return false;
        }
        if (candidate.processName == null || !candidate.processName.equals(rule.processName)) {
            return false;
        }
        if (rule.userId != USER_ALL && candidate.userId != rule.userId) {
            return false;
        }
        if (rule.minRamGb >= 0 && candidate.totalRamGb < rule.minRamGb) {
            return false;
        }
        if (rule.afterProcActiveSec >= 0) {
            if (candidate.startUptimeMs <= 0L || nowUptimeMs < candidate.startUptimeMs) {
                return false;
            }
            if (nowUptimeMs - candidate.startUptimeMs < rule.afterProcActiveSec * 1000L) {
                return false;
            }
        }
        if (rule.afterAppInteractiveSec >= 0) {
            if (candidate.lastInteractiveUptimeMs <= 0L
                    || nowUptimeMs < candidate.lastInteractiveUptimeMs) {
                return false;
            }
            if (nowUptimeMs - candidate.lastInteractiveUptimeMs
                    < rule.afterAppInteractiveSec * 1000L) {
                return false;
            }
        }
        return true;
    }

    /** Lower adj is a stronger upgrade. -1 means unset. */
    private static int improve(int current, int proposed) {
        if (proposed < 0) {
            return current;
        }
        if (current < 0 || proposed < current) {
            return proposed;
        }
        return current;
    }
}
