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

import android.util.Slog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * Writes the ported foreground-uid and swappiness nodes. A missing file or a short
 * write is counted and swallowed. Cuttlefish does not have these nodes; the phone kernel does.
 * Callers run on the adaptive process manager thread, do not hold the activity manager
 * lock, and do not hold the service lock either: the service queues these writes and runs
 * them once it has released that lock.
 */
final class KernelKnobWriter {
    private static final String TAG = "Apm";

    private final String mFgUidsPath;
    private final String mSwappinessPath;
    /** Counted from the service's platform work, so the dump reads them without its lock. */
    private volatile int mMissing;
    private volatile int mShort;
    private volatile int mOk;

    KernelKnobWriter() {
        this(ApmConstants.FG_UIDS_PATH, ApmConstants.SWAPPINESS_PATH);
    }

    KernelKnobWriter(String fgUidsPath, String swappinessPath) {
        mFgUidsPath = fgUidsPath;
        mSwappinessPath = swappinessPath;
    }

    void writeFgUids(int[] uids) {
        write(mFgUidsPath, formatFgUids(uids));
    }

    void writeSwappiness(int value) {
        int clamped = value;
        if (clamped < ApmConstants.SWAPPINESS_MIN) {
            clamped = ApmConstants.SWAPPINESS_MIN;
        } else if (clamped > ApmConstants.SWAPPINESS_MAX) {
            clamped = ApmConstants.SWAPPINESS_MAX;
        }
        write(mSwappinessPath, Integer.toString(clamped) + "\n");
    }

    int getMissingCount() {
        return mMissing;
    }

    int getShortCount() {
        return mShort;
    }

    int getOkCount() {
        return mOk;
    }

    /**
     * One unsigned decimal uid per line. An empty list is a single newline so the kernel
     * replaces the table: a zero-length write is rejected by {@code schedinfo.c}.
     * At most {@link ApmConstants#FG_UID_CAP} uids are written.
     */
    static String formatFgUids(int[] uids) {
        if (uids == null || uids.length == 0) {
            return "\n";
        }
        final StringBuilder sb = new StringBuilder();
        int written = 0;
        for (int i = 0; i < uids.length && written < ApmConstants.FG_UID_CAP; i++) {
            if (uids[i] < 0) {
                continue;
            }
            sb.append(uids[i]);
            sb.append('\n');
            written++;
        }
        if (written == 0) {
            return "\n";
        }
        return sb.toString();
    }

    private void write(String path, String payload) {
        if (path == null || path.length() == 0) {
            mMissing++;
            return;
        }
        final File file = new File(path);
        if (!file.exists()) {
            mMissing++;
            return;
        }
        final byte[] bytes = payload.getBytes(StandardCharsets.US_ASCII);
        try (FileOutputStream out = new FileOutputStream(file)) {
            final FileChannel channel = out.getChannel();
            final ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                final int n = channel.write(buf);
                if (n <= 0) {
                    mShort++;
                    return;
                }
            }
            mOk++;
        } catch (IOException e) {
            mShort++;
            Slog.w(TAG, "kernel knob write failed path=" + path);
        }
    }
}
