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

import java.io.PrintWriter;
import java.io.StringWriter;
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
    /** A package that is in no navigation list and no clear-scene kill table. */
    private static final String OTHER_PKG = "com.example.map";
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

    @Test
    public void higherBanBeatsLowerAllow() {
        final ProtectionArbiter arbiter = new ProtectionArbiter();
        arbiter.put(AppProtectionPolicy.builder(PKG, USER, ProtectionArbiter.Layer.SYSTEM_SAFETY)
                .denyFreeze(true)
                .denyKill(true)
                .expiresElapsed(0L)
                .source("role")
                .reason("phone")
                .build());
        arbiter.put(AppProtectionPolicy.builder(PKG, USER, ProtectionArbiter.Layer.STATIC)
                .denyFreeze(false)
                .denyKill(false)
                .allowJobWakeup(true)
                .allowAlarmWakeup(true)
                .allowNetworkWhileFrozen(true)
                .source("config")
                .reason("lower-allow")
                .expiresElapsed(60_000L)
                .build());
        ProtectionArbiter.Merged merged = arbiter.merge(PKG, USER, 1_000L);
        assertTrue(merged.denyFreeze);
        assertTrue(merged.denyKill);
        assertEquals(ProtectionArbiter.Layer.SYSTEM_SAFETY, merged.denyFreezeLayer);
        assertEquals(ProtectionArbiter.Layer.SYSTEM_SAFETY, merged.denyKillLayer);
        assertEquals("(none)", merged.higherAllowsFreeze);
        assertEquals("(none)", merged.higherAllowsKill);
        assertTrue(merged.allowJobWakeup);

        arbiter.put(AppProtectionPolicy.builder(PKG, USER, ProtectionArbiter.Layer.DYNAMIC)
                .denyFreeze(false)
                .denyKill(false)
                .source("dynamic")
                .reason("predict-allow")
                .expiresElapsed(60_000L)
                .build());
        merged = arbiter.merge(PKG, USER, 1_000L);
        assertTrue(merged.denyFreeze);
        assertTrue(merged.denyKill);

        arbiter.setUserForceStop(PKG, USER, true);
        merged = arbiter.merge(PKG, USER, 1_000L);
        assertTrue(merged.denyFreeze);
        assertTrue(merged.denyKill);
        assertTrue(merged.forceStopped);
        assertFalse(merged.allowJobWakeup);
        assertFalse(merged.allowAlarmWakeup);
        assertFalse(arbiter.shouldSpareCachedKill(PKG, USER, 1_000L, false));
        assertFalse(arbiter.shouldSpareCachedKill(PKG, USER, 1_000L, true));

        final ProtectionArbiter locked = new ProtectionArbiter();
        locked.setUserLocked(PKG, USER, true);
        final ProtectionArbiter.Merged lockMerged = locked.merge(PKG, USER, 1_000L);
        assertFalse(lockMerged.denyKill);
        assertFalse(lockMerged.denyFreeze);
        assertTrue(lockMerged.userLocked);
        assertTrue(lockMerged.protectionScore > 0);
        assertTrue(lockMerged.freezeDelayExtraMs > 0L);

        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 700_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        settle(service, clock);
        service.postOomAdjCompleted(0, Collections.singletonList(
                cachedSnapshot(PID, 1L, false, false)));
        service.fireDueAlarmsForTest();
        assertTrue(service.isFrozenForTest(UID));
        final int freezes = fake.freezeCalls;
        service.setProtectionForTest(AppProtectionPolicy.builder(
                PKG, USER, ProtectionArbiter.Layer.SYSTEM_SAFETY)
                .denyFreeze(true)
                .denyKill(true)
                .expiresElapsed(0L)
                .source("role")
                .reason("phone")
                .build());
        assertFalse(service.isFrozenForTest(UID));
        assertEquals("deny-freeze", service.getLastFreezeDetailForTest(UID));
        service.setProtectionForTest(AppProtectionPolicy.builder(
                PKG, USER, ProtectionArbiter.Layer.STATIC)
                .denyFreeze(false)
                .denyKill(false)
                .allowJobWakeup(true)
                .source("config")
                .reason("lower-allow")
                .expiresElapsed(clock.now + 60_000L)
                .build());
        service.postOomAdjCompleted(0, Collections.singletonList(
                cachedSnapshot(PID, 1L, false, false)));
        service.fireDueAlarmsForTest();
        assertFalse(service.isFrozenForTest(UID));
        assertEquals(freezes, fake.freezeCalls);
        assertTrue(service.getArbiterForTest().merge(PKG, USER, clock.now).denyFreeze);
        assertTrue(service.getArbiterForTest().merge(PKG, USER, clock.now).denyKill);
        final int kills = fake.killCalls;
        service.noteMemoryPressure(ApmConstants.PRESSURE_CRITICAL);
        assertEquals(kills, fake.killCalls);

        final StringWriter sw = new StringWriter();
        service.dump(new PrintWriter(sw));
        final String dump = sw.toString();
        assertTrue(dump.contains("layer=SYSTEM_SAFETY"));
        assertTrue(dump.contains("layer=STATIC"));
        assertTrue(dump.contains("denyFreeze=true"));
        assertTrue(dump.contains("denyKill=true"));
        assertTrue(dump.contains("higher layers that still allow freeze: (none)"));
    }

    @Test
    public void sceneSkipDoesNotApplyToADifferentCaller() {
        final ClearSceneTable table = ClearSceneTable.get();
        assertEquals(ClearSceneTable.SCENE_COUNT, table.sceneCount());
        assertEquals(300, ClearSceneTable.ATHENA_LMK_ADJ_THRESHOLD);
        assertEquals(1380, ClearSceneTable.athenaLmkMemMb(4));
        assertEquals(400, ClearSceneTable.sappShouldKillMb("com.tencent.mm"));
        assertEquals(-1, ClearSceneTable.sappShouldKillMb("com.example.app"));
        assertNull(table.select("clear_spec#config_1"));
        assertEquals(2, table.scenesForCaller(
                "com.oplus.battery.safety.hightemperature").size());
        assertNull(table.select("com.oplus.battery.safety.hightemperature"));

        final ClearScene camera = table.select("com.oplus.camera");
        final ClearScene tournament = table.select("com.oplus.games.tournamentmode");
        final ClearScene oguardKill = table.select("android.oguard.kill");
        final ClearScene oguardAudio = table.select("android.oguard.abnormalAudio.kill");
        assertNotNull(camera);
        assertNotNull(tournament);
        assertNotNull(oguardKill);
        assertNotNull(oguardAudio);
        assertEquals("one_key#config_10", camera.name);
        assertEquals("one_key#config_15", tournament.name);
        assertEquals("clear_spec#config_1", oguardKill.name);
        assertEquals("clear_spec#config_1", oguardAudio.name);
        assertTrue(camera.flag("cc_skip_bluetooth"));
        assertTrue(tournament.has("cc_skip_bluetooth"));
        assertFalse(tournament.flag("cc_skip_bluetooth"));
        assertFalse(oguardKill.flag("cc_skip_recent_lock"));
        assertTrue(oguardAudio.flag("cc_skip_recent_lock"));
        assertEquals(200, oguardKill.number("cc_skip_system_process_max_adj", -1));
        assertEquals(99, oguardAudio.number("cc_skip_system_process_max_adj", -1));
        assertEquals(400, table.fastClear("com.oplus.camera.camera_startup").delta);
        assertEquals(1, (int) table.externalStrategy("android.ams.provider"));

        final ClearSceneRunner.Facts locked = new ClearSceneRunner.Facts();
        locked.recentLock = true;
        assertFalse(ClearSceneRunner.skipped(oguardKill, locked));
        assertTrue(ClearSceneRunner.skipped(oguardAudio, locked));

        final ClearSceneRunner.Facts bluetooth = new ClearSceneRunner.Facts();
        bluetooth.bluetooth = true;
        assertTrue(ClearSceneRunner.skipped(camera, bluetooth));
        assertFalse(ClearSceneRunner.skipped(tournament, bluetooth));

        final ClearSceneRunner.Facts white = new ClearSceneRunner.Facts();
        white.athenaWhiteBits = 1;
        final ClearScene lmk = table.select("athena_lmk");
        assertTrue(ClearSceneRunner.skipped(lmk, white));
        white.athenaWhiteBits = 0;
        white.athenaWhiteNewBits = 65536;
        assertTrue(ClearSceneRunner.skipped(lmk, white));
        white.athenaWhiteNewBits = 0;
        assertFalse(ClearSceneRunner.skipped(lmk, white));

        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 800_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        settle(service, clock);
        service.postOomAdjCompleted(0, Collections.singletonList(
                cachedSnapshot(PID, 1L, false, false)));
        service.fireDueAlarmsForTest();
        service.setProtectionForTest(AppProtectionPolicy.builder(
                PKG, USER, ProtectionArbiter.Layer.SYSTEM_SAFETY)
                .denyFreeze(true)
                .denyKill(true)
                .expiresElapsed(0L)
                .source("role")
                .reason("phone")
                .build());
        final int sceneKills = fake.sceneKills;
        service.runClearScene("android.oguard.kill");
        assertEquals(sceneKills, fake.sceneKills);
        service.runClearScene("com.oplus.camera");
        assertEquals(sceneKills, fake.sceneKills);
    }

    @Test
    public void previousAppAdjNeverExceedsThreeSlots() {
        final ArrayList<RecentAdjPolicy.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate(0, "android", "system", 1000, true, true, true));
        for (int i = 0; i < 5; i++) {
            candidates.add(candidate(i + 1, "com.app" + i, "com.app" + i, 10100 + i,
                    false, true, true));
        }
        final List<RecentAdjPolicy.Assignment> plan = RecentAdjPolicy.assign(candidates, 10_000L);
        int previous = 0;
        final int[] slots = new int[3];
        int recent = 0;
        for (int i = 0; i < plan.size(); i++) {
            final RecentAdjPolicy.Assignment assignment = plan.get(i);
            if (assignment.previous()) {
                assertTrue(previous < RecentAdjPolicy.PREVIOUS_MAX);
                slots[previous] = assignment.previousAdj;
                assertFalse(assignment.previousAdj == 500);
                assertFalse(assignment.previousAdj == 800);
                previous++;
            }
            if (assignment.recentAdj >= 0) {
                recent++;
            }
        }
        assertEquals(3, previous);
        assertEquals(450, slots[0]);
        assertEquals(475, slots[1]);
        assertEquals(704, slots[2]);
        assertEquals(RecentAdjPolicy.RECENT_MAX, recent);
        assertEquals(5, RecentAdjPolicy.RECENT_TASK_SLOTS.length);

        final List<RecentAdjPolicy.Assignment> instant = RecentAdjPolicy.assign(
                Collections.singletonList(candidate(0, "com.oppo.instant.local.service",
                        "com.oppo.instant.local.service", 10111, false, false, false)),
                10_000L);
        assertEquals(1, instant.size());
        assertEquals(480, instant.get(0).adj);
        assertEquals("com.oppo.instant.local.service/com.oppo.instant.local.service",
                instant.get(0).staticRule);
        int runningMemMb = -1;
        final RecentAdjPolicy.StaticRule[] rules = RecentAdjPolicy.staticRules();
        for (int i = 0; i < rules.length; i++) {
            if ("com.oppo.instant.local.service".equals(rules[i].packageName)) {
                runningMemMb = rules[i].runningMemMb;
                assertEquals(480, rules[i].adj);
                assertEquals(-1, rules[i].minRamGb);
            }
            if ("com.tencent.tmgp.sgame".equals(rules[i].packageName)) {
                assertEquals(12, rules[i].minRamGb);
            }
        }
        assertEquals(10, runningMemMb);

        final List<RecentAdjPolicy.Assignment> openid = RecentAdjPolicy.assign(
                Collections.singletonList(candidate(0, "com.heytap.openid", "com.heytap.openid",
                        10112, true, false, false)),
                10_000L);
        assertEquals(1, openid.size());
        assertEquals(200, openid.get(0).adj);
        assertFalse(openid.get(0).previous());
    }

    @Test
    public void taskRestoreRewritesForceStopAndDoesNotKeepTheProcess() {
        final TaskRestoreController tasks = new TaskRestoreController();
        assertFalse(tasks.keepsProcessResident());
        assertEquals(0, tasks.restartServiceCount());
        assertEquals(3, tasks.cleanType());
        assertEquals(TaskRestoreController.CLEAN_FORCE_STOP,
                tasks.rewrite(null, 0, 10123, TaskRestoreController.CLEAN_FORCE_STOP, true, false));
        assertEquals(TaskRestoreController.CLEAN_KEEP_TASK,
                tasks.rewrite("com.example.app", 0, 10123, TaskRestoreController.CLEAN_FORCE_STOP,
                        true /* icon */, false /* system */));
        assertEquals(TaskRestoreController.CLEAN_FORCE_STOP,
                tasks.rewrite("com.example.app", 0, 10123, 4, true, false));
        tasks.noteRuntimeForceStop("com.example.app", 0);
        assertEquals(TaskRestoreController.CLEAN_FORCE_STOP,
                tasks.rewrite("com.example.app", 0, 10123, TaskRestoreController.CLEAN_FORCE_STOP,
                        true, false));
        tasks.clearRuntimeForceStop("com.example.app", 0);
        assertEquals(TaskRestoreController.CLEAN_FORCE_STOP,
                tasks.rewrite("android", 0, 1000, TaskRestoreController.CLEAN_FORCE_STOP,
                        true, true));
        assertEquals(TaskRestoreController.CLEAN_FORCE_STOP,
                tasks.rewrite("com.example.sys", 0, 1000, TaskRestoreController.CLEAN_FORCE_STOP,
                        true, true));
        assertEquals(TaskRestoreController.CLEAN_KEEP_TASK,
                tasks.rewrite("com.oplus.example", 0, 1000, TaskRestoreController.CLEAN_FORCE_STOP,
                        true, true));
        assertEquals(TaskRestoreController.CLEAN_FORCE_STOP,
                tasks.rewrite("com.example.app", 0, 10123, TaskRestoreController.CLEAN_FORCE_STOP,
                        false /* no icon */, false));
    }

    @Test
    public void categoryOneRevivalDoesNotConsumeASlot() {
        final RevivalController revival = new RevivalController();
        final long now = 1_000_000L;
        for (int i = 0; i < RevivalController.CATEGORY_ONE.length; i++) {
            final RevivalController.Result result = revival.request(
                    RevivalController.CALLER_PACKAGE, RevivalController.CALLER_ACTION,
                    RevivalController.CATEGORY_ONE[i], 0, now, false /* shadow */,
                    false /* forceStopped */);
            assertTrue(RevivalController.CATEGORY_ONE[i], result.accepted);
            assertTrue(result.applied);
            assertTrue(result.categoryOne);
            assertEquals(RevivalController.REASON_OK, result.reason);
        }
        assertEquals(0, revival.slotsUsed(now));
        assertEquals(0, revival.countUsed(now));
        assertEquals(0L, revival.energyUsed(now));
        assertEquals(RevivalController.BUMP_ADJ,
                revival.bumpAdj("com.tencent.mobileqq", 0, now, false));
        assertEquals(-1, revival.bumpAdj("com.tencent.mobileqq", 0, now, true));

        final RevivalController.Result other = revival.request(
                RevivalController.CALLER_PACKAGE, RevivalController.CALLER_ACTION,
                "com.example.other", 0, now, false, false);
        assertTrue(other.accepted);
        assertFalse(other.categoryOne);
        assertEquals(1, revival.slotsUsed(now));
        assertEquals(1, revival.countUsed(now));
        assertEquals(RevivalController.ENERGY_PER_CONSUMING_GRANT, revival.energyUsed(now));

        final RevivalController.Result mcs = revival.request(
                RevivalController.CALLER_PACKAGE, RevivalController.CALLER_ACTION,
                RevivalController.CALLER_PACKAGE, 0, now, false, false);
        assertFalse(mcs.accepted);
        assertEquals(RevivalController.REASON_TARGET, mcs.reason);

        final long later = now + RevivalBudget.DEFAULT_THRESHOLD_SEC * 1000L;
        assertEquals(0, revival.slotsUsed(later));
        assertEquals(RevivalController.REASON_APP_REVIVAL_SLOT_SIZE_RELEASE,
                revival.lastReleaseReason());
    }

    @Test
    public void forceStopBlocksRevival() {
        final RevivalController revival = new RevivalController();
        final long now = 2_000_000L;
        revival.noteForceStop("com.tencent.mobileqq", 0, true);
        final RevivalController.Result blocked = revival.request(
                RevivalController.CALLER_PACKAGE, RevivalController.CALLER_ACTION,
                "com.tencent.mobileqq", 0, now, false, false);
        assertFalse(blocked.accepted);
        assertEquals(RevivalController.REASON_FORCE_STOP, blocked.reason);
        assertEquals(0, revival.slotsUsed(now));
        assertEquals(-1, revival.bumpAdj("com.tencent.mobileqq", 0, now, false));

        final RevivalController.Result flagged = revival.request(
                RevivalController.CALLER_PACKAGE, RevivalController.CALLER_ACTION,
                "com.ss.android.lark", 0, now, false, true /* forceStopped */);
        assertFalse(flagged.accepted);
        assertEquals(0, revival.slotsUsed(now));

        final ManualClock clock = new ManualClock();
        clock.now = now;
        final AdaptiveProcessManagerService service = newService(clock);
        service.noteUserForceStop("com.alibaba.android.rimet", 0);
        final RevivalController.Result viaService = service.requestRevival(
                RevivalController.CALLER_PACKAGE, RevivalController.CALLER_ACTION,
                "com.alibaba.android.rimet", 0);
        assertFalse(viaService.accepted);
        assertEquals(RevivalController.REASON_FORCE_STOP, viaService.reason);
        service.setShadowModeForTest(true);
        service.noteUserForceStop("com.alibaba.android.rimet", 0);
        // Force-stop still blocks in shadow. A clear of that row is not done here.
        final RevivalController.Result shadow = service.requestRevival(
                RevivalController.CALLER_PACKAGE, RevivalController.CALLER_ACTION,
                "com.tencent.wework", 0);
        assertTrue(shadow.accepted);
        assertFalse(shadow.applied);
        assertEquals(0, service.getRevivalForTest().slotsUsed(clock.now));
    }

    @Test
    public void exemptionBlackBeatsWhiteAndIsNotDenyKill() {
        final ComponentExemptionTable table = new ComponentExemptionTable();
        assertTrue(table.mayDeliver(ComponentExemptionTable.Kind.ACTIVITY, "com.a", "com.b",
                "C", 0));
        assertTrue(table.isDependency(ComponentExemptionTable.DEPENDENCY_BACKUP));
        assertTrue(table.isDozeWhite(ComponentExemptionTable.DOZE_WHITE));
        assertTrue(table.ignoresProxyWakelock(ComponentExemptionTable.WAKELOCK_AUDIO_MIX));
        assertTrue(table.ignoresProxyWakelock(ComponentExemptionTable.WAKELOCK_AUDIO_SPATIAL));
        assertEquals(ComponentExemptionTable.DEFAULT_FF_TIMEOUT,
                table.fastFreezeTimeout("com.example.app"));
        assertFalse(table.inFastFreezeWhite("com.example.app"));
        assertFalse(table.skipFastFreeze("com.example.app"));
        assertTrue(ComponentExemptionTable.isWhitelistApp(
                ComponentExemptionTable.APP_CLASS_THIRD_WHITE));
        assertTrue(ComponentExemptionTable.isWhitelistApp(
                ComponentExemptionTable.APP_CLASS_OPLUS_WHITE));
        assertFalse(ComponentExemptionTable.isWhitelistApp(ComponentExemptionTable.APP_CLASS_GMS));
        assertFalse(ComponentExemptionTable.isWhitelistApp(
                ComponentExemptionTable.APP_CLASS_PROTECT));

        table.put(ComponentExemptionTable.Kind.ACTIVITY, true /* calling */, false /* black */,
                "com.caller", "Target");
        assertEquals(ComponentExemptionTable.Decision.ALLOW,
                table.check(ComponentExemptionTable.Kind.ACTIVITY, true, "com.caller", "Target",
                        0));
        table.put(ComponentExemptionTable.Kind.ACTIVITY, true, true /* black */,
                "com.caller", "Target");
        assertEquals(ComponentExemptionTable.Decision.DENY,
                table.check(ComponentExemptionTable.Kind.ACTIVITY, true, "com.caller", "Target",
                        0));
        assertFalse(table.mayDeliver(ComponentExemptionTable.Kind.ACTIVITY, "com.caller",
                "com.other", "Target", 0));

        final int mask = ComponentExemptionTable.MASK_KILL_WHITE
                | ComponentExemptionTable.MASK_JOB_WHITE
                | ComponentExemptionTable.MASK_SYNC_JOB_BLACK
                | ComponentExemptionTable.MASK_KEEP_ALIVE_WHITE
                | ComponentExemptionTable.MASK_KEEP_ALIVE_BLACK
                | ComponentExemptionTable.MASK_SKIP_FROZEN_WHITE;
        final AppProtectionPolicy policy = table.maskPolicy(PKG, USER, mask);
        assertTrue(policy.denyFreeze);
        assertFalse(policy.denyKill);
        assertFalse(policy.allowJobWakeup);
        assertFalse(policy.taskRestore);
        assertTrue(policy.protectionScore > 0);
        final ProtectionArbiter arbiter = new ProtectionArbiter();
        arbiter.put(policy);
        final ProtectionArbiter.Merged merged = arbiter.merge(PKG, USER, 1L);
        assertTrue(merged.denyFreeze);
        assertFalse(merged.denyKill);
        assertFalse(table.jobAllowed(PKG, "Job"));
        assertTrue(table.jobDenied(PKG));
    }

    @Test
    public void currentInputMethodRoleDeniesFreeze() {
        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 920_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        settle(service, clock);
        service.noteCurrentInputMethodForTest(USER, PKG);
        service.postOomAdjCompleted(0, Collections.singletonList(
                cachedSnapshot(PID, 1L, false, false)));
        service.fireDueAlarmsForTest();
        assertFalse(service.isFrozenForTest(UID));
        assertEquals(0, fake.freezeCalls);
        final ProtectionArbiter.Merged merged =
                service.getArbiterForTest().merge(PKG, USER, clock.now);
        assertTrue(merged.denyFreeze);
        assertTrue(merged.denyKill);
        final StringWriter sw = new StringWriter();
        service.getArbiterForTest().dumpPackage(new PrintWriter(sw), PKG, USER, clock.now);
        assertTrue(sw.toString().contains("source=role"));
        final ClearSceneRunner.Facts facts = new ClearSceneRunner.Facts();
        service.fillClearFacts(facts, PKG, USER, false /* liveAudio */);
        assertTrue(facts.inputMethod);
        assertTrue(ClearSceneRunner.skipped(ClearSceneTable.get().select("athena_lmk"), facts));
    }

    @Test
    public void providerBlackListIsDecidedBeforePublish() {
        final AdaptiveProcessManagerService service = newService(new ManualClock());
        service.exemptions().put(ComponentExemptionTable.Kind.PROVIDER, false /* calling */,
                true /* black */, "com.target", "BlockedProvider");
        final boolean[] published = new boolean[] {false};
        assertFalse(ProviderPublishGate.publishIfAllowed(
                service.mayDeliver(ComponentExemptionTable.Kind.PROVIDER, "com.caller",
                        "com.target", "BlockedProvider"),
                () -> published[0] = true));
        assertFalse(published[0]);
        assertTrue(ProviderPublishGate.publishIfAllowed(
                service.mayDeliver(ComponentExemptionTable.Kind.PROVIDER, "com.caller",
                        "com.free", "BlockedProvider"),
                () -> published[0] = true));
        assertTrue(published[0]);
    }

    @Test
    public void sappShouldBeKillDoesNotKillWhenMemoryIsAboveThreshold() {
        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 930_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        final String pkg = "com.tencent.mm";
        service.noteProcessStarted(PID, UID, USER, pkg, pkg, 1L, false /* persistent */);
        clock.now += ApmConstants.DEFAULT_BIG_APP_FREEZE_DELAY_MS;
        service.postOomAdjCompleted(0, Collections.singletonList(
                snapshotFor(PID, UID, pkg, 1L, false, false, 1_000L)));
        service.fireDueAlarmsForTest();
        service.setAvailableBytesForTest(401L * 1024L * 1024L);
        final int sceneKills = fake.sceneKills;
        final int kills = fake.killCalls;
        service.runClearScene("athena_lmk");
        assertFalse(service.athenaLmkKillsPackage(pkg, CACHED_ADJ));
        assertEquals(sceneKills, fake.sceneKills);
        assertEquals(kills, fake.killCalls);
    }

    @Test
    public void gnssWithLocationFgsProtectsAfterTheEnterDebounce() {
        final ManualClock clock = new ManualClock();
        clock.now = 1_000_000L;
        final AdaptiveProcessManagerService service = newService(clock);
        service.noteProcessStarted(PID, UID, USER, PKG, PKG, 1L, false /* persistent */);
        service.noteGnssClientChanged(UID, PKG, true /* active */);
        service.postOomAdjCompleted(0, Collections.singletonList(
                snapshotWithLocationFgs(PID, 1L)));
        assertEquals(NavigationProtectionController.State.CANDIDATE, navState(service));
        assertFalse(service.isNavigatingForTest(UID));

        clock.now += ApmConstants.DEFAULT_NAVIGATION_ENTER_DEBOUNCE_MS - 1;
        service.fireDueAlarmsForTest();
        assertFalse(service.isNavigatingForTest(UID));

        clock.now += 1;
        service.fireDueAlarmsForTest();
        assertEquals(NavigationProtectionController.State.ACTIVE, navState(service));
        assertTrue(service.isNavigatingForTest(UID));
        assertTrue(service.getArbiterForTest().merge(PKG, USER, clock.now).denyFreeze);

        // The state has to be visible from dumpsys activity apm, not just in-process.
        final StringWriter sw = new StringWriter();
        service.dump(new PrintWriter(sw));
        final String dump = sw.toString();
        assertTrue(dump.contains("navigation enabled=true"));
        assertTrue(dump.contains("protected=1"));
        assertTrue(dump.contains("state=ACTIVE"));
    }

    @Test
    public void navigationProtectionBlocksTheFreezeTheSameFactsWouldOtherwiseCause() {
        // Control: the same cached facts without GNSS freeze.
        final FakeExecutor controlFake = new FakeExecutor();
        final ManualClock controlClock = new ManualClock();
        controlClock.now = 1_100_000L;
        final AdaptiveProcessManagerService control = openFreezer(controlClock, controlFake);
        serviceNoteCachedProcess(control, controlClock);
        assertTrue(control.isFrozenForTest(UID));

        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 1_200_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        serviceNoteCachedProcess(service, clock);
        confirmNavigation(service, clock);
        assertTrue(service.isNavigatingForTest(UID));
        assertFalse("a navigating uid is thawed and stays thawed", service.isFrozenForTest(UID));

        // A later cached pass must not freeze it again while the session holds.
        final int freezes = fake.freezeCalls;
        service.postOomAdjCompleted(0, Collections.singletonList(
                snapshotWithLocationFgs(PID, 1L)));
        service.fireDueAlarmsForTest();
        assertFalse(service.isFrozenForTest(UID));
        assertEquals(freezes, fake.freezeCalls);
    }

    @Test
    public void gnssLossEntersGraceAndTheGraceDeadlineReturnsToInactive() {
        final ManualClock clock = new ManualClock();
        clock.now = 2_000_000L;
        final AdaptiveProcessManagerService service = newService(clock);
        confirmNavigation(service, clock);
        assertTrue(service.isNavigatingForTest(UID));

        service.noteGnssClientChanged(UID, PKG, false /* active */);
        assertEquals(NavigationProtectionController.State.GRACE, navState(service));
        assertTrue("grace is still protected", service.isNavigatingForTest(UID));

        clock.now += ApmConstants.DEFAULT_NAVIGATION_EXIT_GRACE_MS - 1;
        service.fireDueAlarmsForTest();
        assertTrue(service.isNavigatingForTest(UID));

        clock.now += 1;
        service.fireDueAlarmsForTest();
        assertEquals(NavigationProtectionController.State.INACTIVE, navState(service));
        assertFalse(service.isNavigatingForTest(UID));
        assertFalse(service.getArbiterForTest().merge(PKG, USER, clock.now).denyFreeze);
    }

    @Test
    public void allowlistedPackageNeedsRecentTopAndADeniedPackageNeverConfirms() {
        final int deniedUid = UID + 1;
        final String deniedPkg = "com.example.locationads";
        final ManualClock clock = new ManualClock();
        clock.now = 3_000_000L;
        final AdaptiveProcessManagerService service = newService(clock);
        final ArraySet<String> deny = new ArraySet<>();
        deny.add(deniedPkg);
        service.setNavigationConfigForTest(NavigationPolicyConfig.defaults().withDenylist(deny));

        // Allowlist hit with recent top and no location foreground service.
        service.noteTopResumed(UID, PID, USER, "com.autonavi.minimap");
        service.noteGnssClientChanged(UID, "com.autonavi.minimap", true /* active */);
        clock.now += ApmConstants.DEFAULT_NAVIGATION_ENTER_DEBOUNCE_MS;
        service.fireDueAlarmsForTest();
        assertTrue(service.isNavigatingForTest(UID));

        // Denied package: GNSS and recent top are not enough.
        service.noteTopResumed(deniedUid, PID + 1, USER, deniedPkg);
        service.noteGnssClientChanged(deniedUid, deniedPkg, true /* active */);
        clock.now += ApmConstants.DEFAULT_NAVIGATION_ENTER_DEBOUNCE_MS;
        service.fireDueAlarmsForTest();
        assertFalse(service.isNavigatingForTest(deniedUid));
        assertEquals(NavigationProtectionController.State.INACTIVE, navStateFor(service, deniedUid));

        // The allowlist confirmation lapses with the recency window.
        clock.now += ApmConstants.DEFAULT_NAVIGATION_RECENT_TOP_MS + 1;
        service.fireDueAlarmsForTest();
        assertEquals(NavigationProtectionController.State.GRACE, navState(service));
        clock.now += ApmConstants.DEFAULT_NAVIGATION_EXIT_GRACE_MS;
        service.fireDueAlarmsForTest();
        assertFalse(service.isNavigatingForTest(UID));
    }

    @Test
    public void navigatingUidIsSkippedByTheAthenaLmkScene() {
        final int otherUid = UID + 1;
        final FakeExecutor fake = new FakeExecutor();
        final ManualClock clock = new ManualClock();
        clock.now = 4_000_000L;
        final AdaptiveProcessManagerService service = openFreezer(clock, fake);
        service.noteProcessStarted(PID, UID, USER, PKG, PKG, 1L, false /* persistent */);
        service.noteProcessStarted(PID + 1, otherUid, USER, OTHER_PKG, OTHER_PKG, 1L,
                false /* persistent */);
        clock.now += ApmConstants.DEFAULT_BIG_APP_FREEZE_DELAY_MS;
        service.noteGnssClientChanged(UID, PKG, true /* active */);
        service.postOomAdjCompleted(0, Arrays.asList(snapshotWithLocationFgs(PID, 1L),
                cachedSnapshotFor(PID + 1, otherUid, OTHER_PKG, 1L)));
        clock.now += ApmConstants.DEFAULT_NAVIGATION_ENTER_DEBOUNCE_MS;
        service.fireDueAlarmsForTest();
        assertTrue(service.isNavigatingForTest(UID));

        service.runClearScene("athena_lmk");
        assertTrue("the uid without navigation facts is the scene victim",
                fake.killed.contains(otherUid));
        assertFalse(fake.killed.contains(UID));
    }

    @Test
    public void navigationAdjClampAppliesOnlyWhileProtectedAndEnabled() {
        final ManualClock clock = new ManualClock();
        clock.now = 5_000_000L;
        final AdaptiveProcessManagerService service = newService(clock);
        assertEquals(-1, service.navigationAdjClamp(PKG, USER));
        assertEquals(-1, service.navigationAdjClamp(null, USER));

        confirmNavigation(service, clock);
        assertEquals(ProcessList.PERCEPTIBLE_APP_ADJ, service.navigationAdjClamp(PKG, USER));
        assertEquals("another package in the same uid is not clamped", -1,
                service.navigationAdjClamp(OTHER_PKG, USER));

        service.setShadowModeForTest(true);
        assertEquals(-1, service.navigationAdjClamp(PKG, USER));
        service.setShadowModeForTest(false);
        service.setEnabledForTest(false);
        assertEquals(-1, service.navigationAdjClamp(PKG, USER));
    }

    @Test
    public void alwaysExemptPackageIsSparedKeptAndGivenAnAdjFloor() {
        final ManualClock clock = new ManualClock();
        clock.now = 5_100_000L;
        final AdaptiveProcessManagerService service = newService(clock);
        service.setEnabledForTest(true);
        service.setShadowModeForTest(false);

        final String exempt = "com.xiaomi.xmsf";
        assertTrue(ProtectionArbiter.isAlwaysExempt(exempt));
        assertFalse(ProtectionArbiter.isAlwaysExempt(PKG));

        // The row is built in: nothing has to register, start, or install anything first.
        final ProtectionArbiter.Merged merged =
                service.getArbiterForTest().merge(exempt, USER, clock.now);
        assertTrue(merged.denyFreeze);
        assertTrue(merged.denyKill);
        assertTrue(merged.allowNetworkWhileFrozen);
        assertEquals(ProtectionArbiter.Layer.SYSTEM_SAFETY, merged.denyFreezeLayer);
        assertTrue(service.getArbiterForTest().shouldSpareCachedKill(exempt, USER, clock.now,
                false /* processForceStopped */));

        assertEquals(ProcessList.SERVICE_ADJ, service.alwaysExemptAdjFloor(exempt));
        assertEquals(-1, service.alwaysExemptAdjFloor(PKG));
        assertEquals(-1, service.alwaysExemptAdjFloor(null));

        // A user force-stop and the user's own background restriction still win.
        assertFalse(service.getArbiterForTest().shouldSpareCachedKill(exempt, USER, clock.now,
                true /* processForceStopped */));
    }

    private static RecentAdjPolicy.Candidate candidate(int index, String pkg, String process,
            int uid, boolean system, boolean previous, boolean recent) {
        return new RecentAdjPolicy.Candidate(index, pkg, process, 0 /* user */, uid, system,
                previous, recent, 920 /* curAdj */, 0L, 0L, 8 /* ramGb */);
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

    /**
     * GNSS plus a location foreground service, held past the enter debounce. The adj pass
     * carries the location fact, so this is the whole evidence chain.
     */
    private static void confirmNavigation(AdaptiveProcessManagerService service, ManualClock clock) {
        service.noteGnssClientChanged(UID, PKG, true /* active */);
        service.postOomAdjCompleted(0, Collections.singletonList(
                snapshotWithLocationFgs(PID, 1L)));
        clock.now += ApmConstants.DEFAULT_NAVIGATION_ENTER_DEBOUNCE_MS;
        service.fireDueAlarmsForTest();
    }

    /** A cached uid the freezer takes: no foreground facts, no foreground service. */
    private static void serviceNoteCachedProcess(AdaptiveProcessManagerService service,
            ManualClock clock) {
        settle(service, clock);
        service.postOomAdjCompleted(0, Collections.singletonList(
                cachedSnapshot(PID, 1L, false /* visible */, false /* foregroundService */)));
        service.fireDueAlarmsForTest();
    }

    private static ProcessSnapshot snapshotWithLocationFgs(int pid, long startSeq) {
        return new ProcessSnapshot(pid, UID, USER, PKG, PKG, startSeq, CACHED_ADJ,
                ActivityManager.PROCESS_STATE_CACHED_EMPTY, false /* persistent */,
                false /* foregroundActivities */, false /* visibleActivities */,
                false /* foregroundService */, 0L /* rssKb */, 0L /* swapKb */,
                false /* home */, false /* hasTask */, false /* forceStopped */,
                false /* foregroundAudio */, true /* locationFgs */);
    }

    private static ProcessSnapshot cachedSnapshotFor(int pid, int uid, String pkg, long startSeq) {
        return new ProcessSnapshot(pid, uid, USER, pkg, pkg, startSeq, CACHED_ADJ,
                ActivityManager.PROCESS_STATE_CACHED_EMPTY, false /* persistent */,
                false /* foregroundActivities */, false /* visibleActivities */,
                false /* foregroundService */, 0L /* rssKb */, 0L /* swapKb */,
                false /* home */, false /* hasTask */, false /* forceStopped */,
                false /* foregroundAudio */, false /* locationFgs */);
    }

    /** Null when the controller holds no record for the uid. */
    private static NavigationProtectionController.State navStateFor(
            AdaptiveProcessManagerService service, int uid) {
        final List<NavigationProtectionController.Snapshot> snapshots = service.navigationSnapshots();
        for (int i = 0; i < snapshots.size(); i++) {
            if (snapshots.get(i).uid == uid) {
                return snapshots.get(i).state;
            }
        }
        return null;
    }

    private static NavigationProtectionController.State navState(
            AdaptiveProcessManagerService service) {
        return navStateFor(service, UID);
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
        int sceneKills;
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

        @Override
        public boolean killForScene(int uid, int[] pids, String reason) {
            sceneKills++;
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
