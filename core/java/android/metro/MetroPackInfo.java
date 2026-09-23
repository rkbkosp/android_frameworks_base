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

package android.metro;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * State of the data pack indexed by the platform trigger service: what was loaded, when, and why
 * it is unavailable when it is.
 *
 * @hide
 */
public final class MetroPackInfo implements Parcelable {
    private final String mCity;
    private final int mPackVersion;
    private final int mMatchMode;
    private final int mStationCount;
    private final int mLineCount;
    private final int mCellCount;
    private final long mLoadedAtElapsedMs;
    private final boolean mAvailable;
    private final String mDiagnostic;

    public MetroPackInfo(String city, int packVersion, int matchMode, int stationCount,
            int lineCount, int cellCount, long loadedAtElapsedMs, boolean available,
            String diagnostic) {
        mCity = city;
        mPackVersion = packVersion;
        mMatchMode = matchMode;
        mStationCount = stationCount;
        mLineCount = lineCount;
        mCellCount = cellCount;
        mLoadedAtElapsedMs = loadedAtElapsedMs;
        mAvailable = available;
        mDiagnostic = diagnostic;
    }

    private MetroPackInfo(Parcel in) {
        mCity = in.readString();
        mPackVersion = in.readInt();
        mMatchMode = in.readInt();
        mStationCount = in.readInt();
        mLineCount = in.readInt();
        mCellCount = in.readInt();
        mLoadedAtElapsedMs = in.readLong();
        mAvailable = in.readBoolean();
        mDiagnostic = in.readString();
    }

    public String getCity() {
        return mCity;
    }

    public int getPackVersion() {
        return mPackVersion;
    }

    /**
     * Effective matching mode of the index. A pack declaring the legacy prototype mode is indexed
     * with {@link MetroContract#MATCH_MODE_LOCAL_CID_COMPAT}; the declared mode is part of
     * {@link #getDiagnostic()}.
     */
    public int getMatchMode() {
        return mMatchMode;
    }

    public int getStationCount() {
        return mStationCount;
    }

    public int getLineCount() {
        return mLineCount;
    }

    public int getCellCount() {
        return mCellCount;
    }

    /** Monotonic load time, {@code 0} when nothing was ever loaded successfully. */
    public long getLoadedAtElapsedMs() {
        return mLoadedAtElapsedMs;
    }

    public boolean isAvailable() {
        return mAvailable;
    }

    /** Human readable reason, never containing raw cell identities or user routes. */
    public String getDiagnostic() {
        return mDiagnostic;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mCity);
        dest.writeInt(mPackVersion);
        dest.writeInt(mMatchMode);
        dest.writeInt(mStationCount);
        dest.writeInt(mLineCount);
        dest.writeInt(mCellCount);
        dest.writeLong(mLoadedAtElapsedMs);
        dest.writeBoolean(mAvailable);
        dest.writeString(mDiagnostic);
    }

    @Override
    public String toString() {
        return "MetroPackInfo{city=" + mCity + ", packVersion=" + mPackVersion + ", matchMode="
                + MetroContract.matchModeName(mMatchMode) + ", stations=" + mStationCount
                + ", lines=" + mLineCount + ", cells=" + mCellCount + ", available=" + mAvailable
                + ", diagnostic=" + mDiagnostic + "}";
    }

    public static final Creator<MetroPackInfo> CREATOR = new Creator<MetroPackInfo>() {
        @Override
        public MetroPackInfo createFromParcel(Parcel in) {
            return new MetroPackInfo(in);
        }

        @Override
        public MetroPackInfo[] newArray(int size) {
            return new MetroPackInfo[size];
        }
    };
}
