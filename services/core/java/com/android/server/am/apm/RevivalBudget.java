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

/**
 * MCS revival terms for one package. This is not a boolean. Category 1 does not consume
 * a slot, a daily count, or energy. The global caps live on the revival controller.
 */
public final class RevivalBudget {
    /** Raw energy integer. The donor comment equates {@code 15000000} with 15 mAh/day. */
    public static final long DEFAULT_ENERGY = 15_000_000L;
    public static final int DEFAULT_REVIVAL_CNT = 100;
    public static final int DEFAULT_REVIVAL_ALIVE_CNT = 100;
    public static final int DEFAULT_APP_SLOT = 3;
    /** Slot window, seconds. */
    public static final int DEFAULT_TIME_SLOT_SEC = 1800;
    /** Kill timeout, seconds. A consuming slot is released after this. */
    public static final int DEFAULT_THRESHOLD_SEC = 600;
    public static final int DEFAULT_DURATION_SEC = 30;

    public static final int CATEGORY_NONE = 0;
    /** Does not consume slot, count, or energy. */
    public static final int CATEGORY_EXEMPT = 1;
    /** Commercial. The donor file has no category 2 row. */
    public static final int CATEGORY_COMMERCIAL = 2;

    public final long energy;
    public final int revivalCnt;
    public final int revivalAliveCnt;
    public final int appSlot;
    public final int timeSlotSec;
    public final int thresholdSec;
    public final int durationSec;
    public final int category;

    public RevivalBudget(long energy, int revivalCnt, int revivalAliveCnt, int appSlot,
            int timeSlotSec, int thresholdSec, int durationSec, int category) {
        this.energy = energy;
        this.revivalCnt = revivalCnt;
        this.revivalAliveCnt = revivalAliveCnt;
        this.appSlot = appSlot;
        this.timeSlotSec = timeSlotSec;
        this.thresholdSec = thresholdSec;
        this.durationSec = durationSec;
        this.category = category;
    }

    /** Global caps from {@code sys_res_control_config.xml}. No per-package duration. */
    public static RevivalBudget globalDefaults() {
        return new RevivalBudget(DEFAULT_ENERGY, DEFAULT_REVIVAL_CNT, DEFAULT_REVIVAL_ALIVE_CNT,
                DEFAULT_APP_SLOT, DEFAULT_TIME_SLOT_SEC, DEFAULT_THRESHOLD_SEC,
                DEFAULT_DURATION_SEC, CATEGORY_NONE);
    }

    /** Category 1 grant: 30 seconds, and it does not consume the global budget. */
    public static RevivalBudget categoryOne() {
        return new RevivalBudget(DEFAULT_ENERGY, DEFAULT_REVIVAL_CNT, DEFAULT_REVIVAL_ALIVE_CNT,
                DEFAULT_APP_SLOT, DEFAULT_TIME_SLOT_SEC, DEFAULT_THRESHOLD_SEC,
                DEFAULT_DURATION_SEC, CATEGORY_EXEMPT);
    }

    public boolean consumesSlotCountOrEnergy() {
        return category != CATEGORY_EXEMPT;
    }
}
