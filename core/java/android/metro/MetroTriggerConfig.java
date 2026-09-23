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
 * The effective trigger configuration for one user, as seen by the platform after gating. The
 * generation is bumped whenever the configuration changes, which invalidates in flight candidates
 * and feedback from older generations.
 *
 * @hide
 */
public final class MetroTriggerConfig implements Parcelable {
    private final int mUserId;
    private final boolean mEnabled;
    private final boolean mRegionEligible;
    private final String mCity;
    private final int mPackVersion;
    private final int mMatchMode;
    private final long mGeneration;

    public MetroTriggerConfig(int userId, boolean enabled, boolean regionEligible, String city,
            int packVersion, int matchMode, long generation) {
        mUserId = userId;
        mEnabled = enabled;
        mRegionEligible = regionEligible;
        mCity = city;
        mPackVersion = packVersion;
        mMatchMode = matchMode;
        mGeneration = generation;
    }

    private MetroTriggerConfig(Parcel in) {
        mUserId = in.readInt();
        mEnabled = in.readBoolean();
        mRegionEligible = in.readBoolean();
        mCity = in.readString();
        mPackVersion = in.readInt();
        mMatchMode = in.readInt();
        mGeneration = in.readLong();
    }

    public int getUserId() {
        return mUserId;
    }

    /** User switch state, never the platform gating result. */
    public boolean isEnabled() {
        return mEnabled;
    }

    public boolean isRegionEligible() {
        return mRegionEligible;
    }

    public String getCity() {
        return mCity;
    }

    public int getPackVersion() {
        return mPackVersion;
    }

    /** One of the {@code MetroContract.MATCH_MODE_*} values. */
    public int getMatchMode() {
        return mMatchMode;
    }

    public long getGeneration() {
        return mGeneration;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUserId);
        dest.writeBoolean(mEnabled);
        dest.writeBoolean(mRegionEligible);
        dest.writeString(mCity);
        dest.writeInt(mPackVersion);
        dest.writeInt(mMatchMode);
        dest.writeLong(mGeneration);
    }

    @Override
    public String toString() {
        return "MetroTriggerConfig{userId=" + mUserId + ", enabled=" + mEnabled
                + ", regionEligible=" + mRegionEligible + ", city=" + mCity + ", packVersion="
                + mPackVersion + ", matchMode=" + MetroContract.matchModeName(mMatchMode)
                + ", generation=" + mGeneration + "}";
    }

    public static final Creator<MetroTriggerConfig> CREATOR =
            new Creator<MetroTriggerConfig>() {
                @Override
                public MetroTriggerConfig createFromParcel(Parcel in) {
                    return new MetroTriggerConfig(in);
                }

                @Override
                public MetroTriggerConfig[] newArray(int size) {
                    return new MetroTriggerConfig[size];
                }
            };
}
