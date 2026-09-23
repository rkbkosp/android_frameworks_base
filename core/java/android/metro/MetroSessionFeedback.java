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
 * Session state reported by the assistant application back to the platform, keyed by candidate and
 * generation so that out of order or stale reports can be dropped.
 *
 * <p>The trailing counters are the application's own diagnostics: the platform performs no network
 * access, so HTTP and ETA freshness can only be observed by the application and reported here.
 * They are optional and describe the reporting application at the time of that report, so a report
 * built with the six argument constructor carries zeros and the platform shows those zeros as the
 * latest known values.
 *
 * @hide
 */
public final class MetroSessionFeedback implements Parcelable {
    private final long mCandidateId;
    private final String mSessionId;
    private final int mState;
    private final String mStationCode;
    private final String mCooldownStationCode;
    private final long mGeneration;
    private final int mHttpRequests;
    private final int mHttpFailures;
    private final int mEtaExpired;

    public MetroSessionFeedback(long candidateId, String sessionId, int state, String stationCode,
            String cooldownStationCode, long generation) {
        this(candidateId, sessionId, state, stationCode, cooldownStationCode, generation, 0, 0, 0);
    }

    public MetroSessionFeedback(long candidateId, String sessionId, int state, String stationCode,
            String cooldownStationCode, long generation, int httpRequests, int httpFailures,
            int etaExpired) {
        mCandidateId = candidateId;
        mSessionId = sessionId;
        mState = state;
        mStationCode = stationCode;
        mCooldownStationCode = cooldownStationCode;
        mGeneration = generation;
        mHttpRequests = httpRequests;
        mHttpFailures = httpFailures;
        mEtaExpired = etaExpired;
    }

    private MetroSessionFeedback(Parcel in) {
        mCandidateId = in.readLong();
        mSessionId = in.readString();
        mState = in.readInt();
        mStationCode = in.readString();
        mCooldownStationCode = in.readString();
        mGeneration = in.readLong();
        mHttpRequests = in.readInt();
        mHttpFailures = in.readInt();
        mEtaExpired = in.readInt();
    }

    /** {@code 0} for feedback that is not tied to a candidate, e.g. a session ending. */
    public long getCandidateId() {
        return mCandidateId;
    }

    /** Application owned session identity; opaque to the platform. */
    public String getSessionId() {
        return mSessionId;
    }

    /** One of the {@code MetroContract.FEEDBACK_STATE_*} values. */
    public int getState() {
        return mState;
    }

    public String getStationCode() {
        return mStationCode;
    }

    /** Station to suppress, used by {@link MetroContract#FEEDBACK_STATE_COOLDOWN}. */
    public String getCooldownStationCode() {
        return mCooldownStationCode;
    }

    public long getGeneration() {
        return mGeneration;
    }

    /** Application HTTP requests since its process started; {@code 0} when not reported. */
    public int getHttpRequests() {
        return mHttpRequests;
    }

    /** Application HTTP failures since its process started; {@code 0} when not reported. */
    public int getHttpFailures() {
        return mHttpFailures;
    }

    /** ETA entries the application discarded as stale; {@code 0} when not reported. */
    public int getEtaExpired() {
        return mEtaExpired;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mCandidateId);
        dest.writeString(mSessionId);
        dest.writeInt(mState);
        dest.writeString(mStationCode);
        dest.writeString(mCooldownStationCode);
        dest.writeLong(mGeneration);
        dest.writeInt(mHttpRequests);
        dest.writeInt(mHttpFailures);
        dest.writeInt(mEtaExpired);
    }

    @Override
    public String toString() {
        return "MetroSessionFeedback{candidateId=" + mCandidateId + ", sessionId=" + mSessionId
                + ", state=" + MetroContract.feedbackStateName(mState) + ", stationCode="
                + mStationCode + ", cooldownStationCode=" + mCooldownStationCode
                + ", generation=" + mGeneration + ", httpRequests=" + mHttpRequests
                + ", httpFailures=" + mHttpFailures + ", etaExpired=" + mEtaExpired + "}";
    }

    public static final Creator<MetroSessionFeedback> CREATOR =
            new Creator<MetroSessionFeedback>() {
                @Override
                public MetroSessionFeedback createFromParcel(Parcel in) {
                    return new MetroSessionFeedback(in);
                }

                @Override
                public MetroSessionFeedback[] newArray(int size) {
                    return new MetroSessionFeedback[size];
                }
            };
}
