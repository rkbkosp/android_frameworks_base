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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Uid level navigation protection, ported from the model in
 * {@code ColorOS17_PLK110_C61_navigation_keepalive_handoff_20260922}. GNSS usage is the
 * primary fact; it is treated as navigation only while a companion signal holds.
 *
 * <pre>
 * INACTIVE  -> CANDIDATE : gnss active and not denied
 * CANDIDATE -> ACTIVE    : confirmed, and enterDebounceMs since the candidate started
 * CANDIDATE -> INACTIVE  : gnss gone or denied
 * ACTIVE    -> GRACE     : confirmation lost
 * GRACE     -> ACTIVE    : confirmation back
 * GRACE     -> INACTIVE  : exitGraceMs elapsed, or denied
 * </pre>
 *
 * ACTIVE and GRACE are protected. This class never calls out: every mutator reports
 * whether the protected state changed, and the caller applies the action. The caller
 * therefore never holds the service lock while this lock is held, and vice versa only in
 * the read-only direction.
 */
public final class NavigationProtectionController {
    public enum State { INACTIVE, CANDIDATE, ACTIVE, GRACE }

    /** Immutable per-uid view for dumpsys and tests. */
    public static final class Snapshot {
        public final int uid;
        public final State state;
        public final boolean protectedNow;
        public final boolean gnssActive;
        public final boolean locationFgs;
        public final boolean classifierAllowed;
        public final boolean classifierDenied;
        public final long activeSinceMs;
        public final long graceDeadlineMs;
        public final String explanation;

        Snapshot(Record r) {
            uid = r.uid;
            state = r.state;
            protectedNow = isProtected(r.state);
            gnssActive = r.gnssActive;
            locationFgs = r.locationFgs;
            classifierAllowed = r.classifierAllowed;
            classifierDenied = r.classifierDenied;
            activeSinceMs = r.activeSinceMs;
            graceDeadlineMs = r.graceDeadlineMs;
            explanation = r.explanation;
        }
    }

    private static final class Record {
        final int uid;
        State state = State.INACTIVE;
        boolean gnssActive;
        boolean locationFgs;
        boolean classifierAllowed;
        boolean classifierDenied;
        long candidateSinceMs;
        long activeSinceMs;
        long graceDeadlineMs;
        long lastTopMs = Long.MIN_VALUE;
        String explanation = "no evidence";

        Record(int uid) {
            this.uid = uid;
        }
    }

    private NavigationPolicyConfig mConfig;
    private final Map<Integer, Record> mRecords = new HashMap<>();

    public NavigationProtectionController(NavigationPolicyConfig config) {
        mConfig = config;
    }

    /**
     * Swap the policy and re-check every record against it: a shorter window or a new
     * denylist must take effect without waiting for the next signal. Records are kept, so
     * a config refresh from DeviceConfig does not drop live navigation state.
     *
     * @return the uids whose protected state changed, empty when none did
     */
    public synchronized List<Integer> setConfig(NavigationPolicyConfig config, long nowMs) {
        if (config == null || config == mConfig) {
            return Collections.emptyList();
        }
        mConfig = config;
        return sweep(nowMs, "config");
    }

    /**
     * The GNSS provider gained or lost this uid. {@code packageName} only feeds the
     * explanation; the WorkSource may have no name for a uid.
     *
     * @return true when the uid became protected or stopped being protected
     */
    public synchronized boolean onGnssChanged(int uid, @Nullable String packageName,
            boolean active, long nowMs) {
        if (uid < 0) {
            return false;
        }
        final Record r = recordFor(uid);
        if (r.gnssActive == active) {
            return false;
        }
        r.gnssActive = active;
        return reevaluate(r, nowMs, active ? "gnss-started" : "gnss-stopped", packageName);
    }

    /** @return true when the uid became protected or stopped being protected */
    public synchronized boolean onLocationFgsChanged(int uid, boolean active, long nowMs) {
        if (uid < 0) {
            return false;
        }
        final Record r = recordFor(uid);
        if (r.locationFgs == active) {
            return false;
        }
        r.locationFgs = active;
        return reevaluate(r, nowMs, "location-fgs", null);
    }

    /**
     * Top-resumed. Only refreshes the recency window, so it can confirm a classified uid
     * that is using GNSS, and can drop the confirmation again once the window lapses.
     */
    public synchronized boolean noteForeground(int uid, long nowMs) {
        if (uid < 0) {
            return false;
        }
        final Record r = recordFor(uid);
        if (elapsedWithin(nowMs, r.lastTopMs, mConfig.recentTopMs)) {
            return false;
        }
        r.lastTopMs = nowMs;
        return reevaluate(r, nowMs, "foreground", null);
    }

    /**
     * Package classification. A denied package never enters protection. An allowed one
     * still needs GNSS plus the recency window or a location foreground service.
     *
     * @return true when the uid became protected or stopped being protected
     */
    public synchronized boolean setClassifier(int uid, boolean allowed, boolean denied,
            long nowMs) {
        if (uid < 0) {
            return false;
        }
        final Record r = recordFor(uid);
        if (r.classifierAllowed == allowed && r.classifierDenied == denied) {
            return false;
        }
        r.classifierAllowed = allowed;
        r.classifierDenied = denied;
        return reevaluate(r, nowMs, "classifier", null);
    }

    /** Drop a uid that no longer has a process. True when it was protected. */
    public synchronized boolean removeUid(int uid) {
        final Record r = mRecords.remove(uid);
        return r != null && isProtected(r.state);
    }

    /** Deadline sweep. Returns every uid whose protected state changed. */
    public synchronized List<Integer> onTimer(long nowMs) {
        return sweep(nowMs, "timer");
    }

    private List<Integer> sweep(long nowMs, String reason) {
        ArrayList<Integer> changed = null;
        for (final Record r : mRecords.values()) {
            if (!reevaluate(r, nowMs, reason, null)) {
                continue;
            }
            if (changed == null) {
                changed = new ArrayList<>();
            }
            changed.add(r.uid);
        }
        return changed == null ? Collections.emptyList() : changed;
    }

    /**
     * Earliest instant a record can change on its own, or {@link Long#MAX_VALUE} when no
     * record can. Only instants that actually move a state are reported, so a caller that
     * re-arms a timer at the returned instant cannot spin: a candidate that is not
     * confirmed will not advance just because the debounce elapsed.
     *
     * <p>An instant already in the past is still reported, because the sweep that should
     * have run at it may not have.
     */
    public synchronized long nextDeadlineMs(long nowMs) {
        long next = Long.MAX_VALUE;
        for (final Record r : mRecords.values()) {
            switch (r.state) {
                case CANDIDATE:
                    if (confirmed(r, nowMs)) {
                        next = Math.min(next, r.candidateSinceMs + mConfig.enterDebounceMs);
                    }
                    break;
                case ACTIVE:
                    next = Math.min(next, recentTopLapseMs(r));
                    break;
                case GRACE:
                    next = Math.min(next, r.graceDeadlineMs);
                    break;
                default:
                    break;
            }
        }
        return next;
    }

    /**
     * When an allowlist confirmation lapses. A location foreground service confirms on
     * its own, so its record has no deadline here. The instant is one past the window so
     * the sweep that lands on it sees the window closed.
     */
    private long recentTopLapseMs(Record r) {
        if (!r.gnssActive || r.classifierDenied || r.locationFgs || !r.classifierAllowed
                || r.lastTopMs == Long.MIN_VALUE) {
            return Long.MAX_VALUE;
        }
        return r.lastTopMs + mConfig.recentTopMs + 1L;
    }

    public synchronized boolean isProtected(int uid) {
        final Record r = mRecords.get(uid);
        return r != null && isProtected(r.state);
    }

    @Nullable
    public synchronized Snapshot snapshot(int uid) {
        final Record r = mRecords.get(uid);
        return r == null ? null : new Snapshot(r);
    }

    public synchronized List<Snapshot> snapshots() {
        final ArrayList<Snapshot> out = new ArrayList<>(mRecords.size());
        for (final Record r : mRecords.values()) {
            out.add(new Snapshot(r));
        }
        return out;
    }

    public synchronized int protectedCount() {
        int n = 0;
        for (final Record r : mRecords.values()) {
            if (isProtected(r.state)) {
                n++;
            }
        }
        return n;
    }

    public synchronized int size() {
        return mRecords.size();
    }

    private Record recordFor(int uid) {
        Record r = mRecords.get(uid);
        if (r == null) {
            r = new Record(uid);
            mRecords.put(uid, r);
        }
        return r;
    }

    /**
     * The companion signal that turns GNSS use into navigation: a location foreground
     * service on its own, or an allowlisted package that was top inside the recency
     * window. A denied package is never confirmed.
     */
    private boolean confirmed(Record r, long nowMs) {
        if (r.classifierDenied || !r.gnssActive) {
            return false;
        }
        if (r.locationFgs) {
            return true;
        }
        return r.classifierAllowed && elapsedWithin(nowMs, r.lastTopMs, mConfig.recentTopMs);
    }

    /** @return true when the protected state changed */
    private boolean reevaluate(Record r, long nowMs, String reason, @Nullable String packageName) {
        final boolean wasProtected = isProtected(r.state);
        final boolean recentTop = elapsedWithin(nowMs, r.lastTopMs, mConfig.recentTopMs);
        final boolean confirmed = confirmed(r, nowMs);

        switch (r.state) {
            case INACTIVE:
                if (r.gnssActive && !r.classifierDenied) {
                    r.state = State.CANDIDATE;
                    r.candidateSinceMs = nowMs;
                }
                break;
            case CANDIDATE:
                if (!r.gnssActive || r.classifierDenied) {
                    r.state = State.INACTIVE;
                } else if (confirmed
                        && nowMs - r.candidateSinceMs >= mConfig.enterDebounceMs) {
                    r.state = State.ACTIVE;
                    r.activeSinceMs = nowMs;
                }
                break;
            case ACTIVE:
                if (!confirmed) {
                    r.state = State.GRACE;
                    r.graceDeadlineMs = nowMs + mConfig.exitGraceMs;
                }
                break;
            case GRACE:
                if (confirmed) {
                    r.state = State.ACTIVE;
                    r.graceDeadlineMs = 0L;
                } else if (r.classifierDenied || nowMs >= r.graceDeadlineMs) {
                    r.state = State.INACTIVE;
                    r.activeSinceMs = 0L;
                    r.graceDeadlineMs = 0L;
                }
                break;
            default:
                break;
        }
        r.explanation = "reason=" + reason + (packageName != null ? " pkg=" + packageName : "")
                + " gnss=" + r.gnssActive
                + " locationFgs=" + r.locationFgs
                + " allow=" + r.classifierAllowed
                + " deny=" + r.classifierDenied
                + " recentTop=" + recentTop;
        return wasProtected != isProtected(r.state);
    }

    static boolean isProtected(State state) {
        return state == State.ACTIVE || state == State.GRACE;
    }

    private static boolean elapsedWithin(long nowMs, long eventMs, long windowMs) {
        return eventMs != Long.MIN_VALUE && nowMs >= eventMs && nowMs - eventMs <= windowMs;
    }
}
