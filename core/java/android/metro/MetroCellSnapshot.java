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
 * A single cell observation taken from the telephony stack. It carries no subscriber identity:
 * no IMSI, no phone number and no account is ever copied.
 *
 * @hide
 */
public final class MetroCellSnapshot implements Parcelable {
    private final long mSubId;
    private final int mRat;
    private final int mMcc;
    private final int mMnc;
    private final long mTac;
    private final long mCellId;
    private final boolean mRegistered;
    private final long mObservedElapsedMs;
    private final int mSource;

    public MetroCellSnapshot(long subId, int rat, int mcc, int mnc, long tac, long cellId,
            boolean registered, long observedElapsedMs, int source) {
        mSubId = subId;
        mRat = rat;
        mMcc = mcc;
        mMnc = mnc;
        mTac = tac;
        mCellId = cellId;
        mRegistered = registered;
        mObservedElapsedMs = observedElapsedMs;
        mSource = source;
    }

    private MetroCellSnapshot(Parcel in) {
        mSubId = in.readLong();
        mRat = in.readInt();
        mMcc = in.readInt();
        mMnc = in.readInt();
        mTac = in.readLong();
        mCellId = in.readLong();
        mRegistered = in.readBoolean();
        mObservedElapsedMs = in.readLong();
        mSource = in.readInt();
    }

    public long getSubId() {
        return mSubId;
    }

    public int getRat() {
        return mRat;
    }

    public int getMcc() {
        return mMcc;
    }

    public int getMnc() {
        return mMnc;
    }

    public long getTac() {
        return mTac;
    }

    public long getCellId() {
        return mCellId;
    }

    public boolean isRegistered() {
        return mRegistered;
    }

    /**
     * Observation time on the monotonic clock, derived from the modem time stamp when the modem
     * provides one so that a cached duplicate is not mistaken for a fresh observation.
     */
    public long getObservedElapsedMs() {
        return mObservedElapsedMs;
    }

    /** One of {@link MetroContract#SOURCE_CELL_INFO} or
     * {@link MetroContract#SOURCE_SERVICE_STATE}. */
    public int getSource() {
        return mSource;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mSubId);
        dest.writeInt(mRat);
        dest.writeInt(mMcc);
        dest.writeInt(mMnc);
        dest.writeLong(mTac);
        dest.writeLong(mCellId);
        dest.writeBoolean(mRegistered);
        dest.writeLong(mObservedElapsedMs);
        dest.writeInt(mSource);
    }

    /** Deliberately omits the raw identity so the string is safe to log. */
    @Override
    public String toString() {
        return "MetroCellSnapshot{subId=" + mSubId + ", rat=" + mRat + ", registered=" + mRegistered
                + ", observedElapsedMs=" + mObservedElapsedMs + ", source="
                + MetroContract.sourceName(mSource) + "}";
    }

    public static final Creator<MetroCellSnapshot> CREATOR = new Creator<MetroCellSnapshot>() {
        @Override
        public MetroCellSnapshot createFromParcel(Parcel in) {
            return new MetroCellSnapshot(in);
        }

        @Override
        public MetroCellSnapshot[] newArray(int size) {
            return new MetroCellSnapshot[size];
        }
    };
}
