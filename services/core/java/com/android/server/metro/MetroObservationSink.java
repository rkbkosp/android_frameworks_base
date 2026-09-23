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

import android.telephony.CellInfo;
import android.telephony.ServiceState;

import java.util.List;

/**
 * Non blocking hand off from {@link com.android.server.TelephonyRegistry} to the metro trigger
 * service.
 *
 * <p>The {@code offer} methods are called while the telephony registry holds its record lock. An
 * implementation must only copy the observation into a snapshot and append it to a bounded queue:
 * no binding, no disk access, no JSON, no matching and no lock other than the queue's own atomic
 * operations. Draining and all further work happen on the trigger service worker thread, woken by
 * {@link #scheduleDrain()}, which the registry calls after it has released the lock.
 */
public interface MetroObservationSink {
    /**
     * Offers the cell info list reported for a subscription.
     *
     * @return {@code true} when something was queued and the caller should call
     *         {@link #scheduleDrain()} after releasing its lock
     */
    boolean offerCellInfo(int subId, List<CellInfo> cellInfo);

    /**
     * Offers a service state reported for a subscription.
     *
     * @return {@code true} when something was queued and the caller should call
     *         {@link #scheduleDrain()} after releasing its lock
     */
    boolean offerServiceState(int subId, ServiceState state);

    /** Wakes the worker thread. Must be called outside of the telephony registry lock. */
    void scheduleDrain();
}
