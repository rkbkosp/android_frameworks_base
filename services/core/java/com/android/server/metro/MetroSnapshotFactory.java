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

import android.metro.MetroCellSnapshot;
import android.metro.MetroContract;
import android.os.SystemClock;
import android.telephony.AccessNetworkConstants;
import android.telephony.CellIdentity;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityTdscdma;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts telephony objects into {@link MetroCellSnapshot}s.
 *
 * <p>Only identity needed for station matching is copied. Everything is pure in memory work: it is
 * safe to call from the telephony registry while its lock is held, and it never returns a reference
 * to a telephony object.
 */
final class MetroSnapshotFactory {
    private MetroSnapshotFactory() {
    }

    /**
     * Normalizes a modem "not reported" marker to 0. {@code CellInfo.UNAVAILABLE} and negative
     * values both mean the field carries no identity; masking instead would turn a 16 bit
     * {@code UNAVAILABLE} into a plausible looking identity.
     */
    private static long normalized(int value) {
        return (value == CellInfo.UNAVAILABLE || value < 0) ? 0L : value;
    }

    private static long normalized(long value) {
        return (value == CellInfo.UNAVAILABLE_LONG || value < 0L) ? 0L : value;
    }

    static MetroCellSnapshot[] fromCellInfo(int subId, List<CellInfo> cellInfo) {
        if (cellInfo == null || cellInfo.isEmpty()) {
            return null;
        }
        final List<MetroCellSnapshot> snapshots = new ArrayList<>(cellInfo.size());
        // Registered cells first: consumers rely on that order when they prefer the serving cell.
        for (int registeredPass = 1; registeredPass >= 0; registeredPass--) {
            for (int i = 0; i < cellInfo.size(); i++) {
                final CellInfo info = cellInfo.get(i);
                if (info == null || (info.isRegistered() ? 1 : 0) != registeredPass) {
                    continue;
                }
                final MetroCellSnapshot snapshot = fromSingleCellInfo(subId, info);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }
        }
        return snapshots.isEmpty() ? null : snapshots.toArray(new MetroCellSnapshot[0]);
    }

    private static MetroCellSnapshot fromSingleCellInfo(int subId, CellInfo info) {
        final CellIdentity identity = info.getCellIdentity();
        if (identity == null) {
            return null;
        }
        return new MetroCellSnapshot(subId, networkTypeOf(identity), mccOf(identity),
                mncOf(identity), tacOf(identity), cellIdOf(identity), info.isRegistered(),
                elapsedOf(info), MetroContract.SOURCE_CELL_INFO);
    }

    /** {@code ServiceState} carries the serving cell of one phone; only the best entry is kept. */
    static MetroCellSnapshot fromServiceState(int subId, ServiceState state) {
        if (state == null) {
            return null;
        }
        NetworkRegistrationInfo best = null;
        int bestScore = -1;
        final List<NetworkRegistrationInfo> registrations =
                state.getNetworkRegistrationInfoListForTransportType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        for (int i = 0; i < registrations.size(); i++) {
            final NetworkRegistrationInfo candidate = registrations.get(i);
            if (candidate == null || candidate.getCellIdentity() == null) {
                continue;
            }
            final int score = scoreOf(candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null) {
            return null;
        }
        final CellIdentity identity = best.getCellIdentity();
        final boolean registered =
                best.isRegistered() && state.getState() == ServiceState.STATE_IN_SERVICE;
        return new MetroCellSnapshot(subId, best.getAccessNetworkTechnology(), mccOf(identity),
                mncOf(identity), tacOf(identity), cellIdOf(identity), registered,
                SystemClock.elapsedRealtime(), MetroContract.SOURCE_SERVICE_STATE);
    }

    private static int scoreOf(NetworkRegistrationInfo registration) {
        int score = registration.isRegistered() ? 4 : 0;
        final int domain = registration.getDomain();
        if (domain == NetworkRegistrationInfo.DOMAIN_PS) {
            score += 2;
        } else if (domain == NetworkRegistrationInfo.DOMAIN_CS_PS) {
            score += 1;
        }
        return score;
    }

    /** The modem time stamp for {@code CellInfo}, or now when the modem did not provide one. */
    private static long elapsedOf(CellInfo info) {
        final long timestampMillis = info.getTimestampMillis();
        if (timestampMillis <= 0 || timestampMillis == Long.MAX_VALUE / 1000000L) {
            return SystemClock.elapsedRealtime();
        }
        return timestampMillis;
    }

    private static int networkTypeOf(CellIdentity identity) {
        switch (identity.getType()) {
            case CellInfo.TYPE_GSM:
                return TelephonyManager.NETWORK_TYPE_GSM;
            case CellInfo.TYPE_CDMA:
                return TelephonyManager.NETWORK_TYPE_CDMA;
            case CellInfo.TYPE_LTE:
                return TelephonyManager.NETWORK_TYPE_LTE;
            case CellInfo.TYPE_WCDMA:
                return TelephonyManager.NETWORK_TYPE_UMTS;
            case CellInfo.TYPE_TDSCDMA:
                return TelephonyManager.NETWORK_TYPE_TD_SCDMA;
            case CellInfo.TYPE_NR:
                return TelephonyManager.NETWORK_TYPE_NR;
            default:
                return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
    }

    private static int mccOf(CellIdentity identity) {
        return parseIdentityNumber(identity.getMccString());
    }

    private static int mncOf(CellIdentity identity) {
        return parseIdentityNumber(identity.getMncString());
    }

    private static int parseIdentityNumber(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            final int parsed = Integer.parseInt(value);
            return parsed < 0 ? 0 : parsed;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * TAC for LTE/NR, LAC for GSM/WCDMA/TDSCDMA and network id for CDMA. A missing value stays 0
     * and is therefore never matched against a pack entry.
     */
    private static long tacOf(CellIdentity identity) {
        try {
            switch (identity.getType()) {
                case CellInfo.TYPE_LTE:
                    return normalized(((CellIdentityLte) identity).getTac());
                case CellInfo.TYPE_NR:
                    return normalized(((CellIdentityNr) identity).getTac());
                case CellInfo.TYPE_GSM:
                    return normalized(((CellIdentityGsm) identity).getLac());
                case CellInfo.TYPE_WCDMA:
                    return normalized(((CellIdentityWcdma) identity).getLac());
                case CellInfo.TYPE_TDSCDMA:
                    return normalized(((CellIdentityTdscdma) identity).getLac());
                case CellInfo.TYPE_CDMA:
                    return normalized(((CellIdentityCdma) identity).getNetworkId());
                default:
                    return 0L;
            }
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    /**
     * Cell identity kept as a 64 bit value: LTE CI is 28 bit, NR NCI is 36 bit and the GSM, WCDMA,
     * TDSCDMA and CDMA variants are smaller, so no reported identity is truncated here.
     */
    private static long cellIdOf(CellIdentity identity) {
        try {
            switch (identity.getType()) {
                case CellInfo.TYPE_LTE:
                    return normalized(((CellIdentityLte) identity).getCi());
                case CellInfo.TYPE_NR:
                    return normalized(((CellIdentityNr) identity).getNci());
                case CellInfo.TYPE_GSM:
                    return normalized(((CellIdentityGsm) identity).getCid());
                case CellInfo.TYPE_WCDMA:
                    return normalized(((CellIdentityWcdma) identity).getCid());
                case CellInfo.TYPE_TDSCDMA:
                    return normalized(((CellIdentityTdscdma) identity).getCid());
                case CellInfo.TYPE_CDMA:
                    return normalized(((CellIdentityCdma) identity).getBasestationId());
                default:
                    return 0L;
            }
        } catch (ClassCastException e) {
            return 0L;
        }
    }
}
