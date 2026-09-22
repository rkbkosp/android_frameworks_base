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

import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.os.Process.SYSTEM_UID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.ArraySet;

import android.app.ActivityManager;

import com.android.server.am.ProcessList;
import com.android.server.am.apm.ApmConstants.ManagedState;
import com.android.server.am.apm.ApmEvent.ProcessSnapshot;
import com.android.server.am.apm.PolicyDecision.Action;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Adaptive process manager checks. The fake executor records freeze, compact, and kill.
 * Nothing here touches a cgroup.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:AdaptiveProcessManagerServiceTest
 */
public class AdaptiveProcessManagerServiceTest {
    private static final int UID = 10123;
    private static final int USER = 0;
    private static final String PKG = "com.example.app";
    private static final int PID = 4321;
    private static final int CACHED_ADJ = ProcessList.CACHED_APP_MIN_ADJ + 5;

    @Test
    public void hardExemptionBeatsHighKillAndFreezeScore() {
        final PolicyEngine policy = new PolicyEngine();
        final ApmConfig config = ApmConfig.defaults().withEnabled(true);

        final ApmProcessRecord cached = record(UID, false /* persistent */);
        cached.state = ManagedState.CACHED;
        cached.minAdj = CACHED_ADJ;
        cached.procState = PROCESS_STATE_CACHED_EMPTY;
        final PolicyDecision wouldAct = policy.decide(cached, config, 5_000L);
        assertEquals(Action.FREEZE, wouldAct.action);
        assertTrue(wouldAct.freezeScore >= ApmConstants.FREEZE_SCORE_THRESHOLD);
        assertTrue(wouldAct.killScore >= ApmConstants.KILL_SCORE_THRESHOLD);
        assertFalse(wouldAct.shadow);
        assertFalse(wouldAct.dropped);

        final ApmProcessRecord visible = record(UID + 1, false /* persistent */);
        visible.state = ManagedState.CACHED;
        visible.minAdj = CACHED_ADJ;
        visible.procState = PROCESS_STATE_CACHED_EMPTY;
        visible.visible = true;
        final PolicyDecision visibleDecision = policy.decide(visible, config, 5_000L);
        assertEquals(Action.NONE, visibleDecision.action);
        assertTrue(visibleDecision.exemptions.contains("visible"));
        assertTrue(visibleDecision.freezeScore >= ApmConstants.FREEZE_SCORE_THRESHOLD);
        assertTrue(visibleDecision.killScore >= ApmConstants.KILL_SCORE_THRESHOLD);

        final ApmProcessRecord system = record(SYSTEM_UID, false /* persistent */);
        system.state = ManagedState.CACHED;
        system.minAdj = CACHED_ADJ;
        system.procState = PROCESS_STATE_CACHED_EMPTY;
        final PolicyDecision systemDecision = policy.decide(system, config, 5_000L);
        assertTrue(system.systemUid);
        assertEquals(Action.NONE, systemDecision.action);
        assertTrue(systemDecision.exemptions.contains("system-uid"));
        assertTrue(systemDecision.freezeScore >= ApmConstants.FREEZE_SCORE_THRESHOLD);
        assertTrue(systemDecision.killScore >= ApmConstants.KILL_SCORE_THRESHOLD);

        final ApmProcessRecord persistent = record(UID + 2, true /* persistent */);
        persistent.state = ManagedState.CACHED;
        persistent.minAdj = CACHED_ADJ;
        persistent.procState = PROCESS_STATE_CACHED_EMPTY;
        final PolicyDecision persistentDecision = policy.decide(persistent, config, 5_000L);
        assertEquals(Action.NONE, persistentDecision.action);
        assertTrue(persistentDecision.exemptions.contains("persistent-system"));
        assertTrue(persistentDecision.freezeScore >= ApmConstants.FREEZE_SCORE_THRESHOLD);
        assertTrue(persistentDecision.killScore >= ApmConstants.KILL_SCORE_THRESHOLD);
    }

    @Test
    public void stateMovesActiveToGraceToCached() {
        final ManualClock clock = new ManualClock();
        clock.now = 10_000L;
        final AdaptiveProcessManagerService service = newService(clock);
        service.setEnabledForTest(true);

        service.noteProcessStarted(PID, UID, USER, PKG, PKG, 1L, false /* persistent */);
        assertEquals(ManagedState.GRACE, service.getStateForTest(UID));

        service.noteTopResumed(UID, PID, USER, PKG);
        assertEquals(ManagedState.ACTIVE, service.getStateForTest(UID));

        service.noteTopResumed(-1, -1, -1, null);
        assertEquals(ManagedState.GRACE, service.getStateForTest(UID));

        clock.now += ApmConstants.DEFAULT_FREEZE_DELAY_MS;
        service.postOomAdjCompleted(0 /* reason */, Collections.singletonList(cachedSnapshot(
                PID, 1L)));
        assertEquals(ManagedState.CACHED, service.getStateForTest(UID));
    }

    @Test
    public void defaultConfigFreezes() {
        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 20_000L;
        final AdaptiveProcessManagerService service = new AdaptiveProcessManagerService(
                clock, false /* startThread */, fake);
        assertTrue(service.isEnabled());
        assertFalse(ApmConfig.defaults().shadowMode);
        assertTrue(ApmConfig.defaults().freezerEnabled);
        assertTrue(ApmConfig.defaults().memoryEnabled);
        settle(service, clock);
        service.postOomAdjCompleted(0, Collections.singletonList(
                cachedSnapshot(PID, 1L, false /* visible */, false /* foregroundService */)));
        service.fireDueAlarmsForTest();
        assertEquals(1, fake.freezeCalls);
        assertTrue(fake.frozen.contains(PID));
        assertTrue(service.isFrozenForTest(UID));
        assertTrue(service.getExecutedActionCountForTest() >= 1);
        final PolicyDecision decision = service.getLastDecisionForTest(UID);
        assertNotNull(decision);
        assertEquals(Action.FREEZE, decision.action);
        assertFalse(decision.shadow);
        assertFalse(decision.dropped);
    }

    @Test
    public void disabledMasterIgnoresNewSnapshots() {
        final ManualClock clock = new ManualClock();
        clock.now = 20_000L;
        final AdaptiveProcessManagerService service = newService(clock);
        service.setEnabledForTest(false);
        assertFalse(service.isEnabled());
        service.postOomAdjCompleted(0, Collections.singletonList(cachedSnapshot(PID, 1L)));
        assertNull(service.getStateForTest(UID));
        assertEquals(0, service.getRecordedDecisionCountForTest());

        service.setEnabledForTest(true);
        service.noteProcessStarted(PID, UID, USER, PKG, PKG, 1L, false /* persistent */);
        service.noteTopResumed(UID, PID, USER, PKG);
        service.noteTopResumed(-1, -1, -1, null);
        clock.now += ApmConstants.DEFAULT_FREEZE_DELAY_MS;
        service.postOomAdjCompleted(0, Collections.singletonList(cachedSnapshot(PID, 1L)));

        final PolicyDecision decision = service.getLastDecisionForTest(UID);
        assertNotNull(decision);
        assertEquals(Action.FREEZE, decision.action);
        assertEquals(ManagedState.CACHED, decision.state);
        assertFalse(decision.shadow);
        final int recorded = service.getRecordedDecisionCountForTest();
        service.setEnabledForTest(false);
        service.noteTopResumed(UID, PID, USER, PKG);
        assertEquals(recorded, service.getRecordedDecisionCountForTest());
        assertEquals(Action.FREEZE, service.getLastDecisionForTest(UID).action);
        assertNoExecutionSurface(service);
    }

    @Test
    public void duplicateAndOutOfOrderEventsDoNotCorruptUidRecord() {
        final ManualClock clock = new ManualClock();
        clock.now = 1_000L;
        final AdaptiveProcessManagerService service = newService(clock);
        service.setEnabledForTest(true);

        service.noteProcessStarted(10, UID, USER, PKG, PKG, 2L, false /* persistent */);
        service.noteProcessStarted(10, UID, USER, PKG, PKG, 2L, false /* persistent */);
        assertEquals(1, service.getPidCountForTest(UID));

        service.noteProcessDied(10, UID, USER, PKG, 1L);
        assertEquals(1, service.getPidCountForTest(UID));

        service.noteProcessDied(10, UID, USER, PKG, 2L);
        assertEquals(0, service.getPidCountForTest(UID));

        service.noteProcessStarted(10, UID, USER, PKG, PKG, 2L, false /* persistent */);
        assertEquals(0, service.getPidCountForTest(UID));

        service.noteProcessDied(10, UID, USER, PKG, 4L);
        service.noteProcessStarted(10, UID, USER, PKG, PKG, 4L, false /* persistent */);
        assertEquals(0, service.getPidCountForTest(UID));

        service.noteProcessStarted(10, UID, USER, PKG, PKG, 5L, false /* persistent */);
        assertEquals(1, service.getPidCountForTest(UID));
        service.noteProcessStarted(10, UID, USER, PKG, PKG, 3L, false /* persistent */);
        assertEquals(1, service.getPidCountForTest(UID));
        service.noteProcessDied(10, UID, USER, PKG, 5L);
        assertEquals(0, service.getPidCountForTest(UID));

        service.noteProcessStarted(21, UID, USER, PKG, PKG, 1L, false /* persistent */);
        service.noteProcessStarted(22, UID, USER, PKG, PKG, 1L, false /* persistent */);
        assertEquals(2, service.getPidCountForTest(UID));
        service.noteProcessDied(21, UID, USER, PKG, 1L);
        assertEquals(1, service.getPidCountForTest(UID));
        service.noteProcessDied(22, UID, USER, PKG, 1L);
        assertEquals(0, service.getPidCountForTest(UID));

        service.postOomAdjCompleted(0, Collections.singletonList(cachedSnapshot(22, 1L)));
        assertEquals(0, service.getPidCountForTest(UID));
        service.postOomAdjCompleted(0, Collections.singletonList(cachedSnapshot(22, 2L)));
        assertEquals(1, service.getPidCountForTest(UID));

        assertTrue(service.hasProcessNameForTest(UID, PKG));
        assertEquals(PKG, service.getPrimaryPackageForTest(UID));
        assertNotNull(service.getStateForTest(UID));
    }

    @Test
    public void hardExemptionBeatsFreezeWhenFreezerIsOn() {
        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 50_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        service.postOomAdjCompleted(0, Collections.singletonList(cachedSnapshot(PID, 1L,
                true /* visible */, false /* foregroundService */)));
        service.fireDueAlarmsForTest();
        assertEquals(0, fake.freezeCalls);
        assertFalse(service.isFrozenForTest(UID));
    }

    @Test
    public void oneFailingProcessCancelsUidFreeze() {
        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 80_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        service.postOomAdjCompleted(0, Arrays.asList(
                cachedSnapshot(PID, 1L, false, false),
                cachedSnapshot(PID + 1, 1L, false, true /* foregroundService */)));
        service.fireDueAlarmsForTest();
        assertEquals(0, fake.freezeCalls);
        assertFalse(service.isFrozenForTest(UID));
    }

    @Test
    public void partialFreezeRollsBack() {
        final FakeExecutor fake = new FakeExecutor();
        fake.failPids.add(PID + 1);
        final ManualClock clock = new ManualClock();
        clock.now = 90_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        settle(service, clock);
        service.postOomAdjCompleted(0, Arrays.asList(
                cachedSnapshot(PID, 1L, false, false),
                cachedSnapshot(PID + 1, 1L, false, false)));
        service.fireDueAlarmsForTest();
        assertEquals(1, fake.freezeCalls);
        assertEquals(FreezeController.PARTIAL_FREEZE_ROLLBACK,
                service.getLastFreezeDetailForTest(UID));
        assertFalse(service.isFrozenForTest(UID));
        assertTrue(fake.unfrozen.contains(PID));
        assertFalse(fake.frozen.contains(PID));
    }

    @Test
    public void masterSwitchOffUnfreezes() {
        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 100_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        settle(service, clock);
        service.postOomAdjCompleted(0, Collections.singletonList(
                cachedSnapshot(PID, 1L, false, false)));
        service.fireDueAlarmsForTest();
        assertTrue(service.isFrozenForTest(UID));
        service.setEnabledForTest(false);
        assertFalse(service.isFrozenForTest(UID));
        assertTrue(fake.unfrozen.contains(PID));
        final int kills = fake.killCalls;
        service.noteMemoryPressure(ApmConstants.PRESSURE_CRITICAL);
        assertEquals(kills, fake.killCalls);
    }

    @Test
    public void churnCooldownPausesFreeze() {
        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 200_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        settle(service, clock);
        for (int i = 0; i < 5; i++) {
            service.postOomAdjCompleted(0, Collections.singletonList(
                    cachedSnapshot(PID, 1L, false, false)));
            service.fireDueAlarmsForTest();
            assertTrue("cycle " + i, service.isFrozenForTest(UID));
            service.noteStartUnfreeze(UID);
            assertFalse(service.isFrozenForTest(UID));
        }
        final int calls = fake.freezeCalls;
        service.postOomAdjCompleted(0, Collections.singletonList(
                cachedSnapshot(PID, 1L, false, false)));
        service.fireDueAlarmsForTest();
        assertEquals(calls, fake.freezeCalls);
        assertFalse(service.isFrozenForTest(UID));
        assertTrue(service.getLastFreezeDetailForTest(UID).contains("churn-cooldown"));
    }

    @Test
    public void threeFreezeFailuresStopUntilReboot() {
        final FakeExecutor fake = new FakeExecutor();
        fake.failPids.add(PID);
        final ManualClock clock = new ManualClock();
        clock.now = 300_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        settle(service, clock);
        for (int i = 0; i < 3; i++) {
            service.postOomAdjCompleted(0, Collections.singletonList(
                    cachedSnapshot(PID, 1L, false, false)));
            service.fireDueAlarmsForTest();
        }
        assertEquals(3, fake.freezeCalls);
        assertTrue(service.isFreezeDisabledForTest(UID));
        service.postOomAdjCompleted(0, Collections.singletonList(
                cachedSnapshot(PID, 1L, false, false)));
        service.fireDueAlarmsForTest();
        assertEquals(3, fake.freezeCalls);
    }

    @Test
    public void shadowDropsFreezeEvenIfFreezerFlagIsOn() {
        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 400_000L;
        final AdaptiveProcessManagerService service = new AdaptiveProcessManagerService(
                clock, false /* startThread */, fake);
        service.setEnabledForTest(true);
        service.setShadowModeForTest(true);
        service.setFreezerEnabledForTest(true);
        assertTrue(service.shellFreeze(Integer.toString(UID), -1)
                .contains("shadow mode"));
        settle(service, clock);
        service.postOomAdjCompleted(0, Collections.singletonList(
                cachedSnapshot(PID, 1L, false, false)));
        service.fireDueAlarmsForTest();
        assertEquals(0, fake.freezeCalls);
        final PolicyDecision decision = service.getLastDecisionForTest(UID);
        assertNotNull(decision);
        assertEquals(Action.FREEZE, decision.action);
        assertTrue(decision.shadow);
        assertTrue(decision.dropped);
        assertEquals(0, service.getExecutedActionCountForTest());
        service.noteMemoryPressure(ApmConstants.PRESSURE_CRITICAL);
        assertEquals(0, fake.killCalls);
        assertEquals(0, fake.compactCalls);
    }

    @Test
    public void bigAppUsesLongerDebounce() {
        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 500_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        service.noteTopResumed(UID, PID, USER, PKG);
        service.noteTopResumed(-1, -1, -1, null);
        service.postOomAdjCompleted(0, Collections.singletonList(snapshot(PID, 1L,
                CACHED_ADJ, ActivityManager.PROCESS_STATE_CACHED_EMPTY, false, false,
                ApmConstants.BIG_APP_RSS_KB)));
        clock.now += ApmConstants.DEFAULT_FREEZE_DELAY_MS;
        service.fireDueAlarmsForTest();
        assertEquals(0, fake.freezeCalls);
        clock.now += ApmConstants.DEFAULT_BIG_APP_FREEZE_DELAY_MS;
        service.fireDueAlarmsForTest();
        assertEquals(1, fake.freezeCalls);
        assertTrue(service.isFrozenForTest(UID));
    }

    @Test
    public void criticalPressureKillsOneUidThenStops() {
        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 600_000L;
        final int[] pressure = new int[] { ApmConstants.PRESSURE_CRITICAL };
        final KernelKnobWriter knobs = new KernelKnobWriter(
                "/proc/apm-missing-fg-uids",
                "/sys/module/apm_missing/parameters/vm_swappiness");
        final AdaptiveProcessManagerService service = new AdaptiveProcessManagerService(
                clock, false /* startThread */, fake, knobs, () -> pressure[0]);
        final int other = UID + 1;
        final int top = UID + 2;
        final int visible = UID + 3;
        service.noteProcessStarted(PID, UID, USER, PKG, PKG, 1L, false /* persistent */);
        service.noteProcessStarted(PID + 1, other, USER, "com.example.other",
                "com.example.other", 1L, false /* persistent */);
        service.noteProcessStarted(PID + 2, top, USER, "com.example.top", "com.example.top",
                1L, false /* persistent */);
        service.noteProcessStarted(PID + 3, visible, USER, "com.example.vis", "com.example.vis",
                1L, false /* persistent */);
        service.noteProcessStarted(PID + 4, SYSTEM_UID, USER, "system", "android", 1L,
                true /* persistent */);
        service.noteTopResumed(top, PID + 2, USER, "com.example.top");
        clock.now += ApmConstants.DEFAULT_BIG_APP_FREEZE_DELAY_MS;
        service.postOomAdjCompleted(0, Arrays.asList(
                snapshotFor(PID, UID, PKG, 1L, false, false, 400_000L),
                snapshotFor(PID + 1, other, "com.example.other", 1L, false, false, 1_000L),
                snapshotFor(PID + 3, visible, "com.example.vis", 1L, true, false, 50_000L)));
        service.fireDueAlarmsForTest();
        assertEquals(0, fake.killCalls);
        service.noteMemoryPressure(ApmConstants.PRESSURE_CRITICAL);
        assertEquals(1, fake.killCalls);
        assertEquals(UID, (int) fake.killed.get(0));
        assertTrue(fake.lastKillReason.startsWith(ApmConstants.KILL_REASON_PREFIX));
        assertFalse(fake.killed.contains(top));
        assertFalse(fake.killed.contains(visible));
        assertFalse(fake.killed.contains(SYSTEM_UID));
        assertFalse(fake.killed.contains(other));
        pressure[0] = ApmConstants.PRESSURE_NORMAL;
        clock.now += ApmConstants.KILL_RECHECK_MS;
        service.fireDueAlarmsForTest();
        assertEquals(1, fake.killCalls);
    }

    @Test
    public void missingKernelFileDoesNotThrow() {
        final ManualClock clock = new ManualClock();
        clock.now = 1L;
        final KernelKnobWriter knobs = new KernelKnobWriter(
                "/proc/apm-missing-fg-uids",
                "/sys/module/apm_missing/parameters/vm_swappiness");
        final FakeExecutor fake = new FakeExecutor();
        final AdaptiveProcessManagerService service = new AdaptiveProcessManagerService(
                clock, false /* startThread */, fake, knobs, null /* pressure */);
        assertEquals("10123\n12\n", KernelKnobWriter.formatFgUids(new int[] {10123, 12}));
        assertEquals("\n", KernelKnobWriter.formatFgUids(new int[0]));
        assertEquals(ApmConstants.SWAPPINESS_DEFAULT,
                ApmConstants.swappinessForPressure(ApmConstants.PRESSURE_NORMAL));
        assertEquals(ApmConstants.SWAPPINESS_MODERATE,
                ApmConstants.swappinessForPressure(ApmConstants.PRESSURE_MODERATE));
        assertEquals(ApmConstants.SWAPPINESS_CRITICAL,
                ApmConstants.swappinessForPressure(ApmConstants.PRESSURE_CRITICAL));
        assertTrue(ApmConstants.SWAPPINESS_CRITICAL <= ApmConstants.SWAPPINESS_MAX);
        assertTrue(ApmConstants.SWAPPINESS_DEFAULT >= ApmConstants.SWAPPINESS_MIN);
        service.noteTopResumed(UID, PID, USER, PKG);
        service.noteMemoryPressure(ApmConstants.PRESSURE_MODERATE);
        service.noteMemoryPressure(ApmConstants.PRESSURE_NORMAL);
        assertTrue(knobs.getMissingCount() > 0);
        assertEquals(0, fake.killCalls);
    }

    private static void assertNoExecutionSurface(AdaptiveProcessManagerService service) {
        // Default construction has no cached-app optimizer and has not executed anything.
        // Freeze methods exist; they stay idle until the freezer flag is on and shadow is off.
        for (Field field : service.getClass().getDeclaredFields()) {
            final String type = field.getType().getName();
            assertFalse(field.getName(), type.contains("CachedAppOptimizer"));
            assertFalse(field.getName(), type.contains("ProcessRecord"));
        }
        assertEquals(0, service.getExecutedActionCountForTest());
    }

    private static AdaptiveProcessManagerService newService(ManualClock clock) {
        return new AdaptiveProcessManagerService(clock, false /* startThread */);
    }

    private static AdaptiveProcessManagerService openFreezer(ManualClock clock, FakeExecutor fake) {
        final AdaptiveProcessManagerService service = new AdaptiveProcessManagerService(
                clock, false /* startThread */, fake);
        service.setEnabledForTest(true);
        service.setShadowModeForTest(false);
        service.setFreezerEnabledForTest(true);
        return service;
    }

    /** Leave the uid in grace long enough that the next cached snapshot is past both debounces. */
    private static void settle(AdaptiveProcessManagerService service, ManualClock clock) {
        service.noteProcessStarted(PID, UID, USER, PKG, PKG, 1L, false /* persistent */);
        clock.now += ApmConstants.DEFAULT_BIG_APP_FREEZE_DELAY_MS;
    }

    private static ProcessSnapshot cachedSnapshot(int pid, long startSeq, boolean visible,
            boolean foregroundService) {
        return snapshot(pid, startSeq, CACHED_ADJ, ActivityManager.PROCESS_STATE_CACHED_EMPTY,
                visible, foregroundService, 0L /* rssKb */);
    }

    private static ProcessSnapshot snapshot(int pid, long startSeq, int adj, int procState,
            boolean visible, boolean foregroundService, long rssKb) {
        return new ProcessSnapshot(pid, UID, USER, PKG, PKG, startSeq, adj, procState,
                false /* persistent */, false /* foregroundActivities */, visible,
                foregroundService, rssKb, 0L /* swapKb */, false /* home */, false /* hasTask */,
                false /* forceStopped */);
    }

    private static ProcessSnapshot snapshotFor(int pid, int uid, String pkg, long startSeq,
            boolean visible, boolean foregroundService, long rssKb) {
        return new ProcessSnapshot(pid, uid, USER, pkg, pkg, startSeq, CACHED_ADJ,
                ActivityManager.PROCESS_STATE_CACHED_EMPTY, false /* persistent */,
                false /* foregroundActivities */, visible, foregroundService, rssKb,
                0L /* swapKb */, false /* home */, false /* hasTask */, false /* forceStopped */);
    }

    private static ApmProcessRecord record(int uid, boolean persistent) {
        final ApmProcessRecord rec = new ApmProcessRecord(uid);
        rec.persistent = persistent;
        rec.userId = USER;
        rec.packages.add(PKG);
        rec.initialized = true;
        return rec;
    }

    private static ProcessSnapshot cachedSnapshot(int pid, long startSeq) {
        return new ProcessSnapshot(pid, UID, USER, PKG, PKG, startSeq, CACHED_ADJ,
                ActivityManager.PROCESS_STATE_CACHED_EMPTY, false /* persistent */,
                false /* foregroundActivities */, false /* visibleActivities */,
                false /* foregroundService */);
    }

    private static final class FakeExecutor implements ApmExecutor {
        final ArraySet<Integer> failPids = new ArraySet<>();
        final ArrayList<Integer> frozen = new ArrayList<>();
        final ArrayList<Integer> unfrozen = new ArrayList<>();
        final ArrayList<Integer> killed = new ArrayList<>();
        final ArrayList<Integer> compacted = new ArrayList<>();
        int freezeCalls;
        int killCalls;
        int compactCalls;
        String lastKillReason;

        @Override
        public ApmFreezeResult freezeUid(int uid, int[] pids) {
            freezeCalls++;
            final List<Integer> ok = new ArrayList<>();
            final List<Integer> bad = new ArrayList<>();
            for (int i = 0; i < pids.length; i++) {
                if (failPids.contains(pids[i])) {
                    bad.add(pids[i]);
                } else {
                    ok.add(pids[i]);
                    frozen.add(pids[i]);
                }
            }
            return new ApmFreezeResult(toArray(ok), toArray(bad));
        }

        @Override
        public boolean unfreezeUid(int uid, int[] pids) {
            for (int i = 0; i < pids.length; i++) {
                unfrozen.add(pids[i]);
                frozen.remove(Integer.valueOf(pids[i]));
            }
            return true;
        }

        @Override
        public int compactUid(int uid, int[] pids) {
            compactCalls++;
            compacted.add(uid);
            return pids == null ? 0 : pids.length;
        }

        @Override
        public boolean killCachedUid(int uid, int[] pids, String reason) {
            killCalls++;
            lastKillReason = reason;
            if (reason == null || !reason.startsWith(ApmConstants.KILL_REASON_PREFIX)) {
                throw new AssertionError("bad kill reason " + reason);
            }
            killed.add(uid);
            return true;
        }

        private static int[] toArray(List<Integer> values) {
            final int[] out = new int[values.size()];
            for (int i = 0; i < values.size(); i++) {
                out[i] = values.get(i);
            }
            return out;
        }
    }

    private static final class ManualClock implements AdaptiveProcessManagerService.Clock {
        long now;

        @Override
        public long elapsedRealtime() {
            return now;
        }
    }
}
