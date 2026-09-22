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

import android.app.ActivityManager;

import com.android.server.am.ProcessList;
import com.android.server.am.apm.ApmConstants.ManagedState;
import com.android.server.am.apm.ApmEvent.ProcessSnapshot;
import com.android.server.am.apm.PolicyDecision.Action;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;

/**
 * Shadow-mode checks for adaptive process management. These tests do not freeze or kill.
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
        assertTrue(wouldAct.dropped);

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
    public void shadowRecordsDecisionAndHasNoFreezer() {
        final ManualClock clock = new ManualClock();
        clock.now = 20_000L;
        final AdaptiveProcessManagerService service = newService(clock);
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
        assertTrue(decision.shadow);
        assertTrue(decision.dropped);
        assertTrue(decision.freezeScore >= ApmConstants.FREEZE_SCORE_THRESHOLD);
        assertEquals(0, service.getExecutedActionCountForTest());
        assertTrue(service.getDroppedActionCountForTest() >= 1);
        assertNoExecutionSurface(service);

        final int recorded = service.getRecordedDecisionCountForTest();
        service.setEnabledForTest(false);
        service.noteTopResumed(UID, PID, USER, PKG);
        assertEquals(recorded, service.getRecordedDecisionCountForTest());
        assertEquals(Action.FREEZE, service.getLastDecisionForTest(UID).action);
        assertEquals(0, service.getExecutedActionCountForTest());

        service.setEnabledForTest(true);
        service.setShadowModeForTest(false);
        service.postOomAdjCompleted(0, Collections.singletonList(cachedSnapshot(PID, 1L)));
        final PolicyDecision forced = service.getLastDecisionForTest(UID);
        assertNotNull(forced);
        assertFalse(forced.shadow);
        assertTrue(forced.dropped);
        assertEquals(Action.FREEZE, forced.action);
        assertEquals(0, service.getExecutedActionCountForTest());
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

    private static void assertNoExecutionSurface(AdaptiveProcessManagerService service) {
        for (Field field : service.getClass().getDeclaredFields()) {
            final String type = field.getType().getName();
            assertFalse(field.getName(), type.contains("Freezer"));
            assertFalse(field.getName(), type.contains("CachedAppOptimizer"));
            assertFalse(field.getName(), type.contains("ProcessRecord"));
        }
        for (Method method : service.getClass().getDeclaredMethods()) {
            final String name = method.getName().toLowerCase(Locale.US);
            assertFalse(name, name.contains("freeze"));
            assertFalse(name, name.contains("kill"));
        }
    }

    private static AdaptiveProcessManagerService newService(ManualClock clock) {
        return new AdaptiveProcessManagerService(clock, false /* startThread */);
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

    private static final class ManualClock implements AdaptiveProcessManagerService.Clock {
        long now;

        @Override
        public long elapsedRealtime() {
            return now;
        }
    }
}
