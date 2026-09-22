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
import static org.junit.Assert.assertTrue;

import android.net.ConnectivityManager;
import android.os.Process;
import android.util.ArraySet;

import com.android.server.am.ProcessList;
import com.android.server.am.apm.ApmProcessRecord.PidSlot;

import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Network cut checks. The backend records the (uid, rule) calls it receives, so every check
 * here is about the order of firewall writes, not about a log line.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksServicesTests:NetworkFreezeControllerTest
 */
public class NetworkFreezeControllerTest {
    private static final int UID = 10123;
    private static final int OTHER_UID = 10124;
    private static final int USER = 0;
    private static final String PKG = "com.example.app";
    /** A package the target image lists as a game, and this tree does not. */
    private static final String GAME_PKG = "com.tencent.tmgp.sgame";
    private static final int PID = 4321;
    private static final int CACHED_ADJ = ProcessList.CACHED_APP_MIN_ADJ + 5;

    @Test
    public void freezeCutsOnce() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final NetworkFreezeController net = controller(backend, scheduler, noGames());

        net.onFreezeConfirmed(UID, PKG, USER, false /* allowNet */, 0L /* delayMs */);
        // Nothing happens on the caller: the freeze confirmed path only enqueues.
        assertEquals(0, backend.rules.size());

        scheduler.runDue();
        assertEquals(Arrays.asList(uid(UID, "DENY")), backend.rules);

        net.flushDestroyTrigger();
        scheduler.runDue();
        assertEquals(Arrays.asList(true), backend.chains);
    }

    @Test
    public void repeatedReviewDoesNotCutTwice() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final NetworkFreezeController net = controller(backend, scheduler, noGames());

        // The freezer confirms an already frozen uid on every review.
        for (int i = 0; i < 4; i++) {
            net.onFreezeConfirmed(UID, PKG, USER, false, 0L);
            net.flushDestroyTrigger();
            scheduler.runDue();
        }
        assertEquals(Arrays.asList(uid(UID, "DENY")), backend.rules);
        // One socket scan for the whole run, not one per review.
        assertEquals(Arrays.asList(true), backend.chains);
    }

    @Test
    public void gameKeepsItsNetworkUntilTheGraceExpires() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final NetworkFreezeController net = controller(backend, scheduler,
                (pkg, user, uid) -> true);

        net.onFreezeConfirmed(UID, GAME_PKG, USER, false, 0L);
        scheduler.runDue();
        assertEquals(0, backend.rules.size());
        // The grace is only real if the activity manager keeps reporting the uid as
        // unfrozen: a frozen report would make the connectivity service cut it now.
        assertTrue(net.isNetworkKeptWhileFrozen(UID));

        scheduler.advance(ApmConstants.DEFAULT_NET_FREEZE_DELAY_GAME_MS - 1L);
        assertEquals(0, backend.rules.size());
        assertTrue(net.isNetworkKeptWhileFrozen(UID));

        scheduler.advance(1L);
        assertEquals(Arrays.asList(uid(UID, "DENY")), backend.rules);
        // The grace timer is not part of a review sweep, so it flushes its own enable:
        // a rule in a disabled chain would drop nothing.
        assertEquals(Arrays.asList(true), backend.chains);
        assertFalse(net.isNetworkKeptWhileFrozen(UID));
    }

    @Test
    public void gameGraceComesFromTheConfig() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final ApmConfigManager config = new ApmConfigManager();
        assertTrue(config.tryReplace(config.get().withNetFreezeDelayGameMs(30_000L)));
        final NetworkFreezeController net = controller(config, backend, scheduler,
                (pkg, user, uid) -> true);

        net.onFreezeConfirmed(UID, GAME_PKG, USER, false /* allowNet */, 0L /* delayMs */);
        scheduler.runDue();
        scheduler.advance(29_999L);
        assertEquals(0, backend.rules.size());
        scheduler.advance(1L);
        assertEquals(Arrays.asList(uid(UID, "DENY")), backend.rules);
    }

    @Test
    public void allowNetUidIsNeverCutAndIsReportedUnfrozen() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final NetworkFreezeController net = controller(backend, scheduler, noGames());
        net.publishAllowNetSnapshot(new int[] {UID});

        net.onFreezeConfirmed(UID, PKG, USER, true /* allowNet */, 0L);
        // A uid inside its grace, and a uid the published allow set holds even though this
        // confirm arrived without the bit.
        net.onFreezeConfirmed(OTHER_UID, PKG, USER, false, 60_000L);
        net.onFreezeConfirmed(UID, PKG, USER, false, 0L);
        scheduler.runDue();
        net.flushDestroyTrigger();
        scheduler.runDue();

        assertEquals(0, backend.rules.size());
        assertEquals(0, backend.chains.size());
        assertTrue(net.isNetworkKeptWhileFrozen(UID));
        // The uid inside its grace is kept too, see the game check.
        assertTrue(net.isNetworkKeptWhileFrozen(OTHER_UID));
    }

    @Test
    public void allowNetRemovesAnEarlierCut() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final NetworkFreezeController net = controller(backend, scheduler, noGames());

        net.onFreezeConfirmed(UID, PKG, USER, false, 0L);
        scheduler.runDue();
        net.flushDestroyTrigger();
        scheduler.runDue();
        assertEquals(Arrays.asList(uid(UID, "DENY")), backend.rules);

        // The capability bit is recomputed by the service and republished, then the freeze
        // is confirmed again.
        net.publishAllowNetSnapshot(new int[] {UID});
        net.onFreezeConfirmed(UID, PKG, USER, true, 0L);
        scheduler.runDue();

        assertEquals(Arrays.asList(uid(UID, "DENY"), uid(UID, "ALLOW")), backend.rules);
        assertTrue(net.isNetworkKeptWhileFrozen(UID));
    }

    @Test
    public void unfreezeRemovesTheRule() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final NetworkFreezeController net = controller(backend, scheduler, noGames());

        net.onFreezeConfirmed(UID, PKG, USER, false, 0L);
        scheduler.runDue();
        net.onUnfrozen(UID);
        scheduler.runDue();
        assertEquals(Arrays.asList(uid(UID, "DENY"), uid(UID, "ALLOW")), backend.rules);

        // Idempotent: a second unfreeze writes nothing.
        net.onUnfrozen(UID);
        scheduler.runDue();
        assertEquals(2, backend.rules.size());
    }

    @Test
    public void unfreezeDuringTheGraceNeverCuts() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final NetworkFreezeController net = controller(backend, scheduler, noGames());

        net.onFreezeConfirmed(UID, PKG, USER, false, 60_000L);
        scheduler.runDue();
        assertTrue(net.isNetworkKeptWhileFrozen(UID));

        net.onUnfrozen(UID);
        scheduler.runDue();
        assertFalse(net.isNetworkKeptWhileFrozen(UID));

        scheduler.advance(120_000L);
        assertEquals(0, backend.rules.size());
    }

    @Test
    public void masterOffRemovesEveryRuleAndDisablesTheChain() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final NetworkFreezeController net = controller(backend, scheduler, noGames());

        net.onFreezeConfirmed(UID, PKG, USER, false, 0L);
        net.onFreezeConfirmed(OTHER_UID, PKG, USER, false, 0L);
        scheduler.runDue();
        net.flushDestroyTrigger();
        scheduler.runDue();
        assertEquals(Arrays.asList(uid(UID, "DENY"), uid(OTHER_UID, "DENY")), backend.rules);

        net.onMasterOffOrShadow();
        scheduler.runDue();
        assertEquals(Arrays.asList(uid(UID, "DENY"), uid(OTHER_UID, "DENY"),
                uid(UID, "ALLOW"), uid(OTHER_UID, "ALLOW")), backend.rules);
        assertEquals(Arrays.asList(true, false), backend.chains);
    }

    @Test
    public void aFailedApplyGivesUpAfterThreeAttempts() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final NetworkFreezeController net = controller(backend, scheduler, noGames());
        backend.failuresLeft = Integer.MAX_VALUE;

        net.onFreezeConfirmed(UID, PKG, USER, false, 0L);
        scheduler.runDue();
        scheduler.advance(NetworkFreezeController.RETRY_DELAY_MS);
        scheduler.advance(NetworkFreezeController.RETRY_DELAY_MS);
        assertEquals(NetworkFreezeController.MAX_CUT_ATTEMPTS, backend.attempts);

        // The budget is spent: no more attempts, and the uid keeps its network.
        scheduler.advance(10L * NetworkFreezeController.RETRY_DELAY_MS);
        net.onFreezeConfirmed(UID, PKG, USER, false, 0L);
        scheduler.runDue();
        assertEquals(NetworkFreezeController.MAX_CUT_ATTEMPTS, backend.attempts);
        assertEquals(0, backend.rules.size());
        assertFalse(net.isNetworkKeptWhileFrozen(UID));
    }

    @Test
    public void coreUidsAreLeftAlone() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final NetworkFreezeController net = controller(backend, scheduler, noGames());

        net.onFreezeConfirmed(SYSTEM_UID, "android", USER, false, 0L);
        net.onFreezeConfirmed(Process.SHELL_UID, PKG, USER, false, 0L);
        net.onFreezeConfirmed(Process.FIRST_APPLICATION_UID - 1, PKG, USER, false, 0L);
        scheduler.runDue();
        net.flushDestroyTrigger();
        scheduler.runDue();

        assertEquals(0, backend.rules.size());
        assertEquals(0, backend.chains.size());
    }

    @Test
    public void relaxedUidIsNeverCut() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final ApmConfigManager config = new ApmConfigManager();
        assertTrue(config.tryReplace(config.get().withNetRelaxUids(new int[] {UID})));
        final NetworkFreezeController net = controller(config, backend, scheduler, noGames());

        net.onFreezeConfirmed(UID, PKG, USER, false, 0L);
        scheduler.runDue();

        assertEquals(0, backend.rules.size());
        assertTrue(net.isNetworkKeptWhileFrozen(UID));
    }

    @Test
    public void switchingTheCutOffRestoresTheNetwork() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final ApmConfigManager config = new ApmConfigManager();
        final NetworkFreezeController net = controller(config, backend, scheduler, noGames());

        net.onFreezeConfirmed(UID, PKG, USER, false, 0L);
        scheduler.runDue();
        net.flushDestroyTrigger();
        scheduler.runDue();

        assertTrue(config.tryReplace(config.get().withNetworkFreezeEnabled(false)));
        net.onConfigChanged();
        scheduler.runDue();

        assertEquals(Arrays.asList(uid(UID, "DENY"), uid(UID, "ALLOW")), backend.rules);
        assertEquals(Arrays.asList(true, false), backend.chains);
    }

    @Test
    public void cleanStartDropsTheChainBeforeTheFirstRule() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final NetworkFreezeController net = controller(backend, scheduler, noGames());

        net.onConfigChanged();
        scheduler.runDue();
        assertEquals(Arrays.asList(false), backend.chains);

        // Once.
        net.onConfigChanged();
        scheduler.runDue();
        assertEquals(Arrays.asList(false), backend.chains);
    }

    @Test
    public void forcedSocketDestroyIsSkippedWhenTheChainIsAlreadyOn() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final ApmConfigManager config = new ApmConfigManager();
        assertTrue(config.tryReplace(config.get().withForceSocketDestroy(false)));
        final NetworkFreezeController net = controller(config, backend, scheduler, noGames());

        net.onFreezeConfirmed(UID, PKG, USER, false, 0L);
        scheduler.runDue();
        net.flushDestroyTrigger();
        scheduler.runDue();
        // The chain has to be enabled for the rule to bite at all.
        assertTrue(backend.chainEnabled);
        backend.chains.clear();

        net.onFreezeConfirmed(OTHER_UID, PKG, USER, false, 0L);
        scheduler.runDue();
        net.flushDestroyTrigger();
        scheduler.runDue();
        assertEquals(Arrays.asList(uid(UID, "DENY"), uid(OTHER_UID, "DENY")), backend.rules);
        // Second rule, same sweep as the first enable: no second socket scan.
        assertEquals(0, backend.chains.size());
    }

    @Test
    public void backendCallsRunOnTheApmNetThread() throws Exception {
        final FakeBackend backend = new FakeBackend();
        backend.latch = new CountDownLatch(1);
        final NetworkFreezeController net = new NetworkFreezeController(
                new ApmConfigManager(), backend, noGames());

        net.onFreezeConfirmed(UID, PKG, USER, false, 0L);
        assertTrue("no rule write reached the backend",
                backend.latch.await(5, TimeUnit.SECONDS));

        assertEquals("apm-net", backend.threads.get(0));
    }

    @Test
    public void partialRollbackNotifiesTheUnfrozenSide() {
        final RecordingListener listener = new RecordingListener();
        final FakeFreezer freezer = new FakeFreezer();
        freezer.next = new ApmFreezeResult(new int[] {PID}, new int[] {PID + 1});
        final FreezeController freeze = new FreezeController(freezer, new NoopScheduler(),
                new ArraySet<Integer>(), new ProtectionArbiter(), new ComponentExemptionTable());
        freeze.setListener(listener);

        freeze.review(cachedRecord(UID), ApmConfig.defaults(), 10_000L);

        assertTrue(freezer.unfrozen);
        assertTrue(listener.confirmed.isEmpty());
        assertEquals(Arrays.asList(UID), listener.unfrozen);
    }

    @Test
    public void commitNotifiesTheFrozenSide() {
        final RecordingListener listener = new RecordingListener();
        final FreezeController freeze = new FreezeController(new FakeFreezer(), new NoopScheduler(),
                new ArraySet<Integer>(), new ProtectionArbiter(), new ComponentExemptionTable());
        freeze.setListener(listener);

        freeze.review(cachedRecord(UID), ApmConfig.defaults(), 10_000L);

        assertEquals(Arrays.asList(UID), listener.confirmed);
        assertTrue(listener.unfrozen.isEmpty());
    }

    @Test
    public void hardcodedGameListHoldsPackagesNotProcessNames() {
        assertTrue(ApmGamePackages.contains(GAME_PKG));
        assertTrue(ApmGamePackages.contains("com.miHoYo.Yuanshen"));
        assertFalse(ApmGamePackages.contains(PKG));
        assertFalse(ApmGamePackages.contains(null));
        // A process name from the same file is not a package.
        assertFalse(ApmGamePackages.contains(GAME_PKG + ":999"));
    }

    @Test
    public void dumpReportsTheCutSetAndTheFailures() {
        final FakeBackend backend = new FakeBackend();
        final ManualScheduler scheduler = new ManualScheduler();
        final NetworkFreezeController net = controller(backend, scheduler, noGames());
        net.onFreezeConfirmed(UID, PKG, USER, false, 0L);
        scheduler.runDue();
        net.flushDestroyTrigger();
        scheduler.runDue();
        net.onUnfrozen(OTHER_UID);
        scheduler.runDue();

        final StringWriter out = new StringWriter();
        net.dump(new PrintWriter(out));
        final String text = out.toString();

        assertTrue(text, text.contains("net cut=1"));
        assertTrue(text, text.contains("chainEnabledApplied=true"));
        assertTrue(text, text.contains("uid=" + UID));
        assertTrue(text, text.contains("rule=DENY"));
        assertTrue(text, text.contains("lastResult=cut"));
        assertTrue(text, text.contains("backendAvailable=true"));
        assertTrue(text, text.contains("gameTable=" + ApmGamePackages.size()));
    }

    private static NetworkFreezeController.GameClassifier noGames() {
        return (pkg, user, uid) -> false;
    }

    private static NetworkFreezeController controller(FakeBackend backend,
            ManualScheduler scheduler, NetworkFreezeController.GameClassifier games) {
        return controller(new ApmConfigManager(), backend, scheduler, games);
    }

    private static NetworkFreezeController controller(ApmConfigManager config,
            FakeBackend backend, ManualScheduler scheduler,
            NetworkFreezeController.GameClassifier games) {
        return new NetworkFreezeController(config, scheduler, backend, games);
    }

    private static String uid(int uid, String rule) {
        return uid + ":" + rule;
    }

    /** A cached uid the freezer takes: every hard block in {@link FreezeController} passes. */
    private static ApmProcessRecord cachedRecord(int uid) {
        final ApmProcessRecord rec = new ApmProcessRecord(uid);
        rec.userId = USER;
        rec.packages.add(PKG);
        rec.initialized = true;
        rec.state = ApmConstants.ManagedState.CACHED;
        rec.minAdj = CACHED_ADJ;
        rec.procState = PROCESS_STATE_CACHED_EMPTY;
        rec.graceStartedElapsed = 0L;
        final PidSlot slot = new PidSlot();
        slot.pid = PID;
        slot.processName = PKG;
        slot.packageName = PKG;
        slot.curAdj = CACHED_ADJ;
        slot.curProcState = PROCESS_STATE_CACHED_EMPTY;
        rec.pids.put(PID, slot);
        return rec;
    }

    private static final class FakeBackend implements NetworkFreezeController.NetCutBackend {
        final ArrayList<String> rules = new ArrayList<>();
        final ArrayList<Boolean> chains = new ArrayList<>();
        final ArrayList<String> threads = new ArrayList<>();
        final ArraySet<Integer> denied = new ArraySet<>();
        boolean available = true;
        boolean chainEnabled;
        int failuresLeft;
        int attempts;
        CountDownLatch latch;

        @Override
        public void setUidRule(int chain, int uid, int rule) {
            threads.add(Thread.currentThread().getName());
            attempts++;
            if (failuresLeft > 0) {
                failuresLeft--;
                throw new IllegalStateException("fake backend failure");
            }
            rules.add(NetworkFreezeControllerTest.uid(uid, ruleName(rule)));
            if (rule == ConnectivityManager.FIREWALL_RULE_DENY) {
                denied.add(uid);
            } else {
                denied.remove(uid);
            }
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override
        public void setChainEnabled(boolean enabled) {
            threads.add(Thread.currentThread().getName());
            chainEnabled = enabled;
            chains.add(enabled);
        }

        @Override
        public boolean isChainEnabled() {
            return chainEnabled;
        }

        @Override
        public int getUidRule(int uid) {
            return denied.contains(uid) ? ConnectivityManager.FIREWALL_RULE_DENY
                    : ConnectivityManager.FIREWALL_RULE_ALLOW;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        private static String ruleName(int rule) {
            return rule == ConnectivityManager.FIREWALL_RULE_DENY ? "DENY" : "ALLOW";
        }
    }

    /** Runs posted work when the test says so, so a grace window needs no real sleep. */
    private static final class ManualScheduler implements NetworkFreezeController.Scheduler {
        private final ArrayList<Alarm> mAlarms = new ArrayList<>();
        private long mNow;

        @Override
        public void post(Runnable runnable) {
            mAlarms.add(new Alarm(mNow, runnable));
        }

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            mAlarms.add(new Alarm(mNow + delayMs, runnable));
        }

        @Override
        public void remove(Runnable runnable) {
            for (int i = mAlarms.size() - 1; i >= 0; i--) {
                if (mAlarms.get(i).run == runnable) {
                    mAlarms.remove(i);
                }
            }
        }

        void advance(long ms) {
            mNow += ms;
            runDue();
        }

        /** Runs everything due now, including work those runnables post themselves. */
        void runDue() {
            for (int guard = 0; guard < 1000; guard++) {
                Alarm due = null;
                for (int i = 0; i < mAlarms.size(); i++) {
                    if (mAlarms.get(i).at <= mNow) {
                        due = mAlarms.remove(i);
                        break;
                    }
                }
                if (due == null) {
                    return;
                }
                due.run.run();
            }
            throw new IllegalStateException("scheduler did not settle");
        }

        private static final class Alarm {
            final long at;
            final Runnable run;

            Alarm(long at, Runnable run) {
                this.at = at;
                this.run = run;
            }
        }
    }

    private static final class NoopScheduler implements FreezeController.Scheduler {
        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
        }

        @Override
        public void remove(Runnable runnable) {
        }
    }

    private static final class RecordingListener implements FreezeController.Listener {
        final List<Integer> confirmed = new ArrayList<>();
        final List<Integer> unfrozen = new ArrayList<>();

        @Override
        public void onFreezeConfirmed(ApmProcessRecord rec) {
            confirmed.add(rec.uid);
        }

        @Override
        public void onUnfrozen(ApmProcessRecord rec) {
            unfrozen.add(rec.uid);
        }
    }

    private static final class FakeFreezer implements ApmExecutor {
        ApmFreezeResult next = new ApmFreezeResult(new int[] {PID}, new int[0]);
        boolean unfrozen;

        @Override
        public ApmFreezeResult freezeUid(int uid, int[] pids) {
            return next;
        }

        @Override
        public boolean unfreezeUid(int uid, int[] pids) {
            unfrozen = true;
            return true;
        }

        @Override
        public int compactUid(int uid, int[] pids) {
            return 0;
        }

        @Override
        public boolean killCachedUid(int uid, int[] pids, String reason) {
            return true;
        }
    }
}
