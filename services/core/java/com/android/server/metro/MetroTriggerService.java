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

package com.android.server.metro;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.metro.IMetroSession;
import android.metro.IMetroTrigger;
import android.metro.IMetroTriggerCallback;
import android.metro.MetroCandidate;
import android.metro.MetroCellSnapshot;
import android.metro.MetroContract;
import android.metro.MetroPackInfo;
import android.metro.MetroSessionFeedback;
import android.metro.MetroTriggerConfig;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.CellInfo;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.TelephonyRegistry;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Passive metro trigger service.
 *
 * <p>Lives in {@code system_server} but does not own a {@code SystemService} lifecycle: it is
 * created by {@code SystemServer} next to the telephony registry, registers itself as the
 * registry's observation sink and exposes {@code IMetroTrigger} through the {@code "metro"} service
 * manager entry so that Settings and {@code dumpsys metro} can inspect it.
 *
 * <p>The design rules implemented here:
 * <ul>
 *   <li>All work happens on a dedicated worker thread. The telephony registry lock only ever copies
 *       and enqueues.
 *   <li>Nothing is observed, loaded, bound or timed when the feature is off; the disabled path is a
 *       single volatile read.
 *   <li>Observation is passive: existing {@code CellInfo} and {@code ServiceState} reports are
 *       consumed, no polling and no extra telephony registration is introduced.
 *   <li>The data pack is indexed with the lightweight JSON parser, bounded in size and entry count,
 *       validated end to end and replaced atomically.
 *   <li>The application is bound explicitly by component and only while a candidate or a session
 *       needs it; binding loss is recovered and unacknowledged candidates are redelivered.
 *   <li>Every delay is measured with {@link SystemClock#elapsedRealtime()}.
 * </ul>
 */
public class MetroTriggerService implements MetroObservationSink {
    private static final String TAG = "MetroTriggerService";
    private static final boolean DBG = false;

    /** Service manager entry backing {@code dumpsys metro} and the Settings diagnostics page. */
    private static final String SERVICE_NAME = "metro";

    private static final int MSG_DRAIN = 1;
    private static final int MSG_LOAD_PACK = 2;
    private static final int MSG_RECHECK_GATING = 3;
    private static final int MSG_CANDIDATE_TIMEOUT = 4;
    private static final int MSG_COOLDOWN_EXPIRED = 5;
    private static final int MSG_FEEDBACK = 6;
    private static final int MSG_REBIND_RETRY = 7;
    private static final int MSG_SYSTEM_RUNNING = 8;
    private static final int MSG_APP_CONNECTED = 9;
    private static final int MSG_APP_DISCONNECTED = 10;
    private static final int MSG_APP_BINDING_DIED = 11;
    private static final int MSG_APP_NULL_BINDING = 12;

    /** Session protection mirroring the application's three hour cap; not a passive budget. */
    private static final long SESSION_GUARD_MS = 3L * 60L * 60L * 1000L;

    private static final double CONFIDENCE_EXACT = 0.88d;
    private static final double CONFIDENCE_CID_COMPAT = 0.70d;
    private static final double CONFIDENCE_AMBIGUOUS = 0.55d;

    private final Context mContext;
    private final HandlerThread mWorkerThread;
    private final Handler mHandler;
    private final UserManager mUserManager;
    private final TelephonyManager mTelephonyManager;
    private final File mPackFile;
    private final IMetroTrigger.Stub mBinder = new TriggerBinder();
    private final IMetroTriggerCallback.Stub mSessionCallback = new SessionCallback();
    private final AppConnection mAppConnection = new AppConnection();
    private final boolean mSupported;

    /** Bounded, lock free observation queue filled by the telephony registry. */
    private final ConcurrentLinkedQueue<Observation> mQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger mQueueDepth = new AtomicInteger();
    private final AtomicLong mDroppedObservations = new AtomicLong();
    private final AtomicLong mInvalidFeedbackCallers = new AtomicLong();
    private final AtomicLong mRouteQueries = new AtomicLong();
    private final AtomicLong mRouteQueriesOk = new AtomicLong();
    private final AtomicLong mRouteQueriesRejected = new AtomicLong();

    /** Published state, read from arbitrary binder threads. */
    private volatile boolean mObserving;
    private volatile MetroTriggerConfig mConfig;
    private volatile MetroPackInfo mPackInfo;
    private volatile MetroPackIndex mPack;
    private volatile long mGeneration;
    private volatile int mAppUid = -1;

    private boolean mSystemRunning;
    private boolean mPackWanted;
    private int mState = MetroContract.SESSION_STATE_DISABLED;
    private String mGateBlockers = "not evaluated";
    private String mGateDetail = "not evaluated";
    private String mEffectiveRegion = "";
    private String mEffectiveZone = "";

    private boolean mBound;
    private IMetroSession mSession;
    private int mAppUserId = UserHandle.USER_NULL;
    private int mRebindAttempts;

    private final ArrayDeque<PendingCandidate> mPending = new ArrayDeque<>();
    private long mCandidateSeq;
    private long mActiveCandidateId;
    private String mActiveStationCode;
    private String mLastStationCode;
    private String mCooldownStation;
    private long mCooldownUntilElapsed;
    private String mBackoffStation;
    private long mBackoffUntilElapsed;

    private long mPackLoads;
    private long mPackFailures;
    private long mObservations;
    private long mCellInfoSnapshots;
    private long mServiceStateSnapshots;
    private long mLastObservationElapsedMs;
    private final Set<Long> mObservedSubIds = new HashSet<>();
    private long mCandidateHits;
    private long mCandidateAccepted;
    private long mCandidateRejected;
    private long mCandidateTimeouts;
    private long mSessionGuardExpiries;
    private long mCooldownSuppressed;
    private long mDuplicateSuppressed;
    private long mBackoffSuppressed;
    private long mForeignNetworkPauses;
    private long mCooldowns;
    private long mGateEvents;
    private long mBinds;
    private long mUnbinds;
    private long mBindDeaths;
    private long mNullBindings;
    private long mDisconnects;
    private long mRebindRetries;
    private long mFeedbackAccepted;
    private long mFeedbackActive;
    private long mFeedbackEnded;
    private long mFeedbackCooldownRequests;
    private long mFeedbackStale;
    private long mFeedbackInvalid;
    private boolean mAppCountersSeen;
    private int mAppHttpRequests;
    private int mAppHttpFailures;
    private int mAppEtaExpired;
    private long mLastFeedbackElapsedMs;

    private final BroadcastReceiver mGateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mGateEvents++;
            mHandler.sendEmptyMessage(MSG_RECHECK_GATING);
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            mHandler.sendEmptyMessage(MSG_RECHECK_GATING);
        }
    };

    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            mHandler.sendEmptyMessage(MSG_APP_BINDING_DIED);
        }
    };

    private static final class Observation {
        final MetroCellSnapshot[] snapshots;
        final long receivedElapsedMs;

        Observation(MetroCellSnapshot[] snapshots, long receivedElapsedMs) {
            this.snapshots = snapshots;
            this.receivedElapsedMs = receivedElapsedMs;
        }
    }

    private static final class PendingCandidate {
        final MetroCandidate candidate;
        final long enqueuedElapsedMs;
        boolean acked;

        PendingCandidate(MetroCandidate candidate, long enqueuedElapsedMs) {
            this.candidate = candidate;
            this.enqueuedElapsedMs = enqueuedElapsedMs;
        }
    }

    private static final class StationMatch {
        final Set<String> stations;
        final double confidence;

        StationMatch(Set<String> stations, double confidence) {
            this.stations = stations;
            this.confidence = confidence;
        }
    }

    public MetroTriggerService(Context context, TelephonyRegistry telephonyRegistry) {
        this(context, telephonyRegistry, new File(MetroContract.PACK_PATH));
    }

    /**
     * Testing seam in the same spirit as {@code TelephonyRegistry.ConfigurationProvider}: the
     * production entry point always indexes {@link MetroContract#PACK_PATH}.
     */
    @VisibleForTesting
    MetroTriggerService(Context context, TelephonyRegistry telephonyRegistry, File packFile) {
        mContext = context;
        mPackFile = packFile;
        mUserManager = context.getSystemService(UserManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mSupported = SystemProperties.getBoolean(MetroContract.PROP_SUPPORTED, false);
        mConfig = new MetroTriggerConfig(UserHandle.USER_SYSTEM, false, false, MetroContract.CITY,
                0, MetroContract.MATCH_MODE_LOCAL_CID_COMPAT, 0L);
        mPackInfo = unavailablePackInfo("not loaded: feature inactive");
        mWorkerThread = new HandlerThread("MetroTrigger");
        mWorkerThread.start();
        mHandler = new Handler(mWorkerThread.getLooper(), this::handleMessageInternal);
        telephonyRegistry.setMetroObservationSink(this);
        ServiceManager.addService(SERVICE_NAME, mBinder);
        registerGateSources();
    }

    /** Called once the system is far enough along that settings and user state can be read. */
    public void systemRunning() {
        registerSettingsObservers();
        mHandler.sendEmptyMessage(MSG_SYSTEM_RUNNING);
    }

    private void registerGateSources() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_USER_STOPPED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(Intent.ACTION_SIM_STATE_CHANGED);
        filter.addAction(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        filter.addAction(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        filter.addAction(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED);
        // Every action above is a protected broadcast, so a non exported receiver still sees the
        // real system senders while other applications cannot inject a gating recheck storm.
        mContext.registerReceiverAsUser(mGateReceiver, UserHandle.ALL, filter, null, mHandler,
                Context.RECEIVER_NOT_EXPORTED);
    }

    /**
     * Settings observers cannot be registered from the constructor: {@code SystemServer}
     * constructs this service (line 1664) before it starts {@code ContentService} (line 1670), so
     * the content resolver still has no binder and {@code registerContentObserver} throws a
     * NullPointerException inside the system process. Registering them from
     * {@link #systemRunning()} instead is late enough for the content service to be published and
     * still early enough to observe every settings change that can gate metro.
     */
    private void registerSettingsObservers() {
        final ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(MetroContract.SECURE_ASSISTANT_ENABLED), false,
                mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(MetroContract.SECURE_REGION_OVERRIDE), false,
                mSettingsObserver, UserHandle.USER_ALL);
    }

    // ---------------------------------------------------------------------------------------
    // Observation sink: called from the telephony registry while it holds its record lock.
    // ---------------------------------------------------------------------------------------

    @Override
    public boolean offerCellInfo(int subId, List<CellInfo> cellInfo) {
        if (!mObserving || cellInfo == null || cellInfo.isEmpty()) {
            return false;
        }
        return enqueue(MetroSnapshotFactory.fromCellInfo(subId, cellInfo));
    }

    @Override
    public boolean offerServiceState(int subId, ServiceState state) {
        if (!mObserving || state == null) {
            return false;
        }
        final MetroCellSnapshot snapshot = MetroSnapshotFactory.fromServiceState(subId, state);
        if (snapshot == null) {
            return false;
        }
        return enqueue(new MetroCellSnapshot[] {snapshot});
    }

    @Override
    public void scheduleDrain() {
        mHandler.sendEmptyMessage(MSG_DRAIN);
    }

    private boolean enqueue(MetroCellSnapshot[] snapshots) {
        if (snapshots == null || snapshots.length == 0) {
            return false;
        }
        if (mQueueDepth.incrementAndGet() > MetroContract.MAX_OBSERVATION_QUEUE) {
            mQueueDepth.decrementAndGet();
            mDroppedObservations.incrementAndGet();
            return false;
        }
        mQueue.add(new Observation(snapshots, SystemClock.elapsedRealtime()));
        return true;
    }

    private void clearQueue() {
        while (mQueue.poll() != null) {
            mQueueDepth.decrementAndGet();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Worker
    // ---------------------------------------------------------------------------------------

    private boolean handleMessageInternal(Message msg) {
        switch (msg.what) {
            case MSG_DRAIN:
                drain();
                return true;
            case MSG_LOAD_PACK:
                loadPack(msg.arg1 == 1);
                return true;
            case MSG_RECHECK_GATING:
                recheckGating();
                return true;
            case MSG_CANDIDATE_TIMEOUT:
                expireCandidates();
                return true;
            case MSG_COOLDOWN_EXPIRED:
                onCooldownExpired();
                return true;
            case MSG_FEEDBACK:
                handleFeedback((MetroSessionFeedback) msg.obj);
                return true;
            case MSG_REBIND_RETRY:
                mRebindAttempts++;
                mRebindRetries++;
                ensureBound();
                return true;
            case MSG_SYSTEM_RUNNING:
                mSystemRunning = true;
                recheckGating();
                return true;
            case MSG_APP_CONNECTED:
                onAppConnected((IBinder) msg.obj);
                return true;
            case MSG_APP_DISCONNECTED:
                mDisconnects++;
                mSession = null;
                return true;
            case MSG_APP_BINDING_DIED:
                onBindingLost();
                return true;
            case MSG_APP_NULL_BINDING:
                mNullBindings++;
                onBindingLost();
                return true;
            default:
                return false;
        }
    }

    private void drain() {
        if (!mObserving) {
            clearQueue();
            return;
        }
        Observation observation = mQueue.poll();
        if (observation == null) {
            return;
        }
        mQueueDepth.decrementAndGet();
        final List<MetroCellSnapshot> batch = new ArrayList<>();
        while (observation != null) {
            mObservations++;
            mLastObservationElapsedMs = observation.receivedElapsedMs;
            for (MetroCellSnapshot snapshot : observation.snapshots) {
                mObservedSubIds.add(snapshot.getSubId());
                if (snapshot.getSource() == MetroContract.SOURCE_CELL_INFO) {
                    mCellInfoSnapshots++;
                } else {
                    mServiceStateSnapshots++;
                }
                batch.add(snapshot);
            }
            observation = mQueue.poll();
            if (observation != null) {
                mQueueDepth.decrementAndGet();
            }
        }
        evaluateBatch(batch);
    }

    /**
     * Loads and indexes the data pack. The pack is only read when the product supports the
     * assistant and either the gating wants it or a caller asked for it explicitly, so a disabled
     * or unsupported device never touches the file.
     */
    private void loadPack(boolean explicit) {
        if (!mSupported) {
            mPackInfo = unavailablePackInfo("not loaded: product unsupported");
            return;
        }
        if (!explicit && !mPackWanted) {
            mPackInfo = unavailablePackInfo("not loaded: feature inactive");
            return;
        }
        MetroPackIndex index = null;
        String diagnostic = null;
        try {
            index = MetroPackIndex.load(mPackFile);
        } catch (MetroPackIndex.PackException | java.io.IOException | RuntimeException e) {
            diagnostic = "unavailable: " + e.getClass().getSimpleName() + ": "
                    + String.valueOf(e.getMessage());
        }
        if (index != null) {
            mPackLoads++;
            mPack = index;
            mPackInfo = new MetroPackInfo(index.city(), index.packVersion(), index.matchMode(),
                    index.stationCount(), index.lineCount(), index.cellCount(),
                    SystemClock.elapsedRealtime(), true, index.describe());
            Slog.i(TAG, "data pack loaded: " + index.describe());
            recheckGating();
            return;
        }
        mPackFailures++;
        // Do not recheck from here: an unreadable pack is retried on the next real gating event
        // instead of by a load loop or a polling timer.
        mPackWanted = false;
        final MetroPackIndex current = mPack;
        if (current == null) {
            mPackInfo = unavailablePackInfo(diagnostic);
        } else {
            // Keep the previous valid index: a failed upgrade must not disable the feature.
            mPackInfo = new MetroPackInfo(current.city(), current.packVersion(),
                    current.matchMode(), current.stationCount(), current.lineCount(),
                    current.cellCount(), mPackInfo.getLoadedAtElapsedMs(), true,
                    "reload failed, keeping previous index; " + diagnostic);
        }
        mGateBlockers = "data pack unavailable: " + diagnostic;
        Slog.w(TAG, "data pack rejected: " + diagnostic);
    }

    private MetroPackInfo unavailablePackInfo(String diagnostic) {
        return new MetroPackInfo(MetroContract.CITY, 0, -1, 0, 0, 0, 0L, false,
                diagnostic == null ? "unavailable" : diagnostic);
    }

    private void recheckGating() {
        if (!mSystemRunning) {
            mGateBlockers = "waiting for boot completion";
            mGateDetail = mGateBlockers;
            return;
        }
        final int userId = ActivityManager.getCurrentUser();
        final ContentResolver resolver = mContext.getContentResolver();
        final int enabledValue = Settings.Secure.getIntForUser(resolver,
                MetroContract.SECURE_ASSISTANT_ENABLED, MetroContract.ENABLED_OFF, userId);
        final boolean userEnabled = enabledValue == MetroContract.ENABLED_ON;
        final String regionOverride = Settings.Secure.getStringForUser(resolver,
                MetroContract.SECURE_REGION_OVERRIDE, userId);
        final String region = TextUtils.isEmpty(regionOverride)
                ? Locale.getDefault().getCountry() : regionOverride.trim();
        final String zone = normalizeChinaZone(TimeZone.getDefault().getID());
        final boolean regionEligible = MetroContract.ELIGIBLE_REGION.equalsIgnoreCase(region)
                && zone != null;
        final boolean locationEnabled = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF, userId)
                != Settings.Secure.LOCATION_MODE_OFF;
        final boolean unlocked = mUserManager != null && mUserManager.isUserUnlocked(userId);
        boolean guest = false;
        if (mUserManager != null) {
            try {
                final UserInfo info = mUserManager.getUserInfo(userId);
                guest = info != null && info.isGuest();
            } catch (RuntimeException e) {
                guest = true;
            }
        }
        final boolean cellular = cellularAvailable();
        final String networkCountry = networkCountry();

        final List<String> blockers = new ArrayList<>();
        if (!mSupported) {
            blockers.add("product does not support the assistant");
        }
        if (!userEnabled) {
            blockers.add("user switch is off");
        }
        if (!regionEligible) {
            blockers.add("region not eligible: " + region + "/"
                    + TimeZone.getDefault().getID());
        }
        if (!locationEnabled) {
            blockers.add("location switch is off");
        }
        if (!unlocked) {
            blockers.add("user " + userId + " is locked");
        }
        if (guest) {
            blockers.add("current user is a guest");
        }
        if (!cellular) {
            blockers.add("no cellular radio (airplane mode or no SIM)");
        }
        // Auxiliary run information: a country that is known and outside CN pauses new sessions.
        // An unknown country is never treated as being abroad, it just skips this blocker.
        if (isOutsideCn(networkCountry)) {
            blockers.add("network country " + networkCountry + " is outside CN");
        }
        // The pack is only indexed once every other condition already allows the feature, so a
        // disabled or out of region device never reads it. A failed load is retried on the next
        // real gating event rather than by a timer.
        if (blockers.isEmpty() && mPack == null) {
            if (!mPackWanted) {
                mPackWanted = true;
                mHandler.removeMessages(MSG_LOAD_PACK);
                mHandler.sendEmptyMessage(MSG_LOAD_PACK);
            }
            blockers.add("data pack not loaded yet");
        }
        final boolean packAvailable = mPack != null;
        final boolean pass = blockers.isEmpty();
        mGateBlockers = pass ? "ok" : String.join("; ", blockers);
        mEffectiveRegion = region;
        mEffectiveZone = zone == null ? "-" : zone;
        mGateDetail = "userId=" + userId + " switch=" + (userEnabled ? "on" : "off") + " region="
                + region + " zone=" + TimeZone.getDefault().getID() + " eligible=" + regionEligible
                + " location=" + (locationEnabled ? "on" : "off") + " unlocked=" + unlocked
                + " guest=" + guest + " cellular=" + cellular + " pack=" + packAvailable
                + " networkCountry=" + (networkCountry == null ? "unknown" : networkCountry);

        final int packVersion = packAvailable ? mPack.packVersion() : 0;
        final int matchMode = packAvailable ? mPack.matchMode()
                : MetroContract.MATCH_MODE_LOCAL_CID_COMPAT;
        final boolean configChanged = mConfig == null
                || mConfig.getUserId() != userId
                || mConfig.isEnabled() != userEnabled
                || mConfig.isRegionEligible() != regionEligible
                || mConfig.getPackVersion() != packVersion
                || mConfig.getMatchMode() != matchMode;
        if (configChanged) {
            mGeneration++;
        }
        mConfig = new MetroTriggerConfig(userId, userEnabled, regionEligible, MetroContract.CITY,
                packVersion, matchMode, mGeneration);

        final boolean wasObserving = mObserving;
        if (pass && !wasObserving) {
            clearQueue();
            setState(MetroContract.SESSION_STATE_PASSIVE);
            mRebindAttempts = 0;
            pushGating(true);
            pushConfig();
        } else if (!pass && wasObserving) {
            stopEverything();
        } else if (pass && configChanged) {
            pushConfig();
        }
        if (DBG) {
            Slog.d(TAG, "gating: " + mGateBlockers + " state="
                    + MetroContract.sessionStateName(mState));
        }
    }

    private boolean cellularAvailable() {
        final ContentResolver resolver = mContext.getContentResolver();
        if (Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0) {
            return false;
        }
        if (mTelephonyManager == null) {
            return false;
        }
        final int modemCount = mTelephonyManager.getActiveModemCount();
        for (int slot = 0; slot < modemCount; slot++) {
            if (mTelephonyManager.getSimState(slot) == TelephonyManager.SIM_STATE_READY) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalizes the many zone ids that share China mainland rules onto the two canonical ids the
     * contract names. Anything else is out of region.
     */
    static String normalizeChinaZone(String zoneId) {
        if (zoneId == null) {
            return null;
        }
        switch (zoneId) {
            case MetroContract.ELIGIBLE_ZONE_SHANGHAI:
            case "Asia/Chongqing":
            case "Asia/Chungking":
            case "Asia/Harbin":
            case "PRC":
            case "CTT":
                return MetroContract.ELIGIBLE_ZONE_SHANGHAI;
            case MetroContract.ELIGIBLE_ZONE_URUMQI:
            case "Asia/Kashgar":
                return MetroContract.ELIGIBLE_ZONE_URUMQI;
            default:
                return null;
        }
    }

    private void setState(int state) {
        if (mState == state) {
            return;
        }
        mState = state;
        mObserving = state != MetroContract.SESSION_STATE_DISABLED;
    }

    /** Gate closed: cancel the candidate, every timer and the binding immediately. */
    private void stopEverything() {
        pushGating(false);
        mHandler.removeMessages(MSG_CANDIDATE_TIMEOUT);
        mHandler.removeMessages(MSG_COOLDOWN_EXPIRED);
        mHandler.removeMessages(MSG_REBIND_RETRY);
        mPending.clear();
        mActiveCandidateId = 0L;
        mActiveStationCode = null;
        mLastStationCode = null;
        mCooldownStation = null;
        mCooldownUntilElapsed = 0L;
        mBackoffStation = null;
        mBackoffUntilElapsed = 0L;
        mRebindAttempts = 0;
        unbindApp();
        clearQueue();
        setState(MetroContract.SESSION_STATE_DISABLED);
        mObserving = false;
        Slog.i(TAG, "gating closed: " + mGateBlockers);
    }

    // ---------------------------------------------------------------------------------------
    // Candidate protocol
    // ---------------------------------------------------------------------------------------

    private void evaluateBatch(List<MetroCellSnapshot> batch) {
        final MetroPackIndex pack = mPack;
        if (pack == null || mState == MetroContract.SESSION_STATE_ACTIVE) {
            return;
        }
        final StationMatch match = match(pack, batch);
        if (match == null) {
            return;
        }
        final String station = match.stations.iterator().next();
        final long now = SystemClock.elapsedRealtime();
        if (mState == MetroContract.SESSION_STATE_COOLDOWN && mCooldownStation != null
                && now < mCooldownUntilElapsed && match.stations.contains(mCooldownStation)) {
            mCooldownSuppressed++;
            return;
        }
        if (mState == MetroContract.SESSION_STATE_CANDIDATE
                && match.stations.contains(mActiveStationCode)) {
            mDuplicateSuppressed++;
            return;
        }
        if (mBackoffStation != null && now < mBackoffUntilElapsed
                && match.stations.contains(mBackoffStation)) {
            mBackoffSuppressed++;
            return;
        }
        if (!networkCountryAllows(batch)) {
            mForeignNetworkPauses++;
            return;
        }
        deliverCandidate(pack, match, batch, station, now);
    }

    private StationMatch match(MetroPackIndex pack, List<MetroCellSnapshot> batch) {
        final Set<String> registered = new HashSet<>();
        final Set<String> any = new HashSet<>();
        for (MetroCellSnapshot snapshot : batch) {
            final String[] stations = pack.lookup(snapshot);
            if (stations == null) {
                continue;
            }
            if (snapshot.isRegistered()) {
                Collections.addAll(registered, stations);
            }
            Collections.addAll(any, stations);
        }
        final Set<String> matches = registered.isEmpty() ? any : registered;
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            final double confidence = pack.matchMode() == MetroContract.MATCH_MODE_EXACT
                    ? CONFIDENCE_EXACT : CONFIDENCE_CID_COMPAT;
            return new StationMatch(matches, confidence);
        }
        // Ambiguous: stay on the previous station instead of jumping between candidates.
        if (mLastStationCode == null || !matches.contains(mLastStationCode)) {
            return null;
        }
        return new StationMatch(Collections.singleton(mLastStationCode), CONFIDENCE_AMBIGUOUS);
    }

    /**
     * The candidate path looks at the subscription that reported the observation, which can differ
     * from the active one the gate consults. Both use {@link #isOutsideCn}.
     */
    private boolean networkCountryAllows(List<MetroCellSnapshot> batch) {
        if (mTelephonyManager == null || batch.isEmpty()) {
            return true;
        }
        return !isOutsideCn(networkCountryOfSlot(slotOfSubId(batch.get(0).getSubId())));
    }

    /** Slot of the subscription the gate should consult: the default data subscription if any. */
    private int activeNetworkSlot() {
        final int dataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (SubscriptionManager.isValidSubscriptionId(dataSubId)) {
            final int slot = slotOfSubId(dataSubId);
            if (slot != SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
                return slot;
            }
        }
        if (mTelephonyManager == null) {
            return SubscriptionManager.INVALID_SIM_SLOT_INDEX;
        }
        try {
            return mTelephonyManager.getSlotIndex();
        } catch (RuntimeException e) {
            return SubscriptionManager.INVALID_SIM_SLOT_INDEX;
        }
    }

    /** Network country of the active subscription, or {@code null} when it is unknown. */
    private String networkCountry() {
        return networkCountryOfSlot(activeNetworkSlot());
    }

    /** @return a lower case ISO country code, or {@code null} when it is not available */
    private String networkCountryOfSlot(int slot) {
        if (mTelephonyManager == null || slot < 0) {
            return null;
        }
        try {
            final String iso = mTelephonyManager.getNetworkCountryIso(slot);
            return TextUtils.isEmpty(iso) ? null : iso.trim().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            // No telephony feature on this device, or the phone process is not reachable.
            return null;
        }
    }

    /** Subscription ids are int sized by construction; the snapshot keeps them as a long. */
    private static int slotOfSubId(long subId) {
        if (subId < Integer.MIN_VALUE || subId > Integer.MAX_VALUE) {
            return SubscriptionManager.INVALID_SIM_SLOT_INDEX;
        }
        return SubscriptionManager.getSlotIndex((int) subId);
    }

    /** A country that is known and outside CN pauses new sessions; unknown never does. */
    private static boolean isOutsideCn(String country) {
        return country != null && !MetroContract.ELIGIBLE_REGION.equalsIgnoreCase(country);
    }

    private void deliverCandidate(MetroPackIndex pack, StationMatch match,
            List<MetroCellSnapshot> batch, String station, long now) {
        String[] stations = match.stations.toArray(new String[0]);
        if (stations.length > MetroContract.MAX_CANDIDATE_STATIONS) {
            Arrays.sort(stations);
            stations = Arrays.copyOf(stations, MetroContract.MAX_CANDIDATE_STATIONS);
        }
        final long candidateId = ++mCandidateSeq;
        final MetroCandidate candidate = new MetroCandidate(candidateId, MetroContract.CITY,
                stations, pruneSnapshots(pack, batch, match.stations), match.confidence,
                pack.matchMode(), mGeneration);
        mCandidateHits++;
        mActiveCandidateId = candidateId;
        mActiveStationCode = station;
        mLastStationCode = station;
        while (mPending.size() >= MetroContract.MAX_PENDING_CANDIDATES) {
            mPending.removeFirst();
        }
        mPending.addLast(new PendingCandidate(candidate, now));
        setState(MetroContract.SESSION_STATE_CANDIDATE);
        scheduleNextExpiry();
        ensureBound();
        deliverPending();
    }

    /** Evidence handed to the application: the matching observations, deduplicated and bounded. */
    private MetroCellSnapshot[] pruneSnapshots(MetroPackIndex pack, List<MetroCellSnapshot> batch,
            Set<String> stations) {
        final List<MetroCellSnapshot> selected = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (MetroCellSnapshot snapshot : batch) {
            final String[] matched = pack.lookup(snapshot);
            if (matched == null) {
                continue;
            }
            boolean wanted = false;
            for (String station : matched) {
                if (stations.contains(station)) {
                    wanted = true;
                    break;
                }
            }
            if (!wanted) {
                continue;
            }
            if (!seen.add(snapshot.getCellId() + "@" + snapshot.getObservedElapsedMs())) {
                continue;
            }
            selected.add(snapshot);
            if (selected.size() >= MetroContract.MAX_CANDIDATE_SNAPSHOTS) {
                break;
            }
        }
        return selected.toArray(new MetroCellSnapshot[0]);
    }

    private void deliverPending() {
        final IMetroSession session = mSession;
        if (session == null) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        boolean rejected = false;
        final Iterator<PendingCandidate> iterator = mPending.iterator();
        while (iterator.hasNext()) {
            final PendingCandidate pending = iterator.next();
            if (pending.acked
                    || now - pending.enqueuedElapsedMs >= MetroContract.CANDIDATE_TIMEOUT_MS) {
                continue;
            }
            final boolean accepted;
            try {
                accepted = session.onCandidate(pending.candidate);
            } catch (RemoteException | RuntimeException e) {
                onSessionFailure("onCandidate", e);
                return;
            }
            if (accepted) {
                pending.acked = true;
                mCandidateAccepted++;
                mActiveCandidateId = pending.candidate.getCandidateId();
                final String station = firstStation(pending.candidate);
                if (station != null) {
                    mActiveStationCode = station;
                }
            } else {
                mCandidateRejected++;
                iterator.remove();
                applyBackoff(firstStation(pending.candidate));
                rejected = true;
            }
        }
        if (rejected && mPending.isEmpty() && mState == MetroContract.SESSION_STATE_CANDIDATE) {
            mActiveCandidateId = 0L;
            mActiveStationCode = null;
            setState(MetroContract.SESSION_STATE_PASSIVE);
            releaseIfIdle();
        }
    }

    private void expireCandidates() {
        final long now = SystemClock.elapsedRealtime();
        boolean changed = false;
        boolean activeExpired = false;
        final Iterator<PendingCandidate> iterator = mPending.iterator();
        while (iterator.hasNext()) {
            final PendingCandidate pending = iterator.next();
            final long age = now - pending.enqueuedElapsedMs;
            if (!pending.acked && age >= MetroContract.CANDIDATE_TIMEOUT_MS) {
                iterator.remove();
                mCandidateTimeouts++;
                applyBackoff(firstStation(pending.candidate));
                changed = true;
            } else if (pending.acked && age >= SESSION_GUARD_MS) {
                iterator.remove();
                mSessionGuardExpiries++;
                if (pending.candidate.getCandidateId() == mActiveCandidateId) {
                    activeExpired = true;
                }
                changed = true;
            }
        }
        if (activeExpired && mState == MetroContract.SESSION_STATE_ACTIVE) {
            // Session protection: the application never reported an end within its own budget.
            enterCooldown(mActiveStationCode);
        }
        if (changed) {
            if (mPending.isEmpty() && mState == MetroContract.SESSION_STATE_CANDIDATE) {
                mActiveCandidateId = 0L;
                mActiveStationCode = null;
                setState(MetroContract.SESSION_STATE_PASSIVE);
            }
            releaseIfIdle();
        }
        scheduleNextExpiry();
    }

    private void scheduleNextExpiry() {
        mHandler.removeMessages(MSG_CANDIDATE_TIMEOUT);
        long earliest = Long.MAX_VALUE;
        final long now = SystemClock.elapsedRealtime();
        for (PendingCandidate pending : mPending) {
            final long deadline = pending.enqueuedElapsedMs
                    + (pending.acked ? SESSION_GUARD_MS : MetroContract.CANDIDATE_TIMEOUT_MS);
            if (deadline < earliest) {
                earliest = deadline;
            }
        }
        if (earliest != Long.MAX_VALUE) {
            mHandler.sendEmptyMessageDelayed(MSG_CANDIDATE_TIMEOUT,
                    Math.max(1L, earliest - now));
        }
    }

    private void applyBackoff(String station) {
        if (station == null) {
            return;
        }
        mBackoffStation = station;
        mBackoffUntilElapsed = SystemClock.elapsedRealtime() + MetroContract.CANDIDATE_BACKOFF_MS;
    }

    private void enterCooldown(String station) {
        mCooldownStation = station;
        mCooldownUntilElapsed = SystemClock.elapsedRealtime() + MetroContract.COOLDOWN_MS;
        mCooldowns++;
        if (station != null) {
            mLastStationCode = station;
        }
        setState(MetroContract.SESSION_STATE_COOLDOWN);
        mHandler.removeMessages(MSG_COOLDOWN_EXPIRED);
        mHandler.sendEmptyMessageDelayed(MSG_COOLDOWN_EXPIRED, MetroContract.COOLDOWN_MS);
    }

    private void onCooldownExpired() {
        if (mState != MetroContract.SESSION_STATE_COOLDOWN) {
            return;
        }
        mCooldownStation = null;
        mCooldownUntilElapsed = 0L;
        setState(mObserving ? MetroContract.SESSION_STATE_PASSIVE
                : MetroContract.SESSION_STATE_DISABLED);
    }

    private static String firstStation(MetroCandidate candidate) {
        final String[] stations = candidate.getStationCodes();
        return stations.length == 0 ? null : stations[0];
    }

    private static String firstNonEmpty(String first, String fallback) {
        return TextUtils.isEmpty(first) ? fallback : first;
    }

    // ---------------------------------------------------------------------------------------
    // Session feedback
    // ---------------------------------------------------------------------------------------

    private void handleFeedback(MetroSessionFeedback feedback) {
        if (feedback == null) {
            mFeedbackInvalid++;
            return;
        }
        if (feedback.getGeneration() != mGeneration) {
            mFeedbackStale++;
            return;
        }
        final long candidateId = feedback.getCandidateId();
        if (candidateId != 0L && !knowsCandidate(candidateId)) {
            mFeedbackStale++;
            return;
        }
        final int state = feedback.getState();
        if (!isKnownFeedbackState(state)) {
            mFeedbackInvalid++;
            return;
        }
        recordAppCounters(feedback);
        switch (state) {
            case MetroContract.FEEDBACK_STATE_ACCEPTED: {
                acknowledge(candidateId);
                mFeedbackAccepted++;
                final String station = firstNonEmpty(feedback.getStationCode(),
                        mActiveStationCode);
                if (station != null) {
                    mActiveStationCode = station;
                    mLastStationCode = station;
                }
                setState(MetroContract.SESSION_STATE_CANDIDATE);
                break;
            }
            case MetroContract.FEEDBACK_STATE_ACTIVE: {
                acknowledge(candidateId);
                mFeedbackActive++;
                final String station = firstNonEmpty(feedback.getStationCode(),
                        mActiveStationCode);
                if (station != null) {
                    mActiveStationCode = station;
                    mLastStationCode = station;
                }
                setState(MetroContract.SESSION_STATE_ACTIVE);
                break;
            }
            case MetroContract.FEEDBACK_STATE_REJECTED: {
                removePending(candidateId);
                mCandidateRejected++;
                applyBackoff(firstNonEmpty(feedback.getStationCode(), mActiveStationCode));
                if (mPending.isEmpty() && mState == MetroContract.SESSION_STATE_CANDIDATE) {
                    mActiveCandidateId = 0L;
                    mActiveStationCode = null;
                    setState(MetroContract.SESSION_STATE_PASSIVE);
                }
                releaseIfIdle();
                break;
            }
            case MetroContract.FEEDBACK_STATE_ENDED: {
                releaseSession(candidateId);
                mFeedbackEnded++;
                enterCooldown(firstNonEmpty(feedback.getStationCode(), mActiveStationCode));
                releaseIfIdle();
                break;
            }
            case MetroContract.FEEDBACK_STATE_COOLDOWN: {
                releaseSession(candidateId);
                mFeedbackCooldownRequests++;
                enterCooldown(firstNonEmpty(feedback.getCooldownStationCode(),
                        feedback.getStationCode()));
                releaseIfIdle();
                break;
            }
            default:
                break;
        }
    }

    private static boolean isKnownFeedbackState(int state) {
        switch (state) {
            case MetroContract.FEEDBACK_STATE_ACCEPTED:
            case MetroContract.FEEDBACK_STATE_ACTIVE:
            case MetroContract.FEEDBACK_STATE_REJECTED:
            case MetroContract.FEEDBACK_STATE_ENDED:
            case MetroContract.FEEDBACK_STATE_COOLDOWN:
                return true;
            default:
                return false;
        }
    }

    /** The application is the only observer of its own network use; the platform stores its report. */
    private void recordAppCounters(MetroSessionFeedback feedback) {
        mAppCountersSeen = true;
        mAppHttpRequests = feedback.getHttpRequests();
        mAppHttpFailures = feedback.getHttpFailures();
        mAppEtaExpired = feedback.getEtaExpired();
        mLastFeedbackElapsedMs = SystemClock.elapsedRealtime();
    }

    private boolean knowsCandidate(long candidateId) {
        if (candidateId == mActiveCandidateId) {
            return true;
        }
        for (PendingCandidate pending : mPending) {
            if (pending.candidate.getCandidateId() == candidateId) {
                return true;
            }
        }
        return false;
    }

    private void acknowledge(long candidateId) {
        for (PendingCandidate pending : mPending) {
            if (pending.candidate.getCandidateId() == candidateId) {
                pending.acked = true;
                return;
            }
        }
    }

    /**
     * A finished session drops its own bookkeeping. The application may report the end without the
     * candidate id, in which case every acknowledged entry belongs to the session that just ended.
     * Unacknowledged candidates are left alone so a pending delivery is not lost.
     */
    private void releaseSession(long candidateId) {
        if (candidateId != 0L) {
            removePending(candidateId);
            return;
        }
        final Iterator<PendingCandidate> iterator = mPending.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().acked) {
                iterator.remove();
            }
        }
        mActiveCandidateId = 0L;
    }

    private void removePending(long candidateId) {
        if (candidateId == 0L) {
            return;
        }
        final Iterator<PendingCandidate> iterator = mPending.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().candidate.getCandidateId() == candidateId) {
                iterator.remove();
            }
        }
        if (mActiveCandidateId == candidateId) {
            mActiveCandidateId = 0L;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Binding
    // ---------------------------------------------------------------------------------------

    private final class AppConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mHandler.obtainMessage(MSG_APP_CONNECTED, service).sendToTarget();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mHandler.sendEmptyMessage(MSG_APP_DISCONNECTED);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            mHandler.sendEmptyMessage(MSG_APP_BINDING_DIED);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            mHandler.sendEmptyMessage(MSG_APP_NULL_BINDING);
        }
    }

    private final class SessionCallback extends IMetroTriggerCallback.Stub {
        @Override
        public void onSessionFeedback(MetroSessionFeedback feedback) {
            final int uid = Binder.getCallingUid();
            if (mAppUid < 0 || uid != mAppUid) {
                mInvalidFeedbackCallers.incrementAndGet();
                return;
            }
            mHandler.obtainMessage(MSG_FEEDBACK, feedback).sendToTarget();
        }
    }

    private void ensureBound() {
        if (mBound || !mObserving) {
            return;
        }
        final int userId = mConfig != null ? mConfig.getUserId() : UserHandle.USER_SYSTEM;
        if (mAppUserId != userId || mAppUid < 0) {
            resolveAppIdentity(userId);
        }
        if (mAppUid < 0) {
            scheduleRebindRetry();
            return;
        }
        final Intent intent = new Intent(MetroContract.ACTION_BIND).setComponent(
                new ComponentName(MetroContract.PACKAGE, MetroContract.SERVICE_CLASS));
        try {
            mBound = mContext.bindServiceAsUser(intent, mAppConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, UserHandle.of(userId));
            if (mBound) {
                mBinds++;
            } else {
                scheduleRebindRetry();
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "unable to bind " + MetroContract.SERVICE_CLASS, e);
            mBound = false;
            scheduleRebindRetry();
        }
    }

    private void resolveAppIdentity(int userId) {
        mAppUserId = userId;
        try {
            mAppUid = mContext.getPackageManager().getPackageUidAsUser(MetroContract.PACKAGE,
                    userId);
        } catch (PackageManager.NameNotFoundException e) {
            mAppUid = -1;
        }
    }

    private void scheduleRebindRetry() {
        if (mRebindAttempts >= MetroContract.MAX_REBIND_ATTEMPTS
                || mHandler.hasMessages(MSG_REBIND_RETRY)) {
            return;
        }
        mHandler.sendEmptyMessageDelayed(MSG_REBIND_RETRY, MetroContract.REBIND_RETRY_MS);
    }

    private void onAppConnected(IBinder service) {
        mRebindAttempts = 0;
        final IMetroSession session = IMetroSession.Stub.asInterface(service);
        mSession = session;
        try {
            service.linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "assistant died before linkToDeath", e);
        }
        pushConfig();
        // A fresh binding must learn the current gating state, not only the configuration: the
        // gate may have opened long before the application existed.
        pushGating(mObserving);
        deliverPending();
    }

    private void onBindingLost() {
        mBindDeaths++;
        mSession = null;
        unbindApp();
        if (needsApplication()) {
            scheduleRebindRetry();
        }
    }

    private void onSessionFailure(String where, Exception e) {
        Slog.w(TAG, "assistant session failed during " + where, e);
        mSession = null;
        unbindApp();
        if (needsApplication()) {
            scheduleRebindRetry();
        }
    }

    private boolean needsApplication() {
        return mObserving && (mState == MetroContract.SESSION_STATE_CANDIDATE
                || mState == MetroContract.SESSION_STATE_ACTIVE || !mPending.isEmpty());
    }

    private void releaseIfIdle() {
        if (mState == MetroContract.SESSION_STATE_CANDIDATE
                || mState == MetroContract.SESSION_STATE_ACTIVE || !mPending.isEmpty()) {
            return;
        }
        unbindApp();
    }

    private void unbindApp() {
        if (!mBound) {
            return;
        }
        mBound = false;
        mSession = null;
        mUnbinds++;
        try {
            mContext.unbindService(mAppConnection);
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "unbind of a service that was never bound", e);
        }
    }

    private void pushConfig() {
        final IMetroSession session = mSession;
        if (session == null) {
            return;
        }
        try {
            session.onTriggerConfig(mConfig, mSessionCallback);
        } catch (RemoteException | RuntimeException e) {
            onSessionFailure("onTriggerConfig", e);
        }
    }

    private void pushGating(boolean eligible) {
        final IMetroSession session = mSession;
        if (session == null) {
            return;
        }
        try {
            session.onGatingChanged(eligible, mConfig);
        } catch (RemoteException | RuntimeException e) {
            onSessionFailure("onGatingChanged", e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------------------

    private final class TriggerBinder extends IMetroTrigger.Stub {
        @Override
        public MetroPackInfo getPackInfo() {
            enforceDiagnosticCaller("getPackInfo");
            return mPackInfo;
        }

        @Override
        public List<String> getStations() {
            enforceDiagnosticCaller("getStations");
            final MetroPackIndex pack = mPack;
            return pack == null ? Collections.emptyList() : pack.stationList();
        }

        @Override
        public boolean hasRoute(String fromStationCode, String toStationCode) {
            enforceDiagnosticCaller("hasRoute");
            mRouteQueries.incrementAndGet();
            final MetroPackIndex pack = mPack;
            // Called straight from a binder thread: the index is immutable and never blocks.
            final boolean reachable = pack != null
                    && pack.hasRoute(fromStationCode, toStationCode);
            if (reachable) {
                mRouteQueriesOk.incrementAndGet();
            } else {
                mRouteQueriesRejected.incrementAndGet();
            }
            return reachable;
        }

        @Override
        public MetroTriggerConfig getConfig() {
            enforceDiagnosticCaller("getConfig");
            return mConfig;
        }

        @Override
        public String getDiagnostics() {
            enforceDiagnosticCaller("getDiagnostics");
            return buildDiagnostics();
        }

        @Override
        public void reloadPack() {
            enforceDiagnosticCaller("reloadPack");
            mHandler.obtainMessage(MSG_LOAD_PACK, 1, 0).sendToTarget();
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) {
                return;
            }
            pw.print(buildDiagnostics());
            pw.flush();
        }
    }

    /**
     * Settings runs as {@code android.uid.system} and shell owns {@code dumpsys}; anything else,
     * including any ordinary application, is rejected.
     */
    private void enforceDiagnosticCaller(String method) {
        final int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID || uid == Process.ROOT_UID || uid == Process.SHELL_UID) {
            return;
        }
        if (mContext.getPackageManager().checkSignatures(uid, Process.SYSTEM_UID)
                == PackageManager.SIGNATURE_MATCH) {
            return;
        }
        throw new SecurityException(method + " requires the system uid or the platform signature");
    }

    /**
     * Diagnostics intentionally report counters and states only: no raw cell identity, no
     * subscriber identity and no personal commute route is ever printed.
     */
    private String buildDiagnostics() {
        final MetroPackIndex pack = mPack;
        final MetroTriggerConfig config = mConfig;
        final StringBuilder sb = new StringBuilder(1024);
        sb.append("MetroTriggerService\n");
        sb.append("  supported_property=").append(mSupported).append(" (")
                .append(MetroContract.PROP_SUPPORTED).append(")\n");
        sb.append("  state=").append(MetroContract.sessionStateName(mState))
                .append(" observing=").append(mObserving).append('\n');
        sb.append("  gate=").append(mGateBlockers).append('\n');
        sb.append("  gate_detail=").append(mGateDetail).append('\n');
        sb.append("  region=").append(mEffectiveRegion).append(" normalized_zone=")
                .append(mEffectiveZone).append('\n');
        sb.append("  generation=").append(mGeneration).append('\n');
        sb.append("  config=").append(config == null ? "none" : config.toString()).append('\n');
        sb.append("  pack=").append(mPackInfo.getDiagnostic()).append(" available=")
                .append(mPackInfo.isAvailable()).append(" loads=").append(mPackLoads)
                .append(" failures=").append(mPackFailures).append(" loaded_age_ms=")
                .append(pack == null || !mPackInfo.isAvailable() ? -1
                        : SystemClock.elapsedRealtime() - mPackInfo.getLoadedAtElapsedMs())
                .append('\n');
        sb.append("  observations=").append(mObservations).append(" cell_info=")
                .append(mCellInfoSnapshots).append(" service_state=").append(mServiceStateSnapshots)
                .append(" dropped=").append(mDroppedObservations.get()).append(" queued=")
                .append(mQueueDepth.get()).append(" sub_ids=").append(mObservedSubIds.size())
                .append(" last_age_ms=").append(mLastObservationElapsedMs == 0 ? -1
                        : SystemClock.elapsedRealtime() - mLastObservationElapsedMs)
                .append('\n');
        sb.append("  candidates= hits=").append(mCandidateHits).append(" accepted=")
                .append(mCandidateAccepted).append(" rejected=").append(mCandidateRejected)
                .append(" timeouts=").append(mCandidateTimeouts).append(" session_guard=")
                .append(mSessionGuardExpiries).append(" pending=").append(mPending.size())
                .append(" cooldown_suppressed=").append(mCooldownSuppressed)
                .append(" duplicate_suppressed=").append(mDuplicateSuppressed)
                .append(" backoff_suppressed=").append(mBackoffSuppressed)
                .append(" foreign_network_pauses=").append(mForeignNetworkPauses).append('\n');
        sb.append("  binding= bound=").append(mBound).append(" connected=")
                .append(mSession != null).append(" binds=").append(mBinds).append(" unbinds=")
                .append(mUnbinds).append(" deaths=").append(mBindDeaths).append(" disconnects=")
                .append(mDisconnects).append(" null_bindings=").append(mNullBindings)
                .append(" rebind_retries=").append(mRebindRetries).append('\n');
        sb.append("  session= candidate_id=").append(mActiveCandidateId).append(" cooldown=")
                .append(mCooldownStation == null ? "none" : "active").append(" cooldowns=")
                .append(mCooldowns).append(" backoff=").append(mBackoffStation == null ? "none"
                        : "active").append('\n');
        sb.append("  feedback= accepted=").append(mFeedbackAccepted).append(" active=")
                .append(mFeedbackActive).append(" ended=").append(mFeedbackEnded)
                .append(" cooldown_requests=").append(mFeedbackCooldownRequests).append(" stale=")
                .append(mFeedbackStale).append(" invalid=").append(mFeedbackInvalid)
                .append(" rejected_callers=").append(mInvalidFeedbackCallers.get()).append('\n');
        sb.append("  gate_events=").append(mGateEvents).append('\n');
        sb.append("  route_queries= total=").append(mRouteQueries.get()).append(" ok=")
                .append(mRouteQueriesOk.get()).append(" rejected=")
                .append(mRouteQueriesRejected.get()).append('\n');
        if (mAppCountersSeen) {
            sb.append("  app_reported_counters= http_requests=").append(mAppHttpRequests)
                    .append(" http_failures=").append(mAppHttpFailures)
                    .append(" eta_expired=").append(mAppEtaExpired).append(" age_ms=")
                    .append(SystemClock.elapsedRealtime() - mLastFeedbackElapsedMs)
                    .append(" (reported by ").append(MetroContract.PACKAGE).append(")\n");
        } else {
            sb.append("  app_reported_counters= http_requests=unavailable http_failures=unavailable"
                    + " eta_expired=unavailable (no session feedback received yet from ")
                    .append(MetroContract.PACKAGE).append(")\n");
        }
        return sb.toString();
    }
}
