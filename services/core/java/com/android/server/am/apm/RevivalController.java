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

import android.util.ArraySet;

import java.util.ArrayList;

/**
 * Revival slot machine. Category 1 does not consume a slot, a count, or energy.
 * {@code com.heytap.mcs} is only the allowed caller id. This class does not start it.
 * Shadow mode returns the decision and does not occupy a slot or arm the adj bump.
 */
public final class RevivalController {
    public static final String CALLER_PACKAGE = "com.heytap.mcs";
    public static final String CALLER_ACTION = "com.heytap.mcs.action.LAUNCH_COMPONENT_VIA_MCS";
    public static final String[] SYS_PREFIXES = {
            "com.heytap", "com.coloros", "com.oplus", "com.oppo"
    };
    public static final String[] CATEGORY_ONE = {
            "com.teamtalk.im",
            "com.alibaba.android.rimet",
            "com.ss.android.lark",
            "com.tencent.mobileqq",
            "com.tencent.wemeet.app",
            "com.tencent.wework",
    };

    public static final int REASON_OK = 0;
    public static final int REASON_APP_REVIVAL_SLOT_SIZE_RELEASE = 1;
    public static final int REASON_APP_REVIVAL_SLOT_SIZE_FULL = 2;
    public static final int REASON_APP_REVIVAL_SLOT_CNT_EXCEED = 4;
    public static final int REASON_APP_REVIVAL_SLOT_ENERGY_EXCEED = 8;
    public static final int REASON_APP_REVIVAL_HIGH_TEMP = 16;
    public static final int REASON_APP_REVIVAL_HIGH_CPU_LOAD = 32;
    public static final int REASON_APP_REVIVAL_LOW_MEMORY = 64;
    public static final int REASON_APP_REVIVAL_DEEP_SLEEP = 128;
    public static final int REASON_APP_REVIVAL_POWER_ABNORMAL = 256;
    public static final int REASON_APP_REVIVAL_SUPER_SAVE_MODE = 512;
    public static final int REASON_APP_REVIVAL_GAME_MODE = 1024;
    /** Not a donor code. User force-stop blocks the grant. */
    public static final int REASON_FORCE_STOP = -2;
    public static final int REASON_CALLER = -3;
    public static final int REASON_ACTION = -4;
    public static final int REASON_TARGET = -5;

    /**
     * The XML gives a daily total and a daily count, not a per-grant cost.
     * A consuming grant is charged {@code energy / count} so 100 grants exhaust the day.
     */
    public static final long ENERGY_PER_CONSUMING_GRANT =
            RevivalBudget.DEFAULT_ENERGY / RevivalBudget.DEFAULT_REVIVAL_CNT;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    /** Temporary adj while the grant's duration lasts. Not {@code denyKill}. */
    public static final int BUMP_ADJ = 700;

    private final Object mLock = new Object();
    private final ArraySet<String> mCategoryOne = new ArraySet<>();
    private final ArraySet<String> mForceStop = new ArraySet<>();
    private final ArrayList<Grant> mGrants = new ArrayList<>();
    private int mEnvBlock;
    private int mLastReleaseReason;

    public RevivalController() {
        for (int i = 0; i < CATEGORY_ONE.length; i++) {
            mCategoryOne.add(CATEGORY_ONE[i]);
        }
    }

    public static boolean isCategoryOne(String packageName) {
        if (packageName == null) {
            return false;
        }
        for (int i = 0; i < CATEGORY_ONE.length; i++) {
            if (CATEGORY_ONE[i].equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    public void noteForceStop(String packageName, int userId, boolean stopped) {
        if (packageName == null) {
            return;
        }
        synchronized (mLock) {
            final String key = key(packageName, userId);
            if (stopped) {
                mForceStop.add(key);
            } else {
                mForceStop.remove(key);
            }
        }
    }

    public void setEnvBlock(int reasonBit, boolean on) {
        synchronized (mLock) {
            if (on) {
                mEnvBlock |= reasonBit;
            } else {
                mEnvBlock &= ~reasonBit;
            }
        }
    }

    public Result request(String callerPackage, String action, String targetPackage, int userId,
            long nowElapsed, boolean shadow, boolean forceStopped) {
        synchronized (mLock) {
            releaseExpiredLocked(nowElapsed);
            if (!CALLER_PACKAGE.equals(callerPackage)) {
                return Result.reject(REASON_CALLER);
            }
            if (!CALLER_ACTION.equals(action)) {
                return Result.reject(REASON_ACTION);
            }
            if (targetPackage == null || CALLER_PACKAGE.equals(targetPackage)) {
                return Result.reject(REASON_TARGET);
            }
            if (forceStopped || mForceStop.contains(key(targetPackage, userId))) {
                return Result.reject(REASON_FORCE_STOP);
            }
            if (mEnvBlock != 0) {
                return Result.reject(lowestBit(mEnvBlock));
            }
            final boolean categoryOne = mCategoryOne.contains(targetPackage);
            if (!categoryOne) {
                if (occupiedLocked(nowElapsed) >= RevivalBudget.DEFAULT_APP_SLOT) {
                    return Result.reject(REASON_APP_REVIVAL_SLOT_SIZE_FULL);
                }
                if (countSinceLocked(nowElapsed - DAY_MS) >= RevivalBudget.DEFAULT_REVIVAL_CNT) {
                    return Result.reject(REASON_APP_REVIVAL_SLOT_CNT_EXCEED);
                }
                if (occupiedLocked(nowElapsed) >= RevivalBudget.DEFAULT_REVIVAL_ALIVE_CNT) {
                    return Result.reject(REASON_APP_REVIVAL_SLOT_CNT_EXCEED);
                }
                if (energySinceLocked(nowElapsed - DAY_MS) + ENERGY_PER_CONSUMING_GRANT
                        > RevivalBudget.DEFAULT_ENERGY) {
                    return Result.reject(REASON_APP_REVIVAL_SLOT_ENERGY_EXCEED);
                }
            }
            if (shadow) {
                return Result.computed(categoryOne);
            }
            final long protectUntil = nowElapsed + RevivalBudget.DEFAULT_DURATION_SEC * 1000L;
            if (categoryOne) {
                mGrants.add(new Grant(targetPackage, userId, nowElapsed, protectUntil,
                        0L, false /* consumes */));
                return Result.applied(true /* categoryOne */);
            }
            mGrants.add(new Grant(targetPackage, userId, nowElapsed, protectUntil,
                    ENERGY_PER_CONSUMING_GRANT, true /* consumes */));
            return Result.applied(false /* categoryOne */);
        }
    }

    public int slotsUsed(long nowElapsed) {
        synchronized (mLock) {
            releaseExpiredLocked(nowElapsed);
            return occupiedLocked(nowElapsed);
        }
    }

    public int countUsed(long nowElapsed) {
        synchronized (mLock) {
            return countSinceLocked(nowElapsed - DAY_MS);
        }
    }

    public long energyUsed(long nowElapsed) {
        synchronized (mLock) {
            return energySinceLocked(nowElapsed - DAY_MS);
        }
    }

    public int lastReleaseReason() {
        synchronized (mLock) {
            return mLastReleaseReason;
        }
    }

    /** Adj bump target, or -1. Not applied for a force-stop or after the duration. */
    public int bumpAdj(String packageName, int userId, long nowElapsed, boolean forceStopped) {
        if (forceStopped || packageName == null) {
            return -1;
        }
        synchronized (mLock) {
            if (mForceStop.contains(key(packageName, userId))) {
                return -1;
            }
            for (int i = mGrants.size() - 1; i >= 0; i--) {
                final Grant grant = mGrants.get(i);
                if (grant.userId == userId && packageName.equals(grant.packageName)
                        && nowElapsed < grant.protectUntil) {
                    return BUMP_ADJ;
                }
            }
            return -1;
        }
    }

    private void releaseExpiredLocked(long nowElapsed) {
        final long thresholdMs = RevivalBudget.DEFAULT_THRESHOLD_SEC * 1000L;
        final long windowMs = RevivalBudget.DEFAULT_TIME_SLOT_SEC * 1000L;
        for (int i = mGrants.size() - 1; i >= 0; i--) {
            final Grant grant = mGrants.get(i);
            if (!grant.consumes || grant.released) {
                continue;
            }
            final long age = nowElapsed - grant.at;
            if (age >= thresholdMs || age >= windowMs) {
                grant.released = true;
                mLastReleaseReason = REASON_APP_REVIVAL_SLOT_SIZE_RELEASE;
            }
        }
    }

    private int occupiedLocked(long nowElapsed) {
        final long windowMs = RevivalBudget.DEFAULT_TIME_SLOT_SEC * 1000L;
        int count = 0;
        for (int i = 0; i < mGrants.size(); i++) {
            final Grant grant = mGrants.get(i);
            if (!grant.consumes || grant.released) {
                continue;
            }
            if (nowElapsed - grant.at < windowMs) {
                count++;
            }
        }
        return count;
    }

    private int countSinceLocked(long since) {
        int count = 0;
        for (int i = 0; i < mGrants.size(); i++) {
            final Grant grant = mGrants.get(i);
            if (grant.consumes && grant.at >= since) {
                count++;
            }
        }
        return count;
    }

    private long energySinceLocked(long since) {
        long energy = 0L;
        for (int i = 0; i < mGrants.size(); i++) {
            final Grant grant = mGrants.get(i);
            if (grant.consumes && grant.at >= since) {
                energy += grant.energy;
            }
        }
        return energy;
    }

    private static int lowestBit(int bits) {
        return bits & -bits;
    }

    private static String key(String packageName, int userId) {
        return userId + "/" + packageName;
    }

    private static final class Grant {
        final String packageName;
        final int userId;
        final long at;
        final long protectUntil;
        final long energy;
        final boolean consumes;
        boolean released;

        Grant(String packageName, int userId, long at, long protectUntil, long energy,
                boolean consumes) {
            this.packageName = packageName;
            this.userId = userId;
            this.at = at;
            this.protectUntil = protectUntil;
            this.energy = energy;
            this.consumes = consumes;
            this.released = !consumes;
        }
    }

    public static final class Result {
        public final boolean accepted;
        public final boolean applied;
        public final boolean categoryOne;
        public final int reason;

        private Result(boolean accepted, boolean applied, boolean categoryOne, int reason) {
            this.accepted = accepted;
            this.applied = applied;
            this.categoryOne = categoryOne;
            this.reason = reason;
        }

        static Result reject(int reason) {
            return new Result(false, false, false, reason);
        }

        static Result computed(boolean categoryOne) {
            return new Result(true, false, categoryOne, REASON_OK);
        }

        static Result applied(boolean categoryOne) {
            return new Result(true, true, categoryOne, REASON_OK);
        }
    }
}
