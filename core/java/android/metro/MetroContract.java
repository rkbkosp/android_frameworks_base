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

/**
 * Constants shared by the passive metro trigger bridge running in {@code system_server}, the
 * preinstalled metro assistant application and the Settings entry point.
 *
 * <p>The platform owns cell observation collection, candidate production and gating; the
 * application owns sessions, ETA, persistence and notifications. Everything the two sides
 * exchange is described by the {@code android.metro} AIDL interfaces and parcelables.
 *
 * @hide
 */
public final class MetroContract {
    private MetroContract() {
    }

    /** Action the assistant application advertises for its session service. */
    public static final String ACTION_BIND = "dev.contextsurface.metro.TRIGGER";

    /** Package of the preinstalled metro assistant application. */
    public static final String PACKAGE = "dev.contextsurface.metro";

    /** Component inside {@link #PACKAGE} implementing {@code IMetroSession}. */
    public static final String SERVICE_CLASS = "dev.contextsurface.metro.MetroSessionService";

    /** Product installed metro data pack consumed by the platform index. */
    public static final String PACK_PATH = "/product/etc/metro/hangzhou-v2.json";

    /** City supported by the platform index. */
    public static final String CITY = "hangzhou";

    /** Data pack schema version understood by the platform. */
    public static final int PACK_VERSION = 2;

    /** Product property marking the device as metro assistant capable. */
    public static final String PROP_SUPPORTED = "ro.metro.assistant_supported";

    /** Matching on the full cell identity: RAT + MCC + MNC + TAC/LAC + 64 bit cell id. */
    public static final int MATCH_MODE_EXACT = 0;

    /**
     * Matching on the cell id alone. The pack for this mode carries no network identity, so no
     * MCC/MNC/TAC is invented and the mode is reported as such in diagnostics. Cell ids are not
     * unique across operators and cities, which is why the mode is confined to the supported city
     * and stays behind the gating, ambiguity and candidate confirmation rules.
     */
    public static final int MATCH_MODE_LOCAL_CID_COMPAT = 1;

    /**
     * Legacy prototype matching mode. Parsed for compatibility; a pack declaring this mode is
     * indexed with {@link #MATCH_MODE_LOCAL_CID_COMPAT} semantics and the declared mode is kept in
     * diagnostics.
     */
    public static final int MATCH_MODE_CELL_ID_ONLY_DEBUG = 2;

    private static final String[] MATCH_MODE_NAMES =
            {"EXACT", "LOCAL_CID_COMPAT", "CELL_ID_ONLY_DEBUG"};

    /** @return a stable name for a {@code MATCH_MODE_*} value. */
    public static String matchModeName(int matchMode) {
        if (matchMode < 0 || matchMode >= MATCH_MODE_NAMES.length) {
            return "UNKNOWN(" + matchMode + ")";
        }
        return MATCH_MODE_NAMES[matchMode];
    }

    /** Observation produced from a {@code CellInfo} list. */
    public static final int SOURCE_CELL_INFO = 1;

    /** Observation produced from a {@code ServiceState}. */
    public static final int SOURCE_SERVICE_STATE = 2;

    /** @return a stable name for a {@code SOURCE_*} value. */
    public static String sourceName(int source) {
        switch (source) {
            case SOURCE_CELL_INFO:
                return "CELL_INFO";
            case SOURCE_SERVICE_STATE:
                return "SERVICE_STATE";
            default:
                return "UNKNOWN(" + source + ")";
        }
    }

    /** The application accepted the candidate and is establishing a session. */
    public static final int FEEDBACK_STATE_ACCEPTED = 1;

    /** The session is running, either a station session or a commute trip. */
    public static final int FEEDBACK_STATE_ACTIVE = 2;

    /** The application rejected the candidate; the platform backs off. */
    public static final int FEEDBACK_STATE_REJECTED = 3;

    /** The session ended, voluntarily or by session timeout. */
    public static final int FEEDBACK_STATE_ENDED = 4;

    /** The application asks for a cooldown on the reported station. */
    public static final int FEEDBACK_STATE_COOLDOWN = 5;

    /** @return a stable name for a {@code FEEDBACK_STATE_*} value. */
    public static String feedbackStateName(int state) {
        switch (state) {
            case FEEDBACK_STATE_ACCEPTED:
                return "ACCEPTED";
            case FEEDBACK_STATE_ACTIVE:
                return "ACTIVE";
            case FEEDBACK_STATE_REJECTED:
                return "REJECTED";
            case FEEDBACK_STATE_ENDED:
                return "ENDED";
            case FEEDBACK_STATE_COOLDOWN:
                return "COOLDOWN";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }

    /** The trigger is off: no observation is consumed and the application is not bound. */
    public static final int SESSION_STATE_DISABLED = 0;

    /** Observing existing telephony events, no polling, no network. */
    public static final int SESSION_STATE_PASSIVE = 1;

    /** A candidate was delivered and is awaiting confirmation. */
    public static final int SESSION_STATE_CANDIDATE = 2;

    /** The application reported an active session. */
    public static final int SESSION_STATE_ACTIVE = 3;

    /** Same-station re-triggering is suppressed until the cooldown expires. */
    public static final int SESSION_STATE_COOLDOWN = 4;

    /** @return a stable name for a {@code SESSION_STATE_*} value. */
    public static String sessionStateName(int state) {
        switch (state) {
            case SESSION_STATE_DISABLED:
                return "DISABLED";
            case SESSION_STATE_PASSIVE:
                return "PASSIVE";
            case SESSION_STATE_CANDIDATE:
                return "CANDIDATE";
            case SESSION_STATE_ACTIVE:
                return "ACTIVE";
            case SESSION_STATE_COOLDOWN:
                return "COOLDOWN";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }

    /** Region override value: derive the region from the device locale. */
    public static final String REGION_OVERRIDE_AUTO = "";

    /** Region override value: explicit China mainland. */
    public static final String REGION_OVERRIDE_CN = "CN";

    /** Region override value: explicit non China mainland, which disables the feature. */
    public static final String REGION_OVERRIDE_OTHER = "OTHER";

    /** Region the feature is limited to. */
    public static final String ELIGIBLE_REGION = "CN";

    /** Canonical time zone of the supported region. */
    public static final String ELIGIBLE_ZONE_SHANGHAI = "Asia/Shanghai";

    /** Canonical time zone of the supported region, western part. */
    public static final String ELIGIBLE_ZONE_URUMQI = "Asia/Urumqi";

    /** Secure setting holding the per user feature switch, {@code 0} (off) or {@code 1} (on). */
    public static final String SECURE_ASSISTANT_ENABLED = "metro_assistant_enabled";

    /** Secure setting holding the per user commute origin station code. */
    public static final String SECURE_COMMUTE_ORIGIN = "metro_commute_origin";

    /** Secure setting holding the per user commute destination station code. */
    public static final String SECURE_COMMUTE_DESTINATION = "metro_commute_destination";

    /** Secure setting holding the lock screen detail switch. */
    public static final String SECURE_LOCKSCREEN_DETAILS = "metro_lockscreen_details";

    /** Secure setting holding the per user region override. */
    public static final String SECURE_REGION_OVERRIDE = "metro_region_override";

    /** {@link #SECURE_ASSISTANT_ENABLED} value: off. */
    public static final int ENABLED_OFF = 0;

    /** {@link #SECURE_ASSISTANT_ENABLED} value: on. */
    public static final int ENABLED_ON = 1;

    /**
     * {@link #SECURE_ASSISTANT_ENABLED} when the setting was never written: the assistant is on
     * by default. The platform service, Settings and the application all read the setting
     * through this constant so that "unset" cannot drift between them.
     */
    public static final int ENABLED_DEFAULT = ENABLED_ON;

    /** {@link #SECURE_LOCKSCREEN_DETAILS} value: off. This is also the default. */
    public static final int LOCKSCREEN_DETAILS_OFF = 0;

    /** {@link #SECURE_LOCKSCREEN_DETAILS} value: on. */
    public static final int LOCKSCREEN_DETAILS_ON = 1;

    /** Hard limit on the bytes read from {@link #PACK_PATH}. */
    public static final long MAX_PACK_FILE_BYTES = 8L * 1024L * 1024L;

    /** Hard limit on indexed cell entries. */
    public static final int MAX_PACK_CELLS = 200_000;

    /** Hard limit on indexed stations. */
    public static final int MAX_PACK_STATIONS = 8_192;

    /** Hard limit on indexed lines. */
    public static final int MAX_PACK_LINES = 1_024;

    /** Hard limit on the station codes reported for a single candidate. */
    public static final int MAX_CANDIDATE_STATIONS = 16;

    /** Hard limit on the observations reported for a single candidate. */
    public static final int MAX_CANDIDATE_SNAPSHOTS = 16;

    /** How long a delivered candidate stays valid while it is unacknowledged. */
    public static final long CANDIDATE_TIMEOUT_MS = 30_000L;

    /** Back off applied to a cell that produced an unacknowledged or rejected candidate. */
    public static final long CANDIDATE_BACKOFF_MS = 120_000L;

    /** Suppression window applied to the station of a finished session. */
    public static final long COOLDOWN_MS = 120_000L;

    /** Upper bound on unacknowledged candidates kept for redelivery. */
    public static final int MAX_PENDING_CANDIDATES = 4;

    /** Upper bound on queued observations between the registry and the trigger service. */
    public static final int MAX_OBSERVATION_QUEUE = 256;

    /** Delay between attempts to bind the assistant application while a candidate is pending. */
    public static final long REBIND_RETRY_MS = 15_000L;

    /** Upper bound on consecutive bind attempts for a single candidate. */
    public static final int MAX_REBIND_ATTEMPTS = 4;
}
