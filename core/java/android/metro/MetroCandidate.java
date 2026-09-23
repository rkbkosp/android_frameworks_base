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

import java.util.Arrays;

/**
 * A station match produced by the platform from observed cells. A candidate is not a trip and must
 * not be shown to the user before the application confirms boarding conditions.
 *
 * @hide
 */
public final class MetroCandidate implements Parcelable {
    private final long mCandidateId;
    private final String mCity;
    private final String[] mStationCodes;
    private final MetroCellSnapshot[] mSnapshots;
    private final double mConfidence;
    private final int mMatchMode;
    private final long mGeneration;

    public MetroCandidate(long candidateId, String city, String[] stationCodes,
            MetroCellSnapshot[] snapshots, double confidence, int matchMode, long generation) {
        mCandidateId = candidateId;
        mCity = city;
        mStationCodes = stationCodes == null ? new String[0] : stationCodes;
        mSnapshots = snapshots == null ? new MetroCellSnapshot[0] : snapshots;
        mConfidence = confidence;
        mMatchMode = matchMode;
        mGeneration = generation;
    }

    private MetroCandidate(Parcel in) {
        mCandidateId = in.readLong();
        mCity = in.readString();
        mStationCodes = in.createStringArray();
        mSnapshots = in.createTypedArray(MetroCellSnapshot.CREATOR);
        mConfidence = in.readDouble();
        mMatchMode = in.readInt();
        mGeneration = in.readLong();
    }

    public long getCandidateId() {
        return mCandidateId;
    }

    public String getCity() {
        return mCity;
    }

    /** Stations compatible with the observations, normally exactly one. */
    public String[] getStationCodes() {
        return mStationCodes;
    }

    /** The observations the match is based on, registered observations first. */
    public MetroCellSnapshot[] getSnapshots() {
        return mSnapshots;
    }

    public double getConfidence() {
        return mConfidence;
    }

    /** One of the {@code MetroContract.MATCH_MODE_*} values. */
    public int getMatchMode() {
        return mMatchMode;
    }

    /** Gate generation the candidate belongs to; stale generations must be dropped. */
    public long getGeneration() {
        return mGeneration;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mCandidateId);
        dest.writeString(mCity);
        dest.writeStringArray(mStationCodes);
        dest.writeTypedArray(mSnapshots, flags);
        dest.writeDouble(mConfidence);
        dest.writeInt(mMatchMode);
        dest.writeLong(mGeneration);
    }

    @Override
    public String toString() {
        return "MetroCandidate{id=" + mCandidateId + ", city=" + mCity + ", stations="
                + Arrays.toString(mStationCodes) + ", snapshots=" + mSnapshots.length
                + ", confidence=" + mConfidence + ", matchMode="
                + MetroContract.matchModeName(mMatchMode) + ", generation=" + mGeneration + "}";
    }

    public static final Creator<MetroCandidate> CREATOR = new Creator<MetroCandidate>() {
        @Override
        public MetroCandidate createFromParcel(Parcel in) {
            return new MetroCandidate(in);
        }

        @Override
        public MetroCandidate[] newArray(int size) {
            return new MetroCandidate[size];
        }
    };
}
