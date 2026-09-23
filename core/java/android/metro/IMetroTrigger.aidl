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

import android.metro.MetroPackInfo;
import android.metro.MetroTriggerConfig;

/**
 * Diagnostics interface of the platform trigger service, exposed to Settings through the
 * {@code "metro"} service manager entry. Callers must be the system uid or hold the platform
 * signature; the implementation is also available through {@code dumpsys metro}, which is the
 * primary diagnostic surface.
 *
 * @hide
 */
interface IMetroTrigger {
    /** State of the currently indexed data pack. */
    MetroPackInfo getPackInfo();

    /**
     * Indexed stations, sorted by display name, one entry per station formatted as
     * {@code stationCode|displayName|lineNames} where line names are comma separated.
     */
    List<String> getStations();

    /**
     * Whether the indexed pack contains a rideable path between two stations, transfers included.
     * Connectivity is computed over every line segment of the pack, so two stations that are only
     * linked through an interchange are reported as connected.
     *
     * <p>Returns {@code false} instead of throwing when the pack is not loaded, when either code is
     * not an indexed station, or when both codes are equal (the pack's shortest path semantics
     * treat a zero leg trip as "no route"; Settings rejects equal endpoints separately). Codes are
     * the exact {@code stationCode} values reported by {@link #getStations()}.
     */
    boolean hasRoute(String fromStationCode, String toStationCode);

    /** Effective trigger configuration of the current user. */
    MetroTriggerConfig getConfig();

    /**
     * Human readable multi line diagnostics: gate reasons, state, pack version and counts,
     * observation and candidate counters, binding health. Never contains raw cell identities or
     * personal routes.
     */
    String getDiagnostics();

    /** Reloads the data pack from disk. The previous index stays in place until a load succeeds. */
    void reloadPack();
}
