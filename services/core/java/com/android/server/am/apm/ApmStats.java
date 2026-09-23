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

import java.util.ArrayDeque;

/**
 * In-process ring of shadow decisions. No statsd and no intent extras.
 * {@link #noteExecuted()} counts a freeze, unfreeze, kill, defer, or drop that was applied.
 */
final class ApmStats {
    private final ArrayDeque<String> mEvents = new ArrayDeque<>();
    private int mRecorded;
    private int mDropped;
    /**
     * Written from the platform work as well, which runs with the service lock released,
     * so the dumper reads these without that lock.
     */
    private volatile int mExecuted;
    private volatile int mLongLockHolds;
    private volatile long mLastLockHoldMs;
    private volatile int mUnfreezeTimeouts;
    private volatile int mFreezeVerifyFailures;
    private volatile int mStaleOomAdjPasses;

    void record(PolicyDecision decision) {
        mRecorded++;
        if (decision.action != PolicyDecision.Action.NONE && decision.dropped) {
            mDropped++;
        }
        mEvents.addLast(decision.summarize());
        while (mEvents.size() > ApmConstants.EVENT_RING_SIZE) {
            mEvents.removeFirst();
        }
    }

    int recorded() {
        return mRecorded;
    }

    int dropped() {
        return mDropped;
    }

    void noteExecuted() {
        mExecuted++;
    }

    int executed() {
        return mExecuted;
    }

    int eventCount() {
        return mEvents.size();
    }

    void noteLongLockHold(long heldMs) {
        mLongLockHolds++;
        mLastLockHoldMs = heldMs;
    }

    int longLockHolds() {
        return mLongLockHolds;
    }

    long lastLockHoldMs() {
        return mLastLockHoldMs;
    }

    void noteUnfreezeTimeout() {
        mUnfreezeTimeouts++;
    }

    int unfreezeTimeouts() {
        return mUnfreezeTimeouts;
    }

    void noteFreezeVerifyFailure() {
        mFreezeVerifyFailures++;
    }

    int freezeVerifyFailures() {
        return mFreezeVerifyFailures;
    }

    void noteStaleOomAdjPass() {
        mStaleOomAdjPasses++;
    }

    int staleOomAdjPasses() {
        return mStaleOomAdjPasses;
    }

    /** Oldest first. Caller holds the service lock. */
    void dumpRecent(StringBuilder out, int limit) {
        if (mEvents.isEmpty() || limit <= 0) {
            return;
        }
        final int skip = Math.max(0, mEvents.size() - limit);
        int index = 0;
        for (String event : mEvents) {
            if (index++ < skip) {
                continue;
            }
            out.append("    ");
            out.append(event);
            out.append('\n');
        }
    }
}
