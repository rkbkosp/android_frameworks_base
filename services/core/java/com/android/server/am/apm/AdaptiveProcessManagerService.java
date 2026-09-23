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

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.am.ProcessList;
import com.android.server.am.apm.ApmConstants.ManagedState;
import com.android.server.am.apm.ApmEvent.ProcessSnapshot;
import com.android.server.pm.UserManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/**
 * Adaptive process manager.
 *
 * <p>AMS remains the source of truth. Hooks copy pid, uid, name, and adj facts, then this
 * service scores them on its own thread. Freeze, compact, kill, and kernel writes run
 * when the master switch is on and shadow mode is off. Those are the defaults. Shadow mode
 * logs the decision and does not apply it. While the master switch is off, hooks do not
 * copy further snapshots, every uid this service froze is unfrozen, and new kills stop.
 */
public final class AdaptiveProcessManagerService {
    private static final String TAG = "Apm";
    private static final long SHELL_WAIT_MS = 2000L;
    private static final String[] NO_NAMES = new String[0];

    /** Elapsed realtime, injectable so grace transitions do not sleep. */
    public interface Clock {
        long elapsedRealtime();

        Clock SYSTEM = () -> SystemClock.elapsedRealtime();
    }

    private final Clock mClock;
    private final ApmConfigManager mConfig = new ApmConfigManager();
    private final ProcessStateTracker mTracker = new ProcessStateTracker();
    private final PolicyEngine mPolicy = new PolicyEngine();
    private final ApmStats mStats = new ApmStats();
    private final ProtectionArbiter mArbiter = new ProtectionArbiter();
    private final ClearSceneTable mScenes = ClearSceneTable.get();
    private final TaskRestoreController mTasks = new TaskRestoreController();
    private final RevivalController mRevival = new RevivalController();
    private final ComponentExemptionTable mExemptions = new ComponentExemptionTable();
    /** Auto-start block list. A gate query on an empty list is one set lookup. */
    private final AutoStartPolicy mAutoStart;
    /**
     * Navigation state machine. Mutated only on this service's thread and never while
     * {@link #mLock} is held. Reading it from under that lock is the other direction:
     * see {@link #fillClearFacts}.
     */
    private final NavigationProtectionController mNavigation;
    /** Swapped whole so a DeviceConfig refresh cannot observe a half built policy. */
    private volatile NavigationPolicyConfig mNavigationConfig = NavigationPolicyConfig.defaults();
    /**
     * uid -> the package a GNSS event or an adj snapshot named for it. Written and read
     * only on this service's thread, so it needs no lock.
     */
    private final ArrayMap<Integer, String> mNavigationPackages = new ArrayMap<>();
    /**
     * {@code userId:package} -> uid, for the reverse lookup {@code OomAdjuster} makes
     * while it holds the activity manager lock. Concurrent because that read is the one
     * navigation lookup that does not run on this thread.
     */
    private final ConcurrentHashMap<String, Integer> mNavigationUids = new ConcurrentHashMap<>();
    /** Uids whose last adj pass reported a location foreground service. */
    private final ArraySet<Integer> mLocationFgsUids = new ArraySet<>();
    @Nullable private Runnable mNavigationTick;
    private long mNavigationTickAtMs = Long.MAX_VALUE;
    /** Armed for the next oom-adj trim. Athena LMK adj 300. Not set in shadow mode. */
    private volatile String mArmedAdjScene;
    /**
     * Available MiB read on this thread before an athena_lmk trim is armed.
     * -1 means the read did not happen. Not read under the activity manager lock.
     */
    private volatile long mArmedAvailMb = -1L;
    private boolean mAvailableBytesOverrideSet;
    private long mAvailableBytesOverride = -1L;
    private final Object mLock = new Object();
    private final Object mQueueLock = new Object();
    private final Object mUnfreezeWait = new Object();
    /** Uids this service has frozen. Readable without {@link #mLock}. */
    private final Set<Integer> mFrozenUids = ConcurrentHashMap.newKeySet();
    /**
     * Package and process names of the frozen uids, replaced whole, read without
     * {@link #mLock}: {@code awaitUnfreezePackage} runs on a binder thread and must not queue
     * behind this service's lock. Rebuilt by the freeze listener on every change.
     */
    private volatile String[] mFrozenNames = NO_NAMES;
    /**
     * Platform work decided under {@link #mLock} and run after it is released, on this thread,
     * in decision order. This is what keeps the activity manager locks, the cgroup writes and
     * the proc-node writes out of the service lock.
     */
    private final ArrayList<Runnable> mOffLockWork = new ArrayList<>();
    /** Set while {@link #drainOffLockWork()} runs, so a queued item cannot re-enter it. */
    private boolean mDrainingOffLock;
    /**
     * Delayed runnable -> the wrapper actually posted, so {@link #removeExternal} can still
     * drop it. Touched on this service's thread only.
     */
    private final ArrayMap<Runnable, Runnable> mDelayedWrappers = new ArrayMap<>();
    /** uid -> posted freeze verification. Dropped when the uid is unfrozen. */
    private final ArrayMap<Integer, Runnable> mVerifiers = new ArrayMap<>();
    /** Posted when no oom-adj pass arrives while this service is enabled. */
    @Nullable private Runnable mOomAdjWatchdog;
    /** Last oom-adj pass, or the last arm. Touched on this service's thread only. */
    private long mLastOomAdjMs;
    private final ArrayList<DueAlarm> mDueAlarms = new ArrayList<>();
    @Nullable private final ApmExecutor mExecutor;
    private final FreezeController mFreeze;
    private final KernelKnobWriter mKnobs;
    @Nullable private final ApmPressure mPressure;
    private final MemoryController mMemory;
    /**
     * Cuts the network of a frozen uid. Owns its own thread; the service only enqueues and
     * publishes the allow bit set.
     */
    private final NetworkFreezeController mNet;
    private final FreezeController.Listener mFreezeListener = new FreezeController.Listener() {
        @Override
        public void onFreezeConfirmed(ApmProcessRecord rec, boolean again) {
            if (!again) {
                // The freeze is applied in the platform queue, after the decision, so this is
                // where a fresh freeze is counted.
                mStats.noteExecuted();
            }
            if (!rec.systemUid) {
                // Only enqueues: this runs with the service lock held.
                mNet.onFreezeConfirmed(rec.uid, rec.primaryPackage(), rec.userId,
                        allowsNetworkWhileFrozen(rec, mClock.elapsedRealtime()),
                        mConfig.get().netFreezeDelayMs);
            }
            refreshFrozenNamesLocked();
            armFreezeVerificationLocked(rec);
        }

        @Override
        public void onUnfrozen(ApmProcessRecord rec, boolean applied) {
            if (applied) {
                // A freeze this service had applied, or half applied, was released. The release
                // runs in the platform queue, after the decision, so this is where it is counted.
                mStats.noteExecuted();
            }
            mNet.onUnfrozen(rec.uid);
            cancelFreezeVerificationLocked(rec.uid);
            refreshFrozenNamesLocked();
        }
    };

    /**
     * The platform queue. Every method enqueues and returns: the work runs when the service
     * lock has been released, and a freeze result is committed back under that lock.
     */
    private final OffLockPlatform mPlatform = new OffLockPlatform() {
        @Override
        public void run(Runnable work) {
            mOffLockWork.add(work);
        }

        @Override
        public void freeze(int uid, int[] pids, Consumer<ApmFreezeResult> commit) {
            mOffLockWork.add(() -> {
                ApmFreezeResult result = null;
                if (mExecutor == null) {
                    Slog.w(TAG, "freeze returned no result uid=" + uid);
                } else {
                    try {
                        result = mExecutor.freezeUid(uid, pids);
                        if (result == null) {
                            Slog.w(TAG, "freeze returned no result uid=" + uid);
                        }
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "freeze failed uid=" + uid, e);
                    }
                }
                final ApmFreezeResult outcome = result;
                synchronized (mLock) {
                    commit.accept(outcome);
                }
            });
        }

        @Override
        public void freezeState(int uid, Consumer<Integer> commit) {
            mOffLockWork.add(() -> {
                int state = ApmFreezeResult.STATE_UNKNOWN;
                if (mExecutor != null) {
                    try {
                        state = mExecutor.actualFreezeState(uid);
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "freeze state failed uid=" + uid, e);
                    }
                }
                final int outcome = state;
                synchronized (mLock) {
                    commit.accept(outcome);
                }
            });
        }

        @Override
        public void unfreeze(int uid, int[] pids, Runnable commit) {
            mOffLockWork.add(() -> {
                if (mExecutor != null) {
                    try {
                        mExecutor.unfreezeUid(uid, pids);
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "unfreeze failed uid=" + uid, e);
                    }
                }
                synchronized (mLock) {
                    commit.run();
                }
            });
        }
    };

    private final FreezeController.Scheduler mScheduler = new FreezeController.Scheduler() {
        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            postDelayedExternal(runnable, delayMs);
        }

        @Override
        public void remove(Runnable runnable) {
            removeExternal(runnable);
        }
    };

    @Nullable private final Handler mHandler;
    @Nullable private final Context mContext;
    @Nullable private ApmEvent mPendingOom;
    private boolean mOomQueued;
    private boolean mSystemReady;
    private boolean mConfigListenerRegistered;
    private boolean mRoleObserversRegistered;
    /** userId -> current input-method package. Updated on this thread. */
    private final ConcurrentHashMap<Integer, String> mImeByUser = new ConcurrentHashMap<>();
    /** {@code userId:package} keys. Readers do not take the activity manager lock. */
    private final Set<String> mA11yKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> mVpnKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> mAudioKeys = ConcurrentHashMap.newKeySet();
    @Nullable private String mDeviceOwnerPackage;
    private int mDeviceOwnerUser = UserHandle.USER_NULL;
    @Nullable private ContentObserver mImeObserver;
    @Nullable private ContentObserver mA11yObserver;
    @Nullable private Boolean mShellEnabled;
    @Nullable private Boolean mShellShadow;
    @Nullable private Boolean mShellFreezer;
    /** Last foreground list written, or null if gates were closed and the next open must write. */
    @Nullable private int[] mPublishedFg;

    public AdaptiveProcessManagerService() {
        this(null /* context */, null /* executor */, null /* pressure */);
    }

    public AdaptiveProcessManagerService(@Nullable ApmExecutor executor) {
        this(null /* context */, executor, null /* pressure */);
    }

    public AdaptiveProcessManagerService(@Nullable ApmExecutor executor,
            @Nullable ApmPressure pressure) {
        this(null /* context */, executor, pressure);
    }

    public AdaptiveProcessManagerService(@Nullable Context context,
            @Nullable ApmExecutor executor, @Nullable ApmPressure pressure) {
        this(Clock.SYSTEM, true /* startThread */, executor, null /* knobs */, pressure, context);
    }

    @VisibleForTesting
    public AdaptiveProcessManagerService(Clock clock, boolean startThread) {
        this(clock, startThread, null /* executor */);
    }

    @VisibleForTesting
    public AdaptiveProcessManagerService(Clock clock, boolean startThread,
            @Nullable ApmExecutor executor) {
        // Tests must not create or write the phone's proc nodes.
        this(clock, startThread, executor,
                new KernelKnobWriter("/proc/apm-missing-fg-uids",
                        "/sys/module/apm_missing/parameters/vm_swappiness"),
                null /* pressure */, null /* context */);
    }

    @VisibleForTesting
    AdaptiveProcessManagerService(Clock clock, boolean startThread,
            @Nullable ApmExecutor executor, @Nullable KernelKnobWriter knobs,
            @Nullable ApmPressure pressure) {
        this(clock, startThread, executor, knobs, pressure, null /* context */);
    }

    /** Test seam. Both settings and the platform signature come from the caller. */
    @VisibleForTesting
    AdaptiveProcessManagerService(Clock clock, boolean startThread,
            @Nullable ApmExecutor executor, @Nullable AutoStartPolicy.Store autoStartStore,
            @Nullable AutoStartPolicy.PlatformSignatures autoStartSignatures) {
        this(clock, startThread, executor,
                new KernelKnobWriter("/proc/apm-missing-fg-uids",
                        "/sys/module/apm_missing/parameters/vm_swappiness"),
                null /* pressure */, null /* context */, autoStartStore, autoStartSignatures);
    }

    @VisibleForTesting
    AdaptiveProcessManagerService(Clock clock, boolean startThread,
            @Nullable ApmExecutor executor, @Nullable KernelKnobWriter knobs,
            @Nullable ApmPressure pressure, @Nullable Context context) {
        this(clock, startThread, executor, knobs, pressure, context, null /* autoStartStore */,
                null /* autoStartSignatures */);
    }

    @VisibleForTesting
    AdaptiveProcessManagerService(Clock clock, boolean startThread,
            @Nullable ApmExecutor executor, @Nullable KernelKnobWriter knobs,
            @Nullable ApmPressure pressure, @Nullable Context context,
            @Nullable AutoStartPolicy.Store autoStartStore,
            @Nullable AutoStartPolicy.PlatformSignatures autoStartSignatures) {
        mClock = clock != null ? clock : Clock.SYSTEM;
        mContext = context;
        mNavigation = new NavigationProtectionController(mNavigationConfig);
        mExecutor = executor;
        mKnobs = knobs != null ? knobs : new KernelKnobWriter();
        mPressure = pressure;
        if (startThread) {
            // Proc and sysfs writes happen on this thread.
            final ServiceThread thread = new ServiceThread("apm",
                    Process.THREAD_PRIORITY_BACKGROUND, true /* allowIo */);
            thread.start();
            mHandler = new Handler(thread.getLooper());
        } else {
            mHandler = null;
        }
        mFreeze = new FreezeController(mExecutor, mScheduler, mPlatform, mFrozenUids, mArbiter,
                mExemptions);
        mFreeze.setListener(mFreezeListener);
        mMemory = new MemoryController(mExecutor, mFreeze, mScheduler, mPlatform, mKnobs, mPressure,
                mStats, this::onMemoryRecheck, mArbiter);
        if (mHandler == null) {
            // No service thread: tests. The network work runs on the caller and the grace
            // window runs on the service's own due-alarm list.
            mNet = new NetworkFreezeController(mConfig, new NetworkFreezeController.Scheduler() {
                @Override
                public void post(Runnable runnable) {
                    AdaptiveProcessManagerService.this.post(runnable);
                }

                @Override
                public void postDelayed(Runnable runnable, long delayMs) {
                    postDelayedExternal(runnable, delayMs);
                }

                @Override
                public void remove(Runnable runnable) {
                    removeExternal(runnable);
                }
            });
        } else {
            mNet = new NetworkFreezeController(mConfig, mContext);
        }
        // The settings read and the platform signature lookup run on this thread, never
        // under the activity manager lock: a gate only reads the published snapshot.
        mAutoStart = new AutoStartPolicy(
                autoStartStore != null ? autoStartStore : AutoStartPolicy.globalStore(context),
                autoStartSignatures != null ? autoStartSignatures
                        : AutoStartPolicy.packageSignatures(context),
                new AutoStartPolicy.Roles() {
                    @Override
                    public boolean holdsRole(@Nullable String packageName) {
                        return holdsAutoStartRole(packageName);
                    }

                    @Override
                    public List<String> rolePackages() {
                        return AdaptiveProcessManagerService.this.rolePackages();
                    }
                },
                context == null ? null : context.getContentResolver(),
                mHandler);
    }

    /** Master switch. Read from the activity manager thread; does not take that lock. */
    public boolean isEnabled() {
        return mConfig.get().enabled;
    }

    /**
     * Starts config refresh on the policy thread. Does not read DeviceConfig on the caller,
     * and does not need the activity manager lock.
     */
    public void systemReady() {
        if (mHandler == null || mSystemReady) {
            return;
        }
        mSystemReady = true;
        mHandler.post(() -> {
            registerConfigListener();
            mAutoStart.systemReady();
        });
    }

    public void noteProcessStarted(int pid, int uid, int userId, String processName,
            String packageName, long startSeq, boolean persistent) {
        if (!isEnabled()) {
            return;
        }
        post(ApmEvent.processStarted(pid, uid, userId, processName, packageName, startSeq,
                persistent));
    }

    public void noteProcessDied(int pid, int uid, int userId, String processName, long startSeq) {
        if (!isEnabled()) {
            return;
        }
        post(ApmEvent.processDied(pid, uid, userId, processName, startSeq));
    }

    public void noteTopResumed(int uid, int pid, int userId, @Nullable String processName) {
        if (!isEnabled()) {
            return;
        }
        post(ApmEvent.topResumed(uid, pid, userId, processName));
    }

    /**
     * One snapshot for a completed adj pass. Coalesced to the latest copy if the policy
     * thread is still applying the previous pass. The list must not contain live records.
     */
    public void postOomAdjCompleted(int oomAdjReason, List<ProcessSnapshot> processes) {
        if (!isEnabled()) {
            return;
        }
        final ApmEvent event = ApmEvent.oomAdjCompleted(oomAdjReason, processes);
        synchronized (mQueueLock) {
            mPendingOom = event;
            if (mOomQueued) {
                return;
            }
            mOomQueued = true;
        }
        postDrainOom();
    }

    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            ApmShellCommand.dump(pw, mConfig.get(), mTracker, mStats, mNavigationConfig,
                    mNavigation.snapshots(), mNet, mAutoStart);
            pw.print("  kernel missing=");
            pw.print(mKnobs.getMissingCount());
            pw.print(" short=");
            pw.print(mKnobs.getShortCount());
            pw.print(" ok=");
            pw.println(mKnobs.getOkCount());
            mArbiter.dump(pw, mClock.elapsedRealtime());
            pw.print("  clearScenes=");
            pw.println(mScenes.sceneCount());
            mTasks.dump(pw);
            pw.print("  revival slots=");
            pw.print(mRevival.slotsUsed(mClock.elapsedRealtime()));
            pw.print(" energy=");
            pw.println(mRevival.energyUsed(mClock.elapsedRealtime()));
        }
    }

    /**
     * Whether the uid must keep being reported as unfrozen while it is frozen. Called from
     * the uid frozen state report, which holds no activity manager lock, so this only reads
     * an immutable snapshot: it must not take a lock here.
     */
    public boolean isNetworkKeptWhileFrozen(int uid) {
        return mNet.isNetworkKeptWhileFrozen(uid);
    }

    public void explain(PrintWriter pw, String target) {
        synchronized (mLock) {
            ApmShellCommand.explain(pw, mConfig.get(), mTracker, target);
            final Integer uid = parseExplainUid(target);
            if (uid != null) {
                final ApmProcessRecord rec = mTracker.get(uid);
                if (rec != null && rec.primaryPackage() != null) {
                    mArbiter.dumpPackage(pw, rec.primaryPackage(), rec.userId,
                            mClock.elapsedRealtime());
                }
            } else if (target != null) {
                mArbiter.dumpPackage(pw, target, 0, mClock.elapsedRealtime());
            }
        }
    }

    /** Shadow still computes policy. Freeze, kill, adj, revival, and kernel writes do not run. */
    public boolean isShadowMode() {
        return mConfig.get().shadowMode;
    }

    /**
     * Cached-kill gate for {@code OomAdjuster}. User force-stop still wins: a force-stopped
     * process is not spared. Does not take the activity manager lock.
     */
    public boolean shouldSpareCachedKill(String packageName, int userId,
            boolean processForceStopped) {
        return mArbiter.shouldSpareCachedKill(packageName, userId, mClock.elapsedRealtime(),
                processForceStopped);
    }

    public ProtectionArbiter getArbiter() {
        return mArbiter;
    }

    /** Record a user force-stop. Allows lose. The ban bits of a higher layer stay set. */
    public void noteUserForceStop(String packageName, int userId) {
        if (packageName == null) {
            return;
        }
        mArbiter.setUserForceStop(packageName, userId, true);
        mRevival.noteForceStop(packageName, userId, true);
    }

    /**
     * Revival request. Category 1 does not consume a slot. Shadow computes and does not
     * arm the adj bump. Does not start {@code com.heytap.mcs}.
     */
    public RevivalController.Result requestRevival(String callerPackage, String action,
            String targetPackage, int userId) {
        final long now = mClock.elapsedRealtime();
        final boolean stopped = targetPackage != null
                && mArbiter.merge(targetPackage, userId, now).forceStopped;
        return mRevival.request(callerPackage, action, targetPackage, userId, now,
                isShadowMode(), stopped);
    }

    /**
     * 30 second adj bump for an applied revival. -1 when there is no grant, shadow is on,
     * or the user force-stopped the package. The bump is not {@code denyKill}. A higher
     * layer's {@code denyKill} is left in place and is not cleared by this bump.
     */
    public int revivalBumpAdj(String packageName, int userId, boolean processForceStopped) {
        if (isShadowMode() || processForceStopped || packageName == null) {
            return -1;
        }
        final long now = mClock.elapsedRealtime();
        final ProtectionArbiter.Merged merged = mArbiter.merge(packageName, userId, now);
        if (merged.forceStopped || merged.backgroundRestricted) {
            return -1;
        }
        if (merged.denyKill && merged.denyKillLayer != null
                && merged.denyKillLayer.outranks(ProtectionArbiter.Layer.DYNAMIC)) {
            // Higher denyKill already decides the kill. The bump does not replace it.
            return -1;
        }
        return mRevival.bumpAdj(packageName, userId, now, false);
    }

    @VisibleForTesting
    public RevivalController getRevivalForTest() {
        return mRevival;
    }

    public ComponentExemptionTable exemptions() {
        return mExemptions;
    }

    /** The block list behind the three gates. A test drives the settings through it. */
    @VisibleForTesting
    AutoStartPolicy autoStart() {
        return mAutoStart;
    }

    /** Black list denies delivery. Empty lists do not. */
    public boolean mayDeliver(ComponentExemptionTable.Kind kind, String callerPackage,
            String targetPackage, String name) {
        return mExemptions.mayDeliver(kind, callerPackage, targetPackage, name, 0);
    }

    /**
     * Alarm delivery to a uid this service froze. The allow bit and the alarm white list
     * are the only passes. Force-stop blocks. A uid that is not frozen is not blocked here.
     */
    public boolean mayDeliverBroadcast(String callerPackage, String targetPackage, String action,
            boolean alarm, int uid) {
        // The auto-start block list, ahead of the alarm and frozen rules below: a listed
        // target is not delivered to whatever those rules would have said.
        if (autoStartDeniesBroadcast(targetPackage)) {
            return false;
        }
        if (!mExemptions.mayDeliver(ComponentExemptionTable.Kind.BROADCAST, callerPackage,
                targetPackage, action, 0)) {
            return false;
        }
        if (!alarm || uid < 0 || !mFrozenUids.contains(uid)) {
            return true;
        }
        if (!alarmWakeupAllowed(uid, targetPackage, action)) {
            return false;
        }
        // Caller holds the activity manager lock. Post the unfreeze and do not wait.
        if (!ApmConstants.DEFAULT_DEFER_ALARMS) {
            noteStartUnfreeze(uid);
        }
        return true;
    }

    /**
     * Auto-start block list, {@code startService} gate. Read before the activity manager
     * lock, next to {@link #mayDeliver}. False on an empty list, which is the shipped state.
     */
    public boolean autoStartDeniesService(@Nullable String callerPackage, int callerUid,
            @Nullable String targetPackage) {
        return deniesAutoStart(AutoStartPolicy.GATE_START,
                mAutoStart.shouldBlockStart(callerPackage, callerUid, targetPackage));
    }

    /**
     * Auto-start block list, {@code bindService} gate. The activity manager answers 0 for a
     * bind it will not start, so a hit is the same silent refusal.
     */
    public boolean autoStartDeniesBind(@Nullable String callerPackage, int callerUid,
            @Nullable String targetPackage) {
        return deniesAutoStart(AutoStartPolicy.GATE_BIND,
                mAutoStart.shouldBlockStart(callerPackage, callerUid, targetPackage));
    }

    /**
     * Auto-start block list, broadcast gate. The caller holds the activity manager lock:
     * this is an in-memory lookup and does not wait on a binder.
     */
    public boolean autoStartDeniesBroadcast(@Nullable String targetPackage) {
        return deniesAutoStart(AutoStartPolicy.GATE_BROADCAST,
                mAutoStart.shouldBlockBroadcast(targetPackage));
    }

    private boolean deniesAutoStart(int gate, boolean denies) {
        if (denies) {
            mAutoStart.noteBlocked(gate);
        }
        return denies;
    }

    /**
     * The current input method or an enabled accessibility service of any user. This backs
     * the auto-start exemption list with the two sets the role observers already maintain,
     * so no second copy exists, and it takes no lock the activity manager thread cannot take.
     */
    private boolean holdsAutoStartRole(@Nullable String packageName) {
        if (packageName == null) {
            return false;
        }
        for (String ime : mImeByUser.values()) {
            if (packageName.equals(ime)) {
                return true;
            }
        }
        for (String key : mA11yKeys) {
            final int separator = key.indexOf(':');
            if (separator >= 0 && packageName.equals(key.substring(separator + 1))) {
                return true;
            }
        }
        return false;
    }

    /** Current input-method and enabled accessibility packages, sorted for the dump. */
    private List<String> rolePackages() {
        final ArraySet<String> roles = new ArraySet<>();
        for (String ime : mImeByUser.values()) {
            if (ime != null) {
                roles.add(ime);
            }
        }
        for (String key : mA11yKeys) {
            final int separator = key.indexOf(':');
            if (separator >= 0) {
                roles.add(key.substring(separator + 1));
            }
        }
        final ArrayList<String> out = new ArrayList<>(roles.size());
        for (int i = 0; i < roles.size(); i++) {
            out.add(roles.valueAt(i));
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Job start when the activity manager lock is already held. A uid this service froze is
     * unfrozen by a post, not by a wait: refusing the job instead would leave the job's caller
     * waiting on a service that never comes up. Only a ban refuses, see
     * {@link #frozenDeniesJob}. {@code DEFAULT_DEFER_JOBS} stays false, so this is the shipped
     * path.
     */
    public void noteAllowedJobWakeup(int uid, @Nullable String packageName,
            @Nullable String component, boolean amsLockHeld) {
        if (ApmConstants.DEFAULT_DEFER_JOBS || uid < 0 || !isEnabled()) {
            return;
        }
        if (packageName != null && frozenDeniesJob(uid, packageName, component)) {
            return;
        }
        // Posted even when the uid is only scheduled to be frozen: the post is what drops that
        // debounce alarm, and a job that runs is exactly a new interaction need.
        if (amsLockHeld) {
            noteStartUnfreeze(uid);
        } else {
            awaitUnfreeze(uid);
        }
    }

    /**
     * Alarm delivery when the activity manager lock is not held. Waits up to
     * {@link ApmConstants#UNFREEZE_WAIT_MS}. Does not defer.
     */
    public void noteAllowedAlarmWakeup(int uid, @Nullable String packageName,
            @Nullable String action) {
        if (ApmConstants.DEFAULT_DEFER_ALARMS || !alarmWakeupAllowed(uid, packageName, action)) {
            return;
        }
        awaitUnfreeze(uid);
    }

    private boolean alarmWakeupAllowed(int uid, String packageName, String action) {
        if (uid < 0 || packageName == null || !mFrozenUids.contains(uid)) {
            return false;
        }
        if (mExemptions.check(ComponentExemptionTable.Kind.ALARM, false /* calling */,
                packageName, action, 0) == ComponentExemptionTable.Decision.DENY) {
            return false;
        }
        if (mExemptions.alarmAllowed(packageName, action)) {
            return true;
        }
        final ProtectionArbiter.Merged merged = mArbiter.merge(packageName,
                UserHandle.getUserId(uid), mClock.elapsedRealtime());
        return merged.allowAlarmWakeup && !merged.forceStopped;
    }

    /**
     * Job start of a uid this service froze. Only an explicit ban refuses it: a plain freeze
     * is this service's own doing, and refusing the job would block the caller on a service
     * that is never brought up. {@link #noteAllowedJobWakeup} unfreezes it instead. A uid this
     * service did not freeze, and a caller with no package to check, are not refused here.
     */
    public boolean frozenDeniesJob(int uid, String packageName, String component) {
        if (uid < 0 || packageName == null || !mFrozenUids.contains(uid)) {
            return false;
        }
        if (mExemptions.jobDenied(packageName)) {
            return true;
        }
        if (mExemptions.jobAllowed(packageName, component)) {
            return false;
        }
        final ProtectionArbiter.Merged merged = mArbiter.merge(packageName,
                UserHandle.getUserId(uid), mClock.elapsedRealtime());
        return merged.forceStopped || merged.backgroundRestricted;
    }

    public int fastFreezeTimeout(String packageName) {
        return mFreeze.fastFreezeTimeoutMs(packageName);
    }

    public boolean fastFreezePermitted(String packageName) {
        return mFreeze.fastFreezePermitted(packageName);
    }

    /**
     * Run one clear scene. {@code key} is a caller name, or a scene name when that name
     * is unique. Repeated {@code cc_name} values are not keys. Shadow mode still selects
     * the scene and still applies skips and {@code denyKill}, and does not kill, force-stop,
     * or remove a task.
     */
    public void runClearScene(String key) {
        if (key == null) {
            return;
        }
        post(() -> applyClearScene(key));
    }

    /**
     * Clean type the force-stop would use. 2 stays 2 when the package is not eligible
     * or the user already force-stopped it. 3 keeps the recent task and still kills
     * the process. Shadow mode is applied by the caller, which must not change
     * {@code setRemoved} when this returns 3 but shadow is on.
     */
    public int rewriteCleanType(String packageName, int userId, int uid, int incoming,
            boolean hasLauncherIcon, boolean systemApp) {
        return mTasks.rewrite(packageName, userId, uid, incoming, hasLauncherIcon, systemApp);
    }

    public void noteTaskRestoreForceStop(String packageName, int userId) {
        mTasks.noteRuntimeForceStop(packageName, userId);
    }

    @VisibleForTesting
    public TaskRestoreController getTaskRestoreForTest() {
        return mTasks;
    }

    /** Consumed by one oom-adj pass. Null if nothing is armed. */
    public String consumeArmedAdjScene() {
        final String scene = mArmedAdjScene;
        mArmedAdjScene = null;
        return scene;
    }

    /** The user started the package again. The force-stop row is the one that drops. */
    public void noteUserForceStopCleared(String packageName, int userId) {
        if (packageName == null) {
            return;
        }
        mArbiter.setUserForceStop(packageName, userId, false);
    }

    @VisibleForTesting
    public void setEnabledForTest(boolean enabled) {
        if (!mConfig.tryReplace(mConfig.get().withEnabled(enabled))) {
            throw new IllegalStateException("rejected enabled=" + enabled);
        }
        onGatesChanged();
    }

    @VisibleForTesting
    public void setShadowModeForTest(boolean shadowMode) {
        if (!mConfig.tryReplace(mConfig.get().withShadowMode(shadowMode))) {
            throw new IllegalStateException("rejected shadowMode=" + shadowMode);
        }
        onGatesChanged();
    }

    @VisibleForTesting
    public void setFreezerEnabledForTest(boolean freezerEnabled) {
        if (!mConfig.tryReplace(mConfig.get().withFreezerEnabled(freezerEnabled))) {
            throw new IllegalStateException("rejected freezerEnabled=" + freezerEnabled);
        }
        onGatesChanged();
    }

    @VisibleForTesting
    public void setProtectionForTest(AppProtectionPolicy policy) {
        mArbiter.put(policy);
        reevaluateAll();
    }

    @VisibleForTesting
    public ProtectionArbiter getArbiterForTest() {
        return mArbiter;
    }

    @VisibleForTesting
    public void setMemoryEnabledForTest(boolean memoryEnabled) {
        if (!mConfig.tryReplace(mConfig.get().withMemoryEnabled(memoryEnabled))) {
            throw new IllegalStateException("rejected memoryEnabled=" + memoryEnabled);
        }
        onGatesChanged();
    }

    @VisibleForTesting
    @Nullable ManagedState getStateForTest(int uid) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec == null ? null : rec.state;
        }
    }

    @VisibleForTesting
    int getPidCountForTest(int uid) {
        synchronized (mLock) {
            return mTracker.pidCount(uid);
        }
    }

    @VisibleForTesting
    @Nullable String getPrimaryPackageForTest(int uid) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec == null ? null : rec.primaryPackage();
        }
    }

    @VisibleForTesting
    boolean hasProcessNameForTest(int uid, String processName) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec != null && rec.processNames.contains(processName);
        }
    }

    @VisibleForTesting
    @Nullable PolicyDecision getLastDecisionForTest(int uid) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec == null ? null : rec.lastDecision;
        }
    }

    @VisibleForTesting
    int getRecordedDecisionCountForTest() {
        synchronized (mLock) {
            return mStats.recorded();
        }
    }

    @VisibleForTesting
    int getDroppedActionCountForTest() {
        synchronized (mLock) {
            return mStats.dropped();
        }
    }

    /** Always zero. Present so tests can show the shadow path performs no action. */
    @VisibleForTesting
    int getExecutedActionCountForTest() {
        synchronized (mLock) {
            return mStats.executed();
        }
    }

    private void applyClearScene(String key) {
        final ClearScene scene = mScenes.select(key);
        if (scene == null || !mConfig.get().enabled) {
            return;
        }
        final boolean shadow = mConfig.get().shadowMode;
        final Integer strategy = mScenes.externalStrategy(key);
        final boolean forceStop = strategy != null && strategy == 1;
        final boolean removeTask = scene.flag(ClearScene.DO_REMOVE_TASK);
        final boolean athenaLmk = "athena_lmk".equals(scene.name);
        if (athenaLmk) {
            // Meminfo is read here, on this thread, before the trim kill is armed.
            mArmedAvailMb = readAvailableMb();
            if (!shadow) {
                mArmedAdjScene = scene.name;
            }
        }
        final ArrayList<SceneVictim> victims = new ArrayList<>();
        final long now = mClock.elapsedRealtime();
        synchronized (mLock) {
            for (int i = 0; i < mTracker.size(); i++) {
                final ApmProcessRecord rec = mTracker.valueAt(i);
                if (ClearSceneRunner.skipped(scene, factsFor(rec))) {
                    continue;
                }
                final String pkg = rec.primaryPackage();
                if (pkg == null) {
                    continue;
                }
                if (athenaLmk && ClearSceneTable.sappShouldKillMb(pkg) >= 0
                        && !athenaLmkKillsPackage(pkg, rec.minAdj)) {
                    continue;
                }
                if (sparedByPolicy(rec, now)) {
                    continue;
                }
                victims.add(new SceneVictim(rec.uid, pkg, rec.userId, livePids(rec)));
            }
        }
        if (shadow || mExecutor == null) {
            return;
        }
        for (int i = 0; i < victims.size(); i++) {
            final SceneVictim victim = victims.get(i);
            if (forceStop) {
                mExecutor.forceStopForScene(victim.packageName, victim.userId,
                        "apm:scene:" + key);
            } else {
                mExecutor.killForScene(victim.uid, victim.pids, "apm:scene:" + key);
            }
            if (removeTask) {
                mExecutor.removeTasksForPackage(victim.packageName, victim.userId);
            }
        }
    }

    private boolean sparedByPolicy(ApmProcessRecord rec, long now) {
        if (rec.forceStopped) {
            return false;
        }
        if (rec.packages.size() == 0) {
            return mArbiter.shouldSpareCachedKill(rec.primaryPackage(), rec.userId, now, false);
        }
        for (int i = 0; i < rec.packages.size(); i++) {
            if (mArbiter.shouldSpareCachedKill(rec.packages.valueAt(i), rec.userId, now, false)) {
                return true;
            }
        }
        return false;
    }

    private ClearSceneRunner.Facts factsFor(ApmProcessRecord rec) {
        final ClearSceneRunner.Facts facts = new ClearSceneRunner.Facts();
        facts.foreground = rec.foreground || rec.visible;
        facts.foregroundService = rec.foregroundService;
        facts.home = rec.home;
        facts.persistent = rec.persistent;
        facts.visibleWindow = rec.visible;
        facts.curAdj = rec.minAdj;
        facts.perceptible = rec.minAdj <= ProcessList.PERCEPTIBLE_APP_ADJ;
        facts.system = rec.systemUid;
        fillClearFacts(facts, rec.primaryPackage(), rec.userId, rec.foregroundAudio);
        for (int i = 0; i < rec.packages.size(); i++) {
            fillClearFacts(facts, rec.packages.valueAt(i), rec.userId, rec.foregroundAudio);
        }
        return facts;
    }

    /**
     * Role and session facts already cached on this thread. Does not read settings,
     * app ops, or audio service, so it is safe while the activity manager lock is held.
     * {@code liveAudio} is the boolean copied out of the process record under that lock.
     */
    public void fillClearFacts(ClearSceneRunner.Facts facts, @Nullable String packageName,
            int userId, boolean liveAudio) {
        if (facts == null) {
            return;
        }
        if (liveAudio) {
            facts.audio = true;
        }
        if (packageName == null) {
            return;
        }
        if (packageName.equals(mImeByUser.get(userId))) {
            facts.inputMethod = true;
        }
        final String key = roleKey(userId, packageName);
        if (mVpnKeys.contains(key)) {
            facts.vpn = true;
        }
        if (mA11yKeys.contains(key)) {
            facts.accessibility = true;
        }
        if (mAudioKeys.contains(key)) {
            facts.audio = true;
        }
        // The one lock-free read of navigation from under mLock: the uid is looked up in a
        // concurrent map and the controller answers under its own lock.
        final Integer navigatingUid = mNavigationUids.get(key);
        if (navigatingUid != null && mNavigation.isProtected(navigatingUid)) {
            facts.navigating = true;
        }
    }

    /**
     * Athena LMK trim. Adj below 300 never kills. A package in {@code sapp_should_be_kill}
     * kills only when the available MiB read on this thread is at or below its threshold.
     * Packages that are not in that list keep the adj gate only.
     */
    public boolean athenaLmkKillsPackage(@Nullable String packageName, int curAdj) {
        if (curAdj < ClearSceneTable.ATHENA_LMK_ADJ_THRESHOLD) {
            return false;
        }
        final int threshold = ClearSceneTable.sappShouldKillMb(packageName);
        if (threshold < 0) {
            return true;
        }
        final long availMb = mArmedAvailMb;
        return availMb >= 0L && availMb <= threshold;
    }

    @VisibleForTesting
    public void setAvailableBytesForTest(long bytes) {
        mAvailableBytesOverride = bytes;
        mAvailableBytesOverrideSet = true;
    }

    /** Same value {@code ProcessList#getMemoryInfo} stores in {@code availMem}. */
    private long readAvailableMb() {
        final long bytes;
        if (mAvailableBytesOverrideSet) {
            bytes = mAvailableBytesOverride;
        } else {
            try {
                bytes = Process.getMemAvailable();
            } catch (Throwable t) {
                return -1L;
            }
        }
        if (bytes < 0L) {
            return -1L;
        }
        return bytes / (1024L * 1024L);
    }

    @VisibleForTesting
    public void noteCurrentInputMethodForTest(int userId, @Nullable String packageName) {
        noteCurrentInputMethod(userId, packageName);
    }

    /** Test hook. The accessibility observer's effect without a settings provider. */
    @VisibleForTesting
    public void noteEnabledAccessibilityForTest(int userId, @Nullable String packageName) {
        if (packageName == null || !mA11yKeys.add(roleKey(userId, packageName))) {
            return;
        }
        mArbiter.setHardRole(packageName, userId, "accessibility", true);
    }

    /**
     * Settings and app-ops observers. Runs on this thread. Does not take the activity
     * manager lock. Navigation is not registered here: its GNSS fact arrives from the
     * location provider through {@code ActivityManagerInternal#noteGnssClientChanged},
     * and a location foreground service is already copied into the adj snapshot. Audio
     * focus is not registered either: the adj snapshot copies the audio capability and
     * the media-playback foreground-service type.
     */
    private void registerRoleObservers() {
        if (mRoleObserversRegistered || mContext == null || mHandler == null) {
            return;
        }
        mRoleObserversRegistered = true;
        final android.content.ContentResolver resolver = mContext.getContentResolver();
        mImeObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                    UserHandle user) {
                if (user == null || user.getIdentifier() == UserHandle.USER_ALL) {
                    refreshAllInputMethods();
                } else {
                    refreshInputMethod(user.getIdentifier());
                }
            }
        };
        mA11yObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                    UserHandle user) {
                if (user == null || user.getIdentifier() == UserHandle.USER_ALL) {
                    refreshAllAccessibility();
                } else {
                    refreshAccessibility(user.getIdentifier());
                }
            }
        };
        try {
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                    false, mImeObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false, mA11yObserver, UserHandle.USER_ALL);
        } catch (Throwable t) {
            Slog.w(TAG, "role settings observer not registered", t);
        }
        refreshAllInputMethods();
        refreshAllAccessibility();
        refreshDeviceOwner();
        registerVpnWatch();
    }

    private void refreshAllInputMethods() {
        for (int userId : userIds()) {
            refreshInputMethod(userId);
        }
    }

    private void refreshInputMethod(int userId) {
        if (mContext == null || userId < 0) {
            return;
        }
        String pkg = null;
        try {
            final String flat = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, userId);
            if (flat != null) {
                final ComponentName cn = ComponentName.unflattenFromString(flat);
                if (cn != null) {
                    pkg = cn.getPackageName();
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "input method read failed for user " + userId, t);
            return;
        }
        noteCurrentInputMethod(userId, pkg);
    }

    private void noteCurrentInputMethod(int userId, @Nullable String packageName) {
        final String previous = mImeByUser.get(userId);
        if (previous == null && packageName == null) {
            return;
        }
        if (previous != null && previous.equals(packageName)) {
            return;
        }
        if (previous != null) {
            mImeByUser.remove(userId, previous);
            mArbiter.setHardRole(previous, userId, "ime", false);
        }
        if (packageName != null) {
            mImeByUser.put(userId, packageName);
            mArbiter.setHardRole(packageName, userId, "ime", true);
        }
        reevaluateAll();
    }

    private void refreshAllAccessibility() {
        for (int userId : userIds()) {
            refreshAccessibility(userId);
        }
    }

    /**
     * {@link com.android.server.AccessibilityManagerInternal} does not list enabled
     * service packages. The secure setting is the same kind of fact as the input method
     * and is read on this thread.
     */
    private void refreshAccessibility(int userId) {
        if (mContext == null || userId < 0) {
            return;
        }
        String raw = null;
        try {
            raw = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, userId);
        } catch (Throwable t) {
            Slog.w(TAG, "accessibility setting read failed for user " + userId, t);
            return;
        }
        final ArraySet<String> next = new ArraySet<>();
        if (raw != null && raw.length() > 0) {
            final String[] parts = raw.split(":");
            for (int i = 0; i < parts.length; i++) {
                final ComponentName cn = ComponentName.unflattenFromString(parts[i]);
                if (cn != null && cn.getPackageName() != null) {
                    next.add(cn.getPackageName());
                }
            }
        }
        final String prefix = userId + ":";
        final ArrayList<String> stale = new ArrayList<>();
        for (String key : mA11yKeys) {
            if (key.startsWith(prefix) && !next.contains(key.substring(prefix.length()))) {
                stale.add(key);
            }
        }
        boolean changed = !stale.isEmpty();
        for (int i = 0; i < stale.size(); i++) {
            final String key = stale.get(i);
            mA11yKeys.remove(key);
            mArbiter.setHardRole(key.substring(prefix.length()), userId, "accessibility", false);
        }
        for (int i = 0; i < next.size(); i++) {
            final String pkg = next.valueAt(i);
            if (mA11yKeys.add(roleKey(userId, pkg))) {
                mArbiter.setHardRole(pkg, userId, "accessibility", true);
                changed = true;
            }
        }
        if (changed) {
            reevaluateAll();
        }
    }

    private void refreshDeviceOwner() {
        if (mContext == null) {
            return;
        }
        String pkg = null;
        int userId = UserHandle.USER_SYSTEM;
        try {
            final DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
            if (dpm == null) {
                return;
            }
            final ComponentName owner = dpm.getDeviceOwnerComponentOnAnyUser();
            if (owner != null) {
                pkg = owner.getPackageName();
                final int ownerUser = dpm.getDeviceOwnerUserId();
                if (ownerUser >= 0) {
                    userId = ownerUser;
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "device owner lookup failed", t);
            return;
        }
        if (pkg == null && mDeviceOwnerPackage == null) {
            return;
        }
        if (pkg != null && pkg.equals(mDeviceOwnerPackage) && userId == mDeviceOwnerUser) {
            return;
        }
        if (mDeviceOwnerPackage != null) {
            mArbiter.setHardRole(mDeviceOwnerPackage, mDeviceOwnerUser, "device-owner", false);
        }
        mDeviceOwnerPackage = pkg;
        mDeviceOwnerUser = pkg == null ? UserHandle.USER_NULL : userId;
        if (pkg != null) {
            mArbiter.setHardRole(pkg, userId, "device-owner", true);
        }
        reevaluateAll();
    }

    private void registerVpnWatch() {
        if (mContext == null) {
            return;
        }
        try {
            final AppOpsManager ops = mContext.getSystemService(AppOpsManager.class);
            if (ops == null) {
                return;
            }
            final AppOpsManager.OnOpChangedListener listener = (op, packageName) ->
                    post(() -> recheckVpnPackage(packageName));
            ops.startWatchingMode(AppOpsManager.OP_ACTIVATE_VPN, null /* all packages */,
                    listener);
            ops.startWatchingMode(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN, null, listener);
        } catch (Throwable t) {
            Slog.w(TAG, "vpn app-op watch not registered", t);
        }
    }

    private void recheckVpnPackage(@Nullable String packageName) {
        if (packageName == null || mContext == null) {
            return;
        }
        final ArrayList<int[]> ids = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mTracker.size(); i++) {
                final ApmProcessRecord rec = mTracker.valueAt(i);
                if (rec.matchesName(packageName)) {
                    ids.add(new int[] {rec.uid, rec.userId});
                }
            }
        }
        final AppOpsManager ops = mContext.getSystemService(AppOpsManager.class);
        if (ops == null) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < ids.size(); i++) {
            changed |= setVpnAllowed(ids.get(i)[0], ids.get(i)[1], packageName,
                    vpnOpAllowed(ops, ids.get(i)[0], packageName));
        }
        if (changed) {
            reevaluateAll();
        }
    }

    private void refreshVpnSessions(@Nullable List<ProcessSnapshot> processes) {
        if (mContext == null || processes == null || processes.isEmpty()) {
            return;
        }
        final AppOpsManager ops = mContext.getSystemService(AppOpsManager.class);
        if (ops == null) {
            return;
        }
        final ArraySet<String> seen = new ArraySet<>();
        boolean changed = false;
        for (int i = 0; i < processes.size(); i++) {
            final ProcessSnapshot snap = processes.get(i);
            if (snap == null || snap.packageName == null || snap.uid < 0) {
                continue;
            }
            final String key = roleKey(snap.userId, snap.packageName);
            if (!seen.add(key)) {
                continue;
            }
            changed |= setVpnAllowed(snap.uid, snap.userId, snap.packageName,
                    vpnOpAllowed(ops, snap.uid, snap.packageName));
        }
        if (changed) {
            reevaluateAll();
        }
    }

    private static boolean vpnOpAllowed(AppOpsManager ops, int uid, String packageName) {
        try {
            if (ops.checkOpNoThrow(AppOpsManager.OP_ACTIVATE_VPN, uid, packageName)
                    == AppOpsManager.MODE_ALLOWED) {
                return true;
            }
            return ops.checkOpNoThrow(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN, uid, packageName)
                    == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** @return true when the cached session changed */
    private boolean setVpnAllowed(int uid, int userId, String packageName, boolean allowed) {
        final String key = roleKey(userId, packageName);
        if (allowed) {
            if (!mVpnKeys.add(key)) {
                return false;
            }
            mArbiter.setRuntimeSession(packageName, userId, "vpn", true);
            return true;
        }
        if (!mVpnKeys.remove(key)) {
            return false;
        }
        mArbiter.setRuntimeSession(packageName, userId, "vpn", false);
        return true;
    }

    private void applyAudioRoles(@Nullable List<ProcessSnapshot> processes) {
        final ArraySet<String> now = new ArraySet<>();
        if (processes != null) {
            for (int i = 0; i < processes.size(); i++) {
                final ProcessSnapshot snap = processes.get(i);
                if (snap == null || snap.packageName == null || !snap.foregroundAudio) {
                    continue;
                }
                now.add(roleKey(snap.userId, snap.packageName));
            }
        }
        boolean changed = false;
        final ArrayList<String> stale = new ArrayList<>();
        for (String key : mAudioKeys) {
            if (!now.contains(key)) {
                stale.add(key);
            }
        }
        for (int i = 0; i < stale.size(); i++) {
            final String key = stale.get(i);
            mAudioKeys.remove(key);
            final int cut = key.indexOf(':');
            if (cut <= 0) {
                continue;
            }
            mArbiter.setRuntimeSession(key.substring(cut + 1),
                    Integer.parseInt(key.substring(0, cut)), "audio", false);
            changed = true;
        }
        for (int i = 0; i < now.size(); i++) {
            final String key = now.valueAt(i);
            if (!mAudioKeys.add(key)) {
                continue;
            }
            final int cut = key.indexOf(':');
            mArbiter.setRuntimeSession(key.substring(cut + 1),
                    Integer.parseInt(key.substring(0, cut)), "audio", true);
            changed = true;
        }
        if (changed) {
            reevaluateAll();
        }
    }

    private int[] userIds() {
        try {
            final UserManagerInternal users = LocalServices.getService(UserManagerInternal.class);
            if (users != null) {
                final int[] ids = users.getUserIds();
                if (ids != null && ids.length > 0) {
                    return ids;
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "user list unavailable", t);
        }
        return new int[] {UserHandle.USER_SYSTEM};
    }

    private static String roleKey(int userId, String packageName) {
        return userId + ":" + packageName;
    }

    private static int[] livePids(ApmProcessRecord rec) {
        int count = 0;
        for (int i = 0; i < rec.pids.size(); i++) {
            if (rec.pids.valueAt(i).pid > 0) {
                count++;
            }
        }
        final int[] pids = new int[count];
        int write = 0;
        for (int i = 0; i < rec.pids.size(); i++) {
            final int pid = rec.pids.valueAt(i).pid;
            if (pid > 0) {
                pids[write++] = pid;
            }
        }
        return pids;
    }

    private static final class SceneVictim {
        final int uid;
        final String packageName;
        final int userId;
        final int[] pids;

        SceneVictim(int uid, String packageName, int userId, int[] pids) {
            this.uid = uid;
            this.packageName = packageName;
            this.userId = userId;
            this.pids = pids;
        }
    }

    private void reevaluateAll() {
        final Runnable runnable = () -> {
            synchronized (mLock) {
                final long now = mClock.elapsedRealtime();
                final ApmConfig config = mConfig.get();
                for (int i = 0; i < mTracker.size(); i++) {
                    reviewFreezeLocked(mTracker.valueAt(i), config, now);
                }
                publishAllowNetLocked(now);
            }
            // One enable for every cut this sweep caused. Outside the lock: the enable runs
            // the connectivity service's socket scan on the thread that calls it.
            mNet.flushDestroyTrigger();
        };
        if (mHandler != null) {
            mHandler.post(runnable);
        } else {
            runnable.run();
        }
    }

    /**
     * Republish the uids whose merged policy allows the network while frozen. The network
     * controller reads this to decide who is never cut and who must be reported as
     * unfrozen. Caller holds {@link #mLock}.
     */
    private void publishAllowNetLocked(long now) {
        int count = 0;
        final int[] uids = new int[mTracker.size()];
        for (int i = 0; i < mTracker.size(); i++) {
            final ApmProcessRecord rec = mTracker.valueAt(i);
            if (allowsNetworkWhileFrozen(rec, now)) {
                uids[count++] = rec.uid;
            }
        }
        mNet.publishAllowNetSnapshot(count == uids.length ? uids : Arrays.copyOf(uids, count));
    }

    /**
     * {@code allowNetworkWhileFrozen} of any package of the uid. Any one of them is enough,
     * which is how the arbiter folds a ban of the same kind.
     */
    private boolean allowsNetworkWhileFrozen(ApmProcessRecord rec, long now) {
        if (rec.packages.size() == 0) {
            return mArbiter.merge(rec.primaryPackage(), rec.userId, now).allowNetworkWhileFrozen;
        }
        for (int i = 0; i < rec.packages.size(); i++) {
            if (mArbiter.merge(rec.packages.valueAt(i), rec.userId, now).allowNetworkWhileFrozen) {
                return true;
            }
        }
        return false;
    }

    private static Integer parseExplainUid(String target) {
        if (target == null || target.length() == 0 || target.length() > 9) {
            return null;
        }
        for (int i = 0; i < target.length(); i++) {
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

    private void post(ApmEvent event) {
        if (mHandler != null) {
            mHandler.post(() -> handleEvent(event));
        } else {
            handleEvent(event);
        }
    }

    private void post(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (mHandler != null) {
            mHandler.post(runnable);
        } else {
            runnable.run();
        }
    }

    /**
     * Runs the platform work queued under {@link #mLock}, once that lock has been released.
     * Called at the end of every entry point that takes the lock, on this thread, so the
     * queue keeps decision order and the activity manager locks, the cgroup writes and the
     * proc-node writes never nest inside the service lock.
     */
    private void drainOffLockWork() {
        if (mDrainingOffLock) {
            return;
        }
        mDrainingOffLock = true;
        try {
            while (!mOffLockWork.isEmpty()) {
                mOffLockWork.remove(0).run();
            }
        } finally {
            mDrainingOffLock = false;
        }
    }

    /**
     * Runs {@code body} under {@link #mLock} and reports a hold over
     * {@link ApmConstants#LOCK_WARN_MS}. Every caller is on this service's thread, so the time
     * measured here is the hold: that is how long a binder thread waits for this lock, which
     * is the wait this service must keep short.
     */
    private <T> T callLocked(java.util.function.Supplier<T> body) {
        final long start = SystemClock.uptimeMillis();
        final T out;
        synchronized (mLock) {
            out = body.get();
        }
        noteLockHold(SystemClock.uptimeMillis() - start);
        return out;
    }

    /** {@link #callLocked} for a body whose result is not used. */
    private void runLocked(Runnable body) {
        callLocked(() -> {
            body.run();
            return null;
        });
    }

    private void noteLockHold(long heldMs) {
        if (heldMs < ApmConstants.LOCK_WARN_MS) {
            return;
        }
        mStats.noteLongLockHold(heldMs);
        Slog.w(TAG, "service lock held " + heldMs + " ms");
    }

    private void postDrainOom() {
        if (mHandler != null) {
            mHandler.post(this::drainOom);
        } else {
            drainOom();
        }
    }

    private void drainOom() {
        final ApmEvent event;
        synchronized (mQueueLock) {
            event = mPendingOom;
            mPendingOom = null;
            mOomQueued = false;
        }
        if (event != null) {
            handleEvent(event);
        }
    }

    private void handleEvent(ApmEvent event) {
        final ApmConfig config = mConfig.get();
        if (!config.enabled) {
            return;
        }
        if (event.kind == ApmEvent.Kind.OOM_ADJ_COMPLETED) {
            // App ops, settings, and device policy stay off the activity manager lock.
            applyAudioRoles(event.processes);
            refreshVpnSessions(event.processes);
            refreshDeviceOwner();
            applyLocationFgs(event.processes);
        }
        final long now = mClock.elapsedRealtime();
        final long graceMs = config.freezeDelayMs;
        runLocked(() -> {
            switch (event.kind) {
                case PROCESS_STARTED:
                    mTracker.onProcessStarted(event.pid, event.uid, event.userId, event.processName,
                            event.packageName, event.startSeq, event.persistent, now, graceMs);
                    if (event.packageName != null) {
                        mArbiter.setUserForceStop(event.packageName, event.userId, false);
                        mTasks.clearRuntimeForceStop(event.packageName, event.userId);
                        mRevival.noteForceStop(event.packageName, event.userId, false);
                    }
                    considerLocked(event.uid, config, now);
                    break;
                case PROCESS_DIED:
                    mTracker.onProcessDied(event.pid, event.uid, event.userId, event.processName,
                            event.startSeq);
                    considerLocked(event.uid, config, now);
                    break;
                case TOP_RESUMED:
                    final int previous = mTracker.onTopResumed(event.uid, event.userId,
                            event.processName, now, graceMs);
                    if (event.uid >= 0) {
                        considerLocked(event.uid, config, now);
                    }
                    if (previous >= 0) {
                        considerLocked(previous, config, now);
                    }
                    break;
                case OOM_ADJ_COMPLETED:
                    mTracker.onOomAdj(event.processes, now, graceMs);
                    final ArraySet<Integer> uids = new ArraySet<>();
                    for (int i = 0; i < event.processes.size(); i++) {
                        final ProcessSnapshot snap = event.processes.get(i);
                        if (snap != null) {
                            uids.add(snap.uid);
                        }
                    }
                    for (int i = 0; i < uids.size(); i++) {
                        considerLocked(uids.valueAt(i), config, now);
                    }
                    noteOomAdjPass(now);
                    break;
                default:
                    break;
            }
            publishForegroundLocked(config);
        });
        // The freeze this event decided is committed and its cut is queued by now, and one
        // enable per sweep covers every cut of the sweep.
        drainOffLockWork();
        mNet.flushDestroyTrigger();
        // Navigation runs here, outside mLock. The controller has its own lock, and only
        // fillClearFacts and dump read it the other way round.
        switch (event.kind) {
            case TOP_RESUMED:
                onNavigationForeground(event.uid, now);
                break;
            case PROCESS_DIED:
                if (event.uid >= 0 && !uidHasLiveProcess(event.uid)) {
                    forgetNavigationUid(event.uid);
                }
                break;
            default:
                break;
        }
    }

    /** Foreground-uid list for {@code /proc/fg_info/fg_uids}. Skipped in shadow mode. */
    private void publishForegroundLocked(ApmConfig config) {
        if (!config.enabled || config.shadowMode) {
            mPublishedFg = null;
            return;
        }
        final int[] uids = mTracker.copyForegroundUids();
        if (mPublishedFg != null && Arrays.equals(mPublishedFg, uids)) {
            return;
        }
        mPublishedFg = uids;
        // Queued: /proc write outside the service lock, like every other platform write here.
        mPlatform.run(() -> mKnobs.writeFgUids(uids));
    }

    /**
     * Memory-factor callback from {@code AppProfiler}. Posted onto this service's thread.
     * Does not take the activity manager lock.
     */
    public void noteMemoryPressure(int level) {
        post(() -> handlePressure(level));
    }

    private void handlePressure(int level) {
        runLocked(() -> {
            mMemory.onPressureLocked(level, mConfig.get(), mTracker, mClock.elapsedRealtime());
            noteReleased();
        });
        drainOffLockWork();
        mNet.flushDestroyTrigger();
    }

    private void onMemoryRecheck(int generation) {
        runLocked(() -> {
            mMemory.handleRecheckLocked(generation, mConfig.get(), mTracker,
                    mClock.elapsedRealtime());
            noteReleased();
        });
        drainOffLockWork();
        mNet.flushDestroyTrigger();
    }

    private void considerLocked(int uid, ApmConfig config, long now) {
        final ApmProcessRecord rec = mTracker.get(uid);
        if (rec == null) {
            return;
        }
        final PolicyDecision decision = mPolicy.decide(rec, config, now);
        final PolicyDecision previous = rec.lastDecision;
        rec.lastDecision = decision;
        if (!decision.sameOutcome(previous)) {
            mStats.record(decision);
            if (decision.action != PolicyDecision.Action.NONE
                    && !FreezeController.gatesOpen(config)) {
                Slog.i(TAG, "shadow drop " + decision.summarize());
            }
        }
        reviewFreezeLocked(rec, config, now);
    }

    private void reviewFreezeLocked(ApmProcessRecord rec, ApmConfig config, long now) {
        mFreeze.review(rec, config, now);
        armPendingLocked(rec);
        if (!rec.frozenByApm) {
            synchronized (mUnfreezeWait) {
                mUnfreezeWait.notifyAll();
            }
        }
    }

    private void armPendingLocked(ApmProcessRecord rec) {
        if (rec == null || rec.pendingFreezeGen <= 0) {
            return;
        }
        final int uid = rec.uid;
        final int generation = rec.pendingFreezeGen;
        final long delay = rec.pendingFreezeDelayMs;
        rec.pendingFreezeGen = 0;
        final Runnable alarm = () -> runLocked(() -> {
            if (!mFreeze.noteAlarmFired(uid, generation)) {
                return;
            }
            final ApmProcessRecord current = mTracker.get(uid);
            if (current == null || !mConfig.get().enabled) {
                return;
            }
            reviewFreezeLocked(current, mConfig.get(), mClock.elapsedRealtime());
        });
        mFreeze.rememberAlarm(uid, generation, alarm);
        postDelayedExternal(alarm, delay);
    }

    /**
     * Re-read the platform freeze state once, {@link ApmConstants#FREEZE_VERIFY_DELAY_MS} after
     * a commit. {@code freezeUid} only queues the freezer's work, so a uid the freezer never
     * got to would stay frozen on paper: the job and alarm gates read that paper, and the
     * network side cuts on it. A uid with nothing frozen and nothing queued is released, so no
     * uid is left cut or refused a job for a freeze that does not exist.
     */
    private void armFreezeVerificationLocked(ApmProcessRecord rec) {
        final int uid = rec.uid;
        cancelFreezeVerificationLocked(uid);
        if (mExecutor == null || ApmConstants.FREEZE_VERIFY_DELAY_MS <= 0) {
            return;
        }
        final Runnable verify = () -> mPlatform.freezeState(uid, state -> {
            if (state != ApmFreezeResult.STATE_FAILED) {
                // Frozen, or still queued in the freezer: the commit stands.
                return;
            }
            final ApmProcessRecord current = mTracker.get(uid);
            if (current == null || !current.frozenByApm) {
                // Released or re-committed since: not what this check is about.
                return;
            }
            mFreeze.unfreezeIfOurs(mTracker, mConfig.get(), uid, mClock.elapsedRealtime(),
                    "freeze-not-applied");
            mStats.noteFreezeVerifyFailure();
            Slog.w(TAG, "freeze did not apply uid=" + uid);
        });
        mVerifiers.put(uid, verify);
        postDelayedExternal(verify, ApmConstants.FREEZE_VERIFY_DELAY_MS);
    }

    private void cancelFreezeVerificationLocked(int uid) {
        final Runnable verify = mVerifiers.remove(uid);
        if (verify != null) {
            removeExternal(verify);
        }
    }

    private void onGatesChanged() {
        runLocked(() -> {
            final ApmConfig config = mConfig.get();
            mFreeze.onGatesChanged(mTracker, config, mClock.elapsedRealtime());
            mMemory.onGatesChanged(config);
            if (!config.enabled || config.shadowMode) {
                mPublishedFg = null;
            }
            noteReleased();
        });
        drainOffLockWork();
        if (!FreezeController.gatesOpen(mConfig.get())) {
            // No freeze survives closed gates, so no cut may either.
            mNet.onMasterOffOrShadow();
        }
        if (mConfig.get().enabled) {
            if (mOomAdjWatchdog == null) {
                mLastOomAdjMs = mClock.elapsedRealtime();
                scheduleOomAdjWatchdog();
            }
        } else {
            cancelOomAdjWatchdog();
        }
    }

    /**
     * An oom-adj pass arrived. The freezer facts are refreshed by those passes, so the
     * watchdog below reports a device where they stop arriving while this service is on.
     */
    private void noteOomAdjPass(long now) {
        mLastOomAdjMs = now;
        scheduleOomAdjWatchdog();
    }

    private void scheduleOomAdjWatchdog() {
        cancelOomAdjWatchdog();
        final Runnable watchdog = this::onOomAdjWatchdog;
        mOomAdjWatchdog = watchdog;
        postDelayedExternal(watchdog, ApmConstants.OOM_ADJ_WATCHDOG_MS);
    }

    private void cancelOomAdjWatchdog() {
        final Runnable watchdog = mOomAdjWatchdog;
        mOomAdjWatchdog = null;
        if (watchdog != null) {
            removeExternal(watchdog);
        }
    }

    private void onOomAdjWatchdog() {
        mOomAdjWatchdog = null;
        if (!mConfig.get().enabled) {
            return;
        }
        final long idleMs = mClock.elapsedRealtime() - mLastOomAdjMs;
        if (idleMs < ApmConstants.OOM_ADJ_WATCHDOG_MS) {
            return;
        }
        mStats.noteStaleOomAdjPass();
        Slog.w(TAG, "no oom-adj pass for " + idleMs + " ms");
        scheduleOomAdjWatchdog();
    }

    private void registerConfigListener() {
        registerRoleObservers();
        refreshConfigFromDeviceConfig();
        if (mConfigListenerRegistered) {
            return;
        }
        try {
            DeviceConfig.addOnPropertiesChangedListener(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    runnable -> {
                        if (mHandler != null) {
                            mHandler.post(runnable);
                        }
                    },
                    properties -> refreshConfigFromDeviceConfig());
            mConfigListenerRegistered = true;
        } catch (Throwable t) {
            Slog.w(TAG, "APM config listener not registered", t);
        }
    }

    private void refreshConfigFromDeviceConfig() {
        final ApmConfig previous = mConfig.get();
        try {
            final boolean enabled = readBoolean(ApmConstants.KEY_ENABLED,
                    mShellEnabled != null ? mShellEnabled : previous.enabled);
            final boolean shadow = readBoolean(ApmConstants.KEY_SHADOW_MODE,
                    mShellShadow != null ? mShellShadow : previous.shadowMode);
            final boolean freezer = readBoolean(ApmConstants.KEY_FREEZER_ENABLED,
                    mShellFreezer != null ? mShellFreezer : previous.freezerEnabled);
            final boolean memory = readBoolean(ApmConstants.KEY_MEMORY_ENABLED,
                    previous.memoryEnabled);
            final long freezeDelay = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_FREEZE_DELAY_MS, previous.freezeDelayMs);
            final long bigDelay = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_BIG_APP_FREEZE_DELAY_MS, previous.bigAppFreezeDelayMs);
            final int churnLimit = DeviceConfig.getInt(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_CHURN_LIMIT_60S, previous.churnLimit60s);
            final long cooldown = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_CHURN_COOLDOWN_MS, previous.churnCooldownMs);
            final boolean netEnabled = readBoolean(ApmConstants.KEY_NETWORK_FREEZE_ENABLED,
                    previous.networkFreezeEnabled);
            final boolean netForce = readBoolean(ApmConstants.KEY_NET_FORCE_SOCKET_DESTROY,
                    previous.netForceSocketDestroy);
            final long netDelay = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_NET_FREEZE_DELAY_MS, previous.netFreezeDelayMs);
            final long netGameDelay = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_NET_FREEZE_DELAY_GAME_MS, previous.netFreezeDelayGameMs);
            final int[] netRelax = parseUidList(DeviceConfig.getString(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    ApmConstants.KEY_NET_RELAX_UID_LIST, ApmConstants.DEFAULT_NET_RELAX_UID_LIST));
            final NavigationPolicyConfig navPrevious = mNavigationConfig;
            final NavigationPolicyConfig navCandidate = new NavigationPolicyConfig(
                    readBoolean(ApmConstants.KEY_NAVIGATION_ENABLED, navPrevious.enabled),
                    navPrevious.enterDebounceMs, navPrevious.exitGraceMs, navPrevious.recentTopMs,
                    readBoolean(ApmConstants.KEY_NAVIGATION_ADJ_CLAMP_ENABLED,
                            navPrevious.adjClampEnabled),
                    clampNavigationAdj(DeviceConfig.getInt(
                            DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                            ApmConstants.KEY_NAVIGATION_ADJ, navPrevious.adjClamp)),
                    navPrevious.allowlist, navPrevious.denylist);
            applyNavigationConfig(navCandidate);
            final ApmConfig candidate = new ApmConfig(enabled, shadow, freezer, memory, freezeDelay,
                    bigDelay, churnLimit, cooldown, netEnabled, netForce, netDelay, netGameDelay,
                    netRelax, previous.generation);
            if (!mConfig.tryReplace(candidate)) {
                Slog.w(TAG, "rejected APM config; keeping generation " + previous.generation);
            } else {
                onGatesChanged();
                // The network controller reads the same instance, so a switch that changed
                // here takes effect on its next pass; this is what makes it take effect now.
                mNet.onConfigChanged();
            }
        } catch (Throwable t) {
            Slog.w(TAG, "APM config read failed; keeping generation " + previous.generation, t);
        }
    }

    /** {@code uid,uid,...}. A missing or empty value is the empty list. */
    private static int[] parseUidList(@Nullable String raw) {
        if (raw == null || raw.length() == 0) {
            return new int[0];
        }
        final ArraySet<Integer> uids = new ArraySet<>();
        int start = 0;
        for (int i = 0; i <= raw.length(); i++) {
            if (i < raw.length() && raw.charAt(i) != ',') {
                continue;
            }
            final String one = raw.substring(start, i).trim();
            start = i + 1;
            if (one.length() == 0) {
                continue;
            }
            try {
                uids.add(Integer.parseInt(one));
            } catch (NumberFormatException e) {
                Slog.w(TAG, "ignoring uid list entry '" + one + "'");
            }
        }
        final int[] out = new int[uids.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = uids.valueAt(i);
        }
        return out;
    }

    private static boolean readBoolean(String key, boolean fallback) {
        final String raw = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, key);
        if (raw == null) {
            return fallback;
        }
        return Boolean.parseBoolean(raw);
    }

    /** DeviceConfig carries any int; only a value that can lower an adj is meaningful. */
    private static int clampNavigationAdj(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, ApmConstants.MAX_NAVIGATION_ADJ);
    }

    /**
     * A uid gained or lost the GNSS provider. The location provider calls this from its
     * request and disable paths and must not be made to wait, so this method only posts:
     * classification reads package state, which is not safe from there.
     *
     * <p>{@code packageName} may be null when the work source has no name for the uid.
     */
    public void noteGnssClientChanged(int uid, @Nullable String packageName, boolean active) {
        if (uid < 0 || !isEnabled()) {
            return;
        }
        post(() -> handleGnssChanged(uid, packageName, active));
    }

    /**
     * Adj ceiling for a package that is navigating, or -1 when navigation does not apply.
     * Called from the adj pass, which already holds the activity manager lock: the uid
     * lookup is lock free, and the controller takes only its own lock.
     */
    public int navigationAdjClamp(@Nullable String packageName, int userId) {
        final ApmConfig config = mConfig.get();
        final NavigationPolicyConfig nav = mNavigationConfig;
        if (packageName == null || !config.enabled || config.shadowMode || !nav.enabled
                || !nav.adjClampEnabled) {
            return -1;
        }
        final Integer uid = mNavigationUids.get(roleKey(userId, packageName));
        if (uid == null || !mNavigation.isProtected(uid)) {
            return -1;
        }
        return nav.adjClamp;
    }

    /**
     * Adj floor for a package that is exempt for as long as it is installed, or -1.
     * LMKD picks its victim by adj and never reads {@code denyKill}, so without a floor the
     * exemption would only cover this service's own freeze and kill paths. Same shape as
     * {@link #navigationAdjClamp}: shadow mode and a disabled master switch return no floor.
     * Called from the adj pass, which holds the activity manager lock; nothing here takes
     * any other lock.
     */
    public int alwaysExemptAdjFloor(@Nullable String packageName) {
        final ApmConfig config = mConfig.get();
        if (packageName == null || !config.enabled || config.shadowMode
                || !ProtectionArbiter.isAlwaysExempt(packageName)) {
            return -1;
        }
        return ApmConstants.ALWAYS_EXEMPT_ADJ_FLOOR;
    }

    /** One row per uid the controller has seen, for dumpsys and tests. */
    public List<NavigationProtectionController.Snapshot> navigationSnapshots() {
        return mNavigation.snapshots();
    }

    @VisibleForTesting
    public boolean isNavigatingForTest(int uid) {
        return mNavigation.isProtected(uid);
    }

    @VisibleForTesting
    public void setNavigationConfigForTest(NavigationPolicyConfig config) {
        applyNavigationConfig(config);
    }

    private void handleGnssChanged(int uid, @Nullable String packageName, boolean active) {
        final long now = mClock.elapsedRealtime();
        noteNavigationUid(uid, packageName);
        boolean changed = mNavigation.onGnssChanged(uid, packageName, active, now);
        changed |= classifyNavigation(uid, now);
        if (changed) {
            onNavigationProtectedChanged(uid);
        }
        scheduleNavigationTick(now);
    }

    /**
     * Location foreground-service facts from one adj pass. A uid counts as active when
     * any of its rows says so, which is how the tracker folds the same rows; a uid the
     * pass stops reporting is cleared. Runs on this thread and never under {@link #mLock}.
     */
    private void applyLocationFgs(@Nullable List<ProcessSnapshot> processes) {
        final ArraySet<Integer> active = new ArraySet<>();
        if (processes != null) {
            for (int i = 0; i < processes.size(); i++) {
                final ProcessSnapshot snap = processes.get(i);
                if (snap == null || !snap.locationFgs) {
                    continue;
                }
                if (active.add(snap.uid)) {
                    noteNavigationUid(snap.uid, snap.packageName);
                }
            }
        }
        final long now = mClock.elapsedRealtime();
        final ArraySet<Integer> changed = new ArraySet<>();
        for (int i = mLocationFgsUids.size() - 1; i >= 0; i--) {
            final int uid = mLocationFgsUids.valueAt(i);
            if (active.contains(uid)) {
                continue;
            }
            mLocationFgsUids.removeAt(i);
            if (mNavigation.onLocationFgsChanged(uid, false, now)) {
                changed.add(uid);
            }
        }
        for (int i = 0; i < active.size(); i++) {
            final int uid = active.valueAt(i);
            if (mLocationFgsUids.add(uid) && mNavigation.onLocationFgsChanged(uid, true, now)) {
                changed.add(uid);
            }
        }
        for (int i = 0; i < changed.size(); i++) {
            onNavigationProtectedChanged(changed.valueAt(i));
        }
        scheduleNavigationTick(now);
    }

    /**
     * Top resumed. The recency window is the only fact here, so this confirms an
     * allowlisted uid and is the instant an older one is checked again.
     */
    private void onNavigationForeground(int uid, long now) {
        if (uid < 0) {
            return;
        }
        seedNavigationPackage(uid);
        boolean changed = mNavigation.noteForeground(uid, now);
        changed |= classifyNavigation(uid, now);
        if (changed) {
            onNavigationProtectedChanged(uid);
        }
        scheduleNavigationTick(now);
    }

    /** @return true when the protected state changed */
    private boolean classifyNavigation(int uid, long now) {
        final String pkg = mNavigationPackages.get(uid);
        return mNavigation.setClassifier(uid, mNavigationConfig.isAllowed(pkg),
                mNavigationConfig.isDenied(pkg), now);
    }

    /**
     * Name a uid the tracker knows about but no GNSS event or adj pass has named yet, so
     * an allowlisted package can still be classified. Takes {@link #mLock} briefly.
     */
    private void seedNavigationPackage(int uid) {
        if (mNavigationPackages.containsKey(uid)) {
            return;
        }
        final String pkg;
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            pkg = rec == null ? null : rec.primaryPackage();
        }
        noteNavigationUid(uid, pkg);
    }

    /**
     * Remember which package a uid answers to. The uid to name map keeps the first name so
     * a repeated adj pass cannot flip it; the reverse map takes every name, because a work
     * source and a process record can disagree on the package and still mean one uid.
     */
    private void noteNavigationUid(int uid, @Nullable String packageName) {
        if (uid < 0 || packageName == null) {
            return;
        }
        if (!mNavigationPackages.containsKey(uid)) {
            mNavigationPackages.put(uid, packageName);
        }
        mNavigationUids.putIfAbsent(roleKey(UserHandle.getUserId(uid), packageName), uid);
    }

    /**
     * Mirror one navigation state change into the arbiter. Only called when the controller
     * reported a change, and never from under {@link #mLock}: this reaches the arbiter,
     * the freezer, and the handler.
     */
    private void onNavigationProtectedChanged(int uid) {
        final String pkg = mNavigationPackages.get(uid);
        if (pkg == null) {
            return;
        }
        final boolean protectedNow = mNavigation.isProtected(uid);
        mArbiter.setRuntimeSession(pkg, UserHandle.getUserId(uid), "navigation", protectedNow);
        if (protectedNow) {
            // The uid can already be frozen when navigation starts mid-drive.
            noteStartUnfreeze(uid);
        }
        reevaluateAll();
    }

    /**
     * Drop every navigation fact for a uid whose last process is gone. Without this the
     * controller would hold a protected state, and the arbiter a row, for a package that
     * is no longer running.
     */
    private void forgetNavigationUid(int uid) {
        final String pkg = mNavigationPackages.remove(uid);
        if (pkg != null) {
            mNavigationUids.remove(roleKey(UserHandle.getUserId(uid), pkg));
        }
        mLocationFgsUids.remove(uid);
        if (mNavigation.removeUid(uid) && pkg != null) {
            mArbiter.setRuntimeSession(pkg, UserHandle.getUserId(uid), "navigation", false);
            reevaluateAll();
        }
        scheduleNavigationTick(mClock.elapsedRealtime());
    }

    /** The tracker keeps a record after the last process dies, so count pids instead. */
    private boolean uidHasLiveProcess(int uid) {
        synchronized (mLock) {
            return mTracker.pidCount(uid) > 0;
        }
    }

    /**
     * The controller only advances a candidate or expires a window when it is asked, so
     * one timer is armed for the earliest instant it reports. A later instant leaves the
     * armed timer alone; an earlier one replaces it, and no instant is reported twice.
     */
    private void scheduleNavigationTick(long now) {
        final long deadline = mNavigation.nextDeadlineMs(now);
        if (deadline == Long.MAX_VALUE) {
            cancelNavigationTick();
            return;
        }
        if (mNavigationTick != null && mNavigationTickAtMs <= deadline) {
            return;
        }
        cancelNavigationTick();
        final Runnable tick = () -> {
            // Cleared first so the deadline handler can arm the next timer itself.
            mNavigationTick = null;
            mNavigationTickAtMs = Long.MAX_VALUE;
            handleNavigationDeadline();
        };
        mNavigationTick = tick;
        mNavigationTickAtMs = deadline;
        postDelayedExternal(tick, Math.max(0L, deadline - now));
    }

    private void cancelNavigationTick() {
        if (mNavigationTick == null) {
            return;
        }
        removeExternal(mNavigationTick);
        mNavigationTick = null;
        mNavigationTickAtMs = Long.MAX_VALUE;
    }

    private void handleNavigationDeadline() {
        final long now = mClock.elapsedRealtime();
        final List<Integer> changed = mNavigation.onTimer(now);
        for (int i = 0; i < changed.size(); i++) {
            onNavigationProtectedChanged(changed.get(i));
        }
        scheduleNavigationTick(now);
    }

    /**
     * Swap the navigation policy and mirror whatever it changes. The controller keeps its
     * records, so a DeviceConfig refresh cannot drop a live navigation session. Every uid
     * with a known package is reclassified first, because the allowlist can change too.
     */
    private void applyNavigationConfig(NavigationPolicyConfig config) {
        if (config == null || config == mNavigationConfig) {
            return;
        }
        mNavigationConfig = config;
        final long now = mClock.elapsedRealtime();
        final ArraySet<Integer> changed = new ArraySet<>();
        for (int i = 0; i < mNavigationPackages.size(); i++) {
            final int uid = mNavigationPackages.keyAt(i);
            final String pkg = mNavigationPackages.valueAt(i);
            if (mNavigation.setClassifier(uid, config.isAllowed(pkg), config.isDenied(pkg), now)) {
                changed.add(uid);
            }
        }
        final List<Integer> swept = mNavigation.setConfig(config, now);
        for (int i = 0; i < swept.size(); i++) {
            changed.add(swept.get(i));
        }
        for (int i = 0; i < changed.size(); i++) {
            onNavigationProtectedChanged(changed.valueAt(i));
        }
        scheduleNavigationTick(now);
    }

    /**
     * Post an unfreeze for a uid this controller froze. Does not take the activity manager
     * lock and does not wait. Safe to call while holding that lock.
     */
    public void noteStartUnfreeze(int uid) {
        if (uid < 0 || !isEnabled()) {
            return;
        }
        post(() -> unfreezeForStart(uid, "start"));
    }

    /** Same as {@link #noteStartUnfreeze(int)} for every uid recorded under {@code packageName}. */
    public void noteStartUnfreezePackage(String packageName) {
        if (packageName == null || packageName.length() == 0 || !isEnabled()) {
            return;
        }
        post(() -> unfreezePackageForStart(packageName));
    }

    /**
     * Unfreeze every uid this service froze under {@code packageName} and wait up to
     * {@link ApmConstants#UNFREEZE_WAIT_MS}. Caller must not hold the activity manager lock
     * and must not be the APM thread.
     *
     * <p>Every read here is lock free: this runs on an activity manager binder thread, and
     * queueing behind {@link #mLock} is what turns a slow freeze path into a wedged binder
     * pool. A wait that runs out asks again and reports, so a freeze this service cannot lift
     * is visible in the log and in the dump instead of silently timing out.
     */
    public void awaitUnfreezePackage(String packageName) {
        if (packageName == null || packageName.length() == 0) {
            return;
        }
        noteStartUnfreezePackage(packageName);
        if (!matchesFrozenName(packageName)) {
            return;
        }
        final long deadline = SystemClock.uptimeMillis() + ApmConstants.UNFREEZE_WAIT_MS;
        synchronized (mUnfreezeWait) {
            while (matchesFrozenName(packageName)) {
                final long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    mUnfreezeWait.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (matchesFrozenName(packageName)) {
            noteStartUnfreezePackage(packageName);
            mStats.noteUnfreezeTimeout();
            Slog.w(TAG, "unfreeze timeout package=" + packageName);
        }
    }

    /**
     * Unfreeze a uid this controller froze and wait up to {@link ApmConstants#UNFREEZE_WAIT_MS}.
     * Caller must not hold the activity manager lock and must not be the APM thread.
     */
    public void awaitUnfreeze(int uid) {
        if (uid < 0) {
            return;
        }
        noteStartUnfreeze(uid);
        if (!mFrozenUids.contains(uid)) {
            return;
        }
        final long deadline = SystemClock.uptimeMillis() + ApmConstants.UNFREEZE_WAIT_MS;
        synchronized (mUnfreezeWait) {
            while (mFrozenUids.contains(uid)) {
                final long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    mUnfreezeWait.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (mFrozenUids.contains(uid)) {
            noteStartUnfreeze(uid);
            mStats.noteUnfreezeTimeout();
            Slog.w(TAG, "unfreeze timeout uid=" + uid);
        }
    }

    /** Package and process names of the frozen uids. Lock free: see {@link #mFrozenNames}. */
    private boolean matchesFrozenName(String name) {
        final String[] names = mFrozenNames;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuild the lock-free name snapshot and wake every waiter. Runs under {@link #mLock},
     * from the freeze listener, so a waiter never sees a name whose freeze is already gone.
     */
    private void refreshFrozenNamesLocked() {
        final ArraySet<String> names = new ArraySet<>();
        for (int i = 0; i < mTracker.size(); i++) {
            final ApmProcessRecord rec = mTracker.valueAt(i);
            if (!rec.frozenByApm) {
                continue;
            }
            final String primary = rec.primaryPackage();
            if (primary != null) {
                names.add(primary);
            }
            names.addAll(rec.packages);
            names.addAll(rec.processNames);
        }
        mFrozenNames = names.isEmpty() ? NO_NAMES : names.toArray(new String[0]);
        synchronized (mUnfreezeWait) {
            mUnfreezeWait.notifyAll();
        }
    }

    public String shellSetEnabled(boolean enabled) {
        return postAndWait(() -> {
            mShellEnabled = enabled;
            if (!mConfig.tryReplace(mConfig.get().withEnabled(enabled))) {
                return "rejected enabled=" + enabled;
            }
            onGatesChanged();
            return "enabled=" + enabled;
        });
    }

    public String shellSetShadow(boolean shadow) {
        return postAndWait(() -> {
            mShellShadow = shadow;
            if (!mConfig.tryReplace(mConfig.get().withShadowMode(shadow))) {
                return "rejected shadow=" + shadow;
            }
            onGatesChanged();
            return "shadowMode=" + shadow;
        });
    }

    /**
     * {@code cmd activity apm autostart}. Writes the two settings and republishes on the
     * spot, so the gates follow before this returns; the settings observer then reads the
     * same values back.
     */
    public String shellAutoStart(@Nullable String sub, @Nullable String arg) {
        if (sub == null) {
            return AutoStartPolicy.USAGE;
        }
        switch (sub) {
            case "list":
                return mAutoStart.list();
            case "add":
                return mAutoStart.add(arg);
            case "remove":
                return mAutoStart.remove(arg);
            case "enable":
                return mAutoStart.setEnabled(true);
            case "disable":
                return mAutoStart.setEnabled(false);
            default:
                return AutoStartPolicy.USAGE;
        }
    }

    public String shellFreeze(String target, int userId) {
        return postAndWait(() -> shellFreezeBody(target, userId, true /* freeze */));
    }

    public String shellUnfreeze(String target, int userId) {
        return postAndWait(() -> shellFreezeBody(target, userId, false /* freeze */));
    }

    @VisibleForTesting
    public void fireDueAlarmsForTest() {
        final long now = mClock.elapsedRealtime();
        final ArrayList<Runnable> due = new ArrayList<>();
        for (int i = mDueAlarms.size() - 1; i >= 0; i--) {
            final DueAlarm alarm = mDueAlarms.get(i);
            if (alarm.at <= now) {
                due.add(alarm.run);
                mDueAlarms.remove(i);
            }
        }
        for (int i = due.size() - 1; i >= 0; i--) {
            due.get(i).run();
        }
    }

    @VisibleForTesting
    public boolean isFrozenForTest(int uid) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec != null && rec.frozenByApm;
        }
    }

    @VisibleForTesting
    @Nullable String getLastFreezeDetailForTest(int uid) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec == null ? null : rec.lastFreezeDetail;
        }
    }

    @VisibleForTesting
    boolean isFreezeDisabledForTest(int uid) {
        synchronized (mLock) {
            final ApmProcessRecord rec = mTracker.get(uid);
            return rec != null && rec.freezeDisabled;
        }
    }

    private void unfreezeForStart(int uid, String reason) {
        runLocked(() -> {
            mFreeze.unfreezeIfOurs(mTracker, mConfig.get(), uid, mClock.elapsedRealtime(), reason);
            noteReleased();
        });
        drainOffLockWork();
    }

    private void unfreezePackageForStart(String packageName) {
        runLocked(() -> {
            mFreeze.unfreezePackage(mTracker, mConfig.get(), packageName,
                    mClock.elapsedRealtime(), "activity");
            noteReleased();
        });
        drainOffLockWork();
    }

    /**
     * The device woke up. Whatever this service froze while the screen was off is released,
     * because the wake path itself walks the activity manager and the components it talks to
     * must not be frozen: a bound service, the input method, the notification shade. Posted
     * from the wakefulness hook, which holds the activity manager lock, and enqueued here so
     * that hook is not waiting on this service.
     */
    public void noteScreenAwake() {
        if (!isEnabled()) {
            return;
        }
        post(() -> runLocked(() -> {
            final ApmConfig config = mConfig.get();
            for (int i = 0; i < mTracker.size(); i++) {
                final ApmProcessRecord rec = mTracker.valueAt(i);
                // Every record, not only the frozen ones: a uid whose freeze alarm has not
                // fired yet is released from it too, and the wake path is about to use it.
                mFreeze.unfreezeIfOurs(mTracker, config, rec.uid, mClock.elapsedRealtime(),
                        "screen-on");
            }
            noteReleased();
        }));
    }

    /** Wake everything waiting for an unfreeze. The counts follow the freeze transitions. */
    private void noteReleased() {
        synchronized (mUnfreezeWait) {
            mUnfreezeWait.notifyAll();
        }
    }

    private String shellFreezeBody(String target, int userId, boolean freeze) {
        // A freeze is queued: it runs once this lock is released, so the shell reports the
        // outcome and not the request. The counts follow the freeze transitions in the listener.
        final String result = callLocked(() -> {
            final ApmConfig config = mConfig.get();
            final long now = mClock.elapsedRealtime();
            return freeze
                    ? mFreeze.shellFreeze(mTracker, config, target, userId, now)
                    : mFreeze.shellUnfreeze(mTracker, config, target, userId, now);
        });
        drainOffLockWork();
        mNet.flushDestroyTrigger();
        if (result != null) {
            return result;
        }
        return callLocked(() -> mFreeze.shellOutcome(mTracker, target, userId));
    }

    private String postAndWait(java.util.function.Supplier<String> body) {
        if (mHandler == null) {
            return body.get();
        }
        final ArrayBlockingQueue<String> done = new ArrayBlockingQueue<>(1);
        mHandler.post(() -> done.offer(body.get()));
        try {
            final String result = done.poll(SHELL_WAIT_MS, TimeUnit.MILLISECONDS);
            return result != null ? result : "apm: posted, handler busy";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "apm: interrupted";
        }
    }

    private void postDelayedExternal(Runnable runnable, long delayMs) {
        // The wrapper drains the platform queue after the body: the delayed bodies (freeze
        // alarms, memory rechecks, watchdog, navigation tick) decide under the lock too.
        final Runnable run = () -> {
            mDelayedWrappers.remove(runnable);
            runnable.run();
            drainOffLockWork();
        };
        mDelayedWrappers.put(runnable, run);
        if (mHandler != null) {
            mHandler.postDelayed(run, delayMs);
            return;
        }
        mDueAlarms.add(new DueAlarm(mClock.elapsedRealtime() + delayMs, run));
    }

    private void removeExternal(Runnable runnable) {
        final Runnable run = mDelayedWrappers.remove(runnable);
        if (run == null) {
            return;
        }
        if (mHandler != null) {
            mHandler.removeCallbacks(run);
        }
        for (int i = mDueAlarms.size() - 1; i >= 0; i--) {
            if (mDueAlarms.get(i).run == run) {
                mDueAlarms.remove(i);
            }
        }
    }

    private static final class DueAlarm {
        final long at;
        final Runnable run;

        DueAlarm(long at, Runnable run) {
            this.at = at;
            this.run = run;
        }
    }
}
