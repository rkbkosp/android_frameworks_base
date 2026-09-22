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

import com.android.server.am.apm.ApmProcessRecord.PidSlot;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Text for {@code dumpsys activity apm} and {@code cmd activity apm explain}.
 * Caller holds the service lock. This class does not touch AMS or the freezer. The network
 * section prints cached state only: {@link NetworkFreezeController#dump} issues no IPC.
 */
final class ApmShellCommand {
    private static final int DUMP_EVENT_LIMIT = 50;

    private ApmShellCommand() {}

    static void dump(PrintWriter pw, ApmConfig config, ProcessStateTracker tracker,
            ApmStats stats, NavigationPolicyConfig navigationConfig,
            List<NavigationProtectionController.Snapshot> navigation,
            NetworkFreezeController net) {
        pw.println("ACTIVITY MANAGER APM (dumpsys activity apm)");
        pw.print("  enabled=");
        pw.print(config.enabled);
        pw.print(" shadowMode=");
        pw.print(config.shadowMode);
        pw.print(" freezer=");
        pw.print(config.freezerEnabled);
        pw.print(" memory=");
        pw.print(config.memoryEnabled);
        pw.println(" (freeze runs when enabled, freezer is on, and shadow is off)");
        pw.print("  schema=");
        pw.print(ApmConstants.SCHEMA_VERSION);
        pw.print(" generation=");
        pw.print(config.generation);
        pw.print(" freezeDelayMs=");
        pw.print(config.freezeDelayMs);
        pw.print(" bigAppFreezeDelayMs=");
        pw.println(config.bigAppFreezeDelayMs);
        pw.print("  uids=");
        pw.print(tracker.size());
        pw.print(" recorded=");
        pw.print(stats.recorded());
        pw.print(" dropped=");
        pw.print(stats.dropped());
        pw.print("  executed=");
        pw.println(stats.executed());
        dumpNavigation(pw, navigationConfig, navigation, tracker);
        if (net != null) {
            net.dump(pw);
        }
        pw.print("  ");
        pw.println(ApmConstants.REMAINING_ROLE_GAPS);
        if (!config.enabled) {
            pw.println("  master switch off: snapshots and action logging are not updated");
        }
        for (int i = 0; i < tracker.size(); i++) {
            dumpRecord(pw, tracker.valueAt(i), "  ");
        }
        pw.println("  recent decisions:");
        final StringBuilder events = new StringBuilder();
        stats.dumpRecent(events, DUMP_EVENT_LIMIT);
        if (events.length() == 0) {
            pw.println("    (none)");
        } else {
            pw.print(events);
        }
    }

    static void explain(PrintWriter pw, ApmConfig config, ProcessStateTracker tracker,
            String target) {
        if (target == null || target.length() == 0) {
            pw.println("Error: explain requires a uid or package name");
            return;
        }
        final ArrayList<ApmProcessRecord> matches = new ArrayList<>();
        Integer uid = parseUid(target);
        if (uid != null) {
            final ApmProcessRecord rec = tracker.get(uid);
            if (rec != null) {
                matches.add(rec);
            }
        } else {
            for (int i = 0; i < tracker.size(); i++) {
                final ApmProcessRecord rec = tracker.valueAt(i);
                if (rec.matchesName(target)) {
                    matches.add(rec);
                }
            }
        }
        pw.print("APM explain target=");
        pw.println(target);
        pw.print("  enabled=");
        pw.print(config.enabled);
        pw.print(" shadowMode=");
        pw.print(config.shadowMode);
        pw.print(" ruleVersion=");
        pw.print(ApmConstants.SCHEMA_VERSION);
        pw.print(" configGeneration=");
        pw.println(config.generation);
        pw.print("  ");
        pw.println(ApmConstants.REMAINING_ROLE_GAPS);
        if (matches.isEmpty()) {
            pw.println("  no uid record");
            return;
        }
        for (int i = 0; i < matches.size(); i++) {
            final ApmProcessRecord rec = matches.get(i);
            dumpRecord(pw, rec, "  ");
            final PolicyDecision decision = rec.lastDecision;
            if (decision == null) {
                pw.println("    lastDecision=(none)");
                continue;
            }
            pw.print("    lastDecision action=");
            pw.print(decision.action);
            pw.print(" shadow=");
            pw.print(decision.shadow);
            pw.print(" dropped=");
            pw.println(decision.dropped);
            pw.print("    scores freeze=");
            pw.print(decision.freezeScore);
            pw.print(" kill=");
            pw.print(decision.killScore);
            pw.print(" exemptions=");
            pw.println(decision.exemptions);
            pw.print("    nextFreezeElapsed=");
            pw.print(decision.nextFreezeElapsed);
            pw.print(" at=");
            pw.println(decision.elapsedRealtime);
        }
    }

    private static void dumpNavigation(PrintWriter pw, NavigationPolicyConfig config,
            List<NavigationProtectionController.Snapshot> navigation,
            ProcessStateTracker tracker) {
        int protectedCount = 0;
        if (navigation != null) {
            for (int i = 0; i < navigation.size(); i++) {
                if (navigation.get(i).protectedNow) {
                    protectedCount++;
                }
            }
        }
        pw.print("  navigation enabled=");
        pw.print(config.enabled);
        pw.print(" adjClamp=");
        pw.print(config.adjClampEnabled ? config.adjClamp : -1);
        pw.print(" debounceMs=");
        pw.print(config.enterDebounceMs);
        pw.print(" graceMs=");
        pw.print(config.exitGraceMs);
        pw.print(" recentTopMs=");
        pw.print(config.recentTopMs);
        pw.print(" allowlist=");
        pw.print(config.allowlist.size());
        pw.print(" denylist=");
        pw.print(config.denylist.size());
        pw.print(" tracked=");
        pw.print(navigation == null ? 0 : navigation.size());
        pw.print(" protected=");
        pw.println(protectedCount);
        if (navigation == null || navigation.isEmpty()) {
            return;
        }
        for (int i = 0; i < navigation.size(); i++) {
            final NavigationProtectionController.Snapshot snap = navigation.get(i);
            final ApmProcessRecord rec = tracker.get(snap.uid);
            pw.print("    uid=");
            pw.print(snap.uid);
            pw.print(" pkg=");
            pw.print(rec == null ? null : rec.primaryPackage());
            pw.print(" state=");
            pw.print(snap.state);
            pw.print(" gnss=");
            pw.print(snap.gnssActive);
            pw.print(" locationFgs=");
            pw.print(snap.locationFgs);
            pw.print(" allow=");
            pw.print(snap.classifierAllowed);
            pw.print(" deny=");
            pw.print(snap.classifierDenied);
            pw.print(" graceDeadline=");
            pw.println(snap.graceDeadlineMs);
            pw.print("      explanation=");
            pw.println(snap.explanation);
        }
    }

    private static void dumpRecord(PrintWriter pw, ApmProcessRecord rec, String prefix) {
        pw.print(prefix);
        pw.print("uid=");
        pw.print(rec.uid);
        pw.print(" user=");
        pw.print(rec.userId);
        pw.print(" pkg=");
        pw.print(rec.primaryPackage());
        pw.print(" state=");
        pw.print(rec.state);
        pw.print(" minAdj=");
        pw.print(rec.minAdj);
        pw.print(" procState=");
        pw.println(rec.procState);
        pw.print(prefix);
        pw.print("  visible=");
        pw.print(rec.visible);
        pw.print(" foreground=");
        pw.print(rec.foreground);
        pw.print(" fgs=");
        pw.print(rec.foregroundService);
        pw.print(" persistent=");
        pw.print(rec.persistent);
        pw.print(" home=");
        pw.print(rec.home);
        pw.print(" frozenByApm=");
        pw.print(rec.frozenByApm);
        pw.print(" systemUid=");
        pw.print(rec.systemUid);
        pw.print(" pids=");
        pw.print(formatPids(rec));
        pw.print(" graceUntil=");
        pw.println(rec.graceUntilElapsed);
    }

    private static String formatPids(ApmProcessRecord rec) {
        if (rec.pids.size() == 0) {
            return "[]";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < rec.pids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            final PidSlot slot = rec.pids.valueAt(i);
            sb.append(slot.pid);
            sb.append("#");
            sb.append(slot.startSeq);
        }
        sb.append(']');
        return sb.toString();
    }

    private static Integer parseUid(String target) {
        final int length = target.length();
        if (length == 0 || length > 9) {
            return null;
        }
        for (int i = 0; i < length; i++) {
            final char c = target.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Integer.parseInt(target);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
