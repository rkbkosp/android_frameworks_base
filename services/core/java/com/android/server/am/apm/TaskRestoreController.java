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

/**
 * Rewrites a force-stop clean type from 2 to 3 so the recent task can stay.
 * The process is still killed. This is not {@code denyKill} and it does not
 * keep the process resident. {@code restart_service_white_list} stays empty
 * until a bundle arrives.
 */
public final class TaskRestoreController {
    public static final int CLEAN_FORCE_STOP = 2;
    public static final int CLEAN_KEEP_TASK = 3;
    public static final int CONFIGURED_CLEAN_TYPE = 3;
    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_DCS_ENABLED = true;
    public static final boolean DEFAULT_WHITELIST_ONLY = false;
    public static final int UPLOAD_THRESHOLD = 400;
    /** Parser fallback when the XML list is absent. */
    public static final int[] EXCEPTED_CLEAR_TYPES = {37, 38};

    private final Object mLock = new Object();
    private boolean mEnabled = DEFAULT_ENABLED;
    private final boolean mDcsEnabled = DEFAULT_DCS_ENABLED;
    private boolean mWhitelistOnly = DEFAULT_WHITELIST_ONLY;
    private int mCleanType = CONFIGURED_CLEAN_TYPE;
    private final int mUploadThreshold = UPLOAD_THRESHOLD;
    private final ArraySet<String> mDisable = new ArraySet<>();
    private final ArraySet<String> mWhitelist = new ArraySet<>();
    private final ArraySet<String> mRestartServices = new ArraySet<>();
    private final ArraySet<String> mQuickRestart = new ArraySet<>();
    private final ArraySet<String> mKillRestartFilter = new ArraySet<>();
    private final ArraySet<String> mProtectSelf = new ArraySet<>();
    private final ArraySet<String> mEmptyProc = new ArraySet<>();
    private final ArraySet<String> mEmptyProcBoot = new ArraySet<>();
    private final ArraySet<String> mMiniProgram = new ArraySet<>();
    private final ArraySet<String> mRuntimeForceStop = new ArraySet<>();

    public TaskRestoreController() {
        mDisable.add("android");
    }

    /**
     * Incoming 2 becomes 3 when the package is eligible. Any other incoming type
     * is unchanged. The caller still kills the process.
     */
    public int rewrite(String packageName, int userId, int uid, int incoming,
            boolean hasLauncherIcon, boolean systemApp) {
        if (incoming != CLEAN_FORCE_STOP) {
            return incoming;
        }
        if (!eligible(packageName, userId, uid, hasLauncherIcon, systemApp)) {
            return incoming;
        }
        return CLEAN_KEEP_TASK;
    }

    /** Always false. Clean type 3 keeps the task, not the process. */
    public boolean keepsProcessResident() {
        return false;
    }

    public void noteRuntimeForceStop(String packageName, int userId) {
        if (packageName == null) {
            return;
        }
        synchronized (mLock) {
            mRuntimeForceStop.add(key(packageName, userId));
        }
    }

    public void clearRuntimeForceStop(String packageName, int userId) {
        if (packageName == null) {
            return;
        }
        synchronized (mLock) {
            mRuntimeForceStop.remove(key(packageName, userId));
        }
    }

    public int restartServiceCount() {
        synchronized (mLock) {
            return mRestartServices.size();
        }
    }

    public int cleanType() {
        return mCleanType;
    }

    public boolean enabled() {
        return mEnabled;
    }

    boolean eligible(String packageName, int userId, int uid, boolean hasLauncherIcon,
            boolean systemApp) {
        if (packageName == null || packageName.length() == 0) {
            return false;
        }
        synchronized (mLock) {
            if (!mEnabled || mCleanType != CLEAN_KEEP_TASK) {
                return false;
            }
            if (mWhitelistOnly && !mWhitelist.contains(packageName)) {
                return false;
            }
            if (!hasLauncherIcon) {
                return false;
            }
            if (mDisable.contains(packageName)) {
                return false;
            }
            if ((uid <= 10000 || systemApp) && !selfDeveloped(packageName)) {
                return false;
            }
            if (mRuntimeForceStop.contains(key(packageName, userId))) {
                return false;
            }
            return true;
        }
    }

    static boolean selfDeveloped(String packageName) {
        return packageName.startsWith("com.oplus.")
                || packageName.startsWith("com.coloros.")
                || packageName.startsWith("com.oppo.")
                || packageName.startsWith("com.nearme.")
                || packageName.startsWith("com.heytap.");
    }

    private static String key(String packageName, int userId) {
        return userId + "/" + packageName;
    }

    public void dump(java.io.PrintWriter pw) {
        synchronized (mLock) {
            pw.print("  taskRestore enabled=");
            pw.print(mEnabled);
            pw.print(" dcs=");
            pw.print(mDcsEnabled);
            pw.print(" whitelistOnly=");
            pw.print(mWhitelistOnly);
            pw.print(" cleanType=");
            pw.print(mCleanType);
            pw.print(" upload=");
            pw.print(mUploadThreshold);
            pw.print(" restartServices=");
            pw.print(mRestartServices.size());
            pw.print(" quickRestart=");
            pw.print(mQuickRestart.size());
            pw.print(" killRestartFilter=");
            pw.print(mKillRestartFilter.size());
            pw.print(" protectSelf=");
            pw.print(mProtectSelf.size());
            pw.print(" emptyProc=");
            pw.print(mEmptyProc.size());
            pw.print(" emptyProcBoot=");
            pw.print(mEmptyProcBoot.size());
            pw.print(" miniProgram=");
            pw.print(mMiniProgram.size());
            pw.print(" exceptedClear=");
            pw.print(EXCEPTED_CLEAR_TYPES[0]);
            pw.print(",");
            pw.println(EXCEPTED_CLEAR_TYPES[1]);
        }
    }
}
