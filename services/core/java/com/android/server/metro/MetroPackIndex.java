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
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, validated lookup index of the metro data pack.
 *
 * <p>The pack is decoded with the lightweight JSON parser because {@code system_server} must not
 * carry the application's serialization stack. A pack is only published after every reference has
 * been validated, so an index is always self consistent and reloads are atomic.
 *
 * <p>Two matching modes are supported. {@code EXACT} keys on the full identity reported by the
 * modem. {@code LOCAL_CID_COMPAT} keys on the cell id alone: the pack used by this mode carries no
 * network identity, and none is invented here. The legacy prototype mode declares itself as
 * {@code CELL_ID_ONLY_DEBUG}; it is parsed and indexed with {@code LOCAL_CID_COMPAT} semantics
 * while the declared mode stays visible in diagnostics.
 */
final class MetroPackIndex {
    /** Rejection of a pack that is missing, malformed or inconsistent. */
    static final class PackException extends Exception {
        PackException(String message) {
            super(message);
        }
    }

    private static final Pattern STATION_CODE = Pattern.compile("^[a-z][a-z0-9_-]{0,31}$");
    private static final String DECLARED_EXACT = "EXACT";
    private static final String DECLARED_LOCAL_CID_COMPAT = "LOCAL_CID_COMPAT";
    private static final String DECLARED_DEBUG = "CELL_ID_ONLY_DEBUG";
    private static final Set<String> EXACT_RATS =
            new LinkedHashSet<>(Arrays.asList("LTE", "NR", "WCDMA"));

    private final String mCity;
    private final int mPackVersion;
    private final int mMatchMode;
    private final String mDeclaredMatchMode;
    private final Map<String, String[]> mCellIndex;
    /** Station code to connected component id; two equal ids mean a rideable path exists. */
    private final Map<String, Integer> mComponent;
    private final List<String> mStationList;
    private final int mCellCount;
    private final int mLineCount;
    private final int mStationCount;
    private final int mAmbiguousKeys;

    private MetroPackIndex(String city, int packVersion, int matchMode, String declaredMatchMode,
            Map<String, String[]> cellIndex, Map<String, Integer> component,
            Map<String, String> displayNames, Map<String, String> lineNames, int cellCount,
            int lineCount, int ambiguousKeys) {
        mCity = city;
        mPackVersion = packVersion;
        mMatchMode = matchMode;
        mDeclaredMatchMode = declaredMatchMode;
        mCellIndex = cellIndex;
        mComponent = component;
        mCellCount = cellCount;
        mLineCount = lineCount;
        mStationCount = displayNames.size();
        mAmbiguousKeys = ambiguousKeys;
        final List<String> sorted = new ArrayList<>(displayNames.keySet());
        sorted.sort((left, right) -> {
            final int byName = displayNames.get(left).compareTo(displayNames.get(right));
            return byName != 0 ? byName : left.compareTo(right);
        });
        final List<String> entries = new ArrayList<>(sorted.size());
        for (String code : sorted) {
            entries.add(code + "|" + displayNames.get(code) + "|" + lineNames.get(code));
        }
        mStationList = Collections.unmodifiableList(entries);
    }

    String city() {
        return mCity;
    }

    int packVersion() {
        return mPackVersion;
    }

    int matchMode() {
        return mMatchMode;
    }

    int stationCount() {
        return mStationCount;
    }

    int lineCount() {
        return mLineCount;
    }

    int cellCount() {
        return mCellCount;
    }

    /** {@code stationCode|displayName|lineNames}, sorted by display name. */
    List<String> stationList() {
        return mStationList;
    }

    String describe() {
        return "city=" + mCity + " packVersion=" + mPackVersion + " declared=" + mDeclaredMatchMode
                + " effective=" + MetroContract.matchModeName(mMatchMode) + " stations="
                + mStationCount + " lines=" + mLineCount + " cells=" + mCellCount
                + " components=" + componentCount() + " ambiguousKeys=" + mAmbiguousKeys;
    }

    /**
     * @return the stations matching one observation, or {@code null} when nothing matches or the
     *         observation cannot be matched in the active mode
     */
    String[] lookup(MetroCellSnapshot snapshot) {
        final String key = keyOf(snapshot);
        return key == null ? null : mCellIndex.get(key);
    }

    /**
     * Whether this index contains a rideable path between two stations. Connectivity is computed
     * over every line segment at load time, so interchanges are honoured: two stations only linked
     * through a transfer are connected. Unknown codes and equal codes are not connected, matching
     * the pack's shortest path semantics.
     *
     * <p>The index is immutable, so this is safe to call from any thread, including directly from a
     * binder thread.
     */
    boolean hasRoute(String fromStationCode, String toStationCode) {
        if (fromStationCode == null || toStationCode == null
                || fromStationCode.equals(toStationCode)) {
            return false;
        }
        final Integer from = mComponent.get(fromStationCode);
        final Integer to = mComponent.get(toStationCode);
        return from != null && from.equals(to);
    }

    /** Number of disconnected groups in this index; one means the whole pack is rideable. */
    /** Number of disconnected groups: more than one means some station pairs are not rideable. */
    int componentCount() {
        return new HashSet<>(mComponent.values()).size();
    }

    private String keyOf(MetroCellSnapshot snapshot) {
        if (mMatchMode == MetroContract.MATCH_MODE_EXACT) {
            final String rat = ratName(snapshot.getRat());
            if (rat == null) {
                return null;
            }
            return exactKey(rat, snapshot.getMcc(), snapshot.getMnc(), snapshot.getTac(),
                    snapshot.getCellId());
        }
        if (snapshot.getCellId() <= 0) {
            return null;
        }
        return cidKey(snapshot.getCellId());
    }

    private static String cidKey(long cellId) {
        return "c" + cellId;
    }

    private static String exactKey(String rat, int mcc, int mnc, long tac, long cellId) {
        return "e" + rat + '|' + mcc + '|' + mnc + '|' + tac + '|' + cellId;
    }

    /** Maps a {@code TelephonyManager.NETWORK_TYPE_*} value onto the pack's RAT naming. */
    static String ratName(int networkType) {
        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "LTE";
            case TelephonyManager.NETWORK_TYPE_NR:
                return "NR";
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "WCDMA";
            default:
                return null;
        }
    }

    static MetroPackIndex load(File file) throws IOException, PackException {
        final String json = readBounded(file);
        final JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new PackException("payload is not valid JSON: " + e.getMessage());
        }

        final String city = root.optString("city", "");
        if (!MetroContract.CITY.equals(city)) {
            throw new PackException("unsupported city '" + city + "'");
        }
        final int packVersion = root.optInt("packVersion", -1);
        if (packVersion != MetroContract.PACK_VERSION) {
            throw new PackException("unsupported packVersion " + packVersion);
        }
        final String declared = root.optString("cellMatchMode", DECLARED_EXACT).trim();
        final int matchMode;
        switch (declared) {
            case DECLARED_EXACT:
                matchMode = MetroContract.MATCH_MODE_EXACT;
                break;
            case DECLARED_LOCAL_CID_COMPAT:
                matchMode = MetroContract.MATCH_MODE_LOCAL_CID_COMPAT;
                break;
            case DECLARED_DEBUG:
                // The prototype asset carries no network identity, so it is served by the honest
                // cell id only mode instead of pretending to be an exact identity pack.
                matchMode = MetroContract.MATCH_MODE_LOCAL_CID_COMPAT;
                break;
            default:
                throw new PackException("unsupported cellMatchMode '" + declared + "'");
        }

        final JSONArray lines = root.optJSONArray("lines");
        if (lines == null || lines.length() == 0) {
            throw new PackException("pack has no lines");
        }
        if (lines.length() > MetroContract.MAX_PACK_LINES) {
            throw new PackException("too many lines: " + lines.length());
        }
        final JSONArray stations = root.optJSONArray("stations");
        if (stations == null || stations.length() == 0) {
            throw new PackException("pack has no stations");
        }
        if (stations.length() > MetroContract.MAX_PACK_STATIONS) {
            throw new PackException("too many stations: " + stations.length());
        }
        final JSONArray cells = root.optJSONArray("cells");
        if (cells == null || cells.length() == 0) {
            throw new PackException("pack has no cells");
        }
        if (cells.length() > MetroContract.MAX_PACK_CELLS) {
            throw new PackException("too many cells: " + cells.length());
        }

        try {
            final Map<String, String> displayNames = new LinkedHashMap<>();
            final Map<String, List<String>> stationLineIds = new HashMap<>();
            for (int i = 0; i < stations.length(); i++) {
                final JSONObject station = stations.getJSONObject(i);
                final String code = station.getString("code");
                if (!STATION_CODE.matcher(code).matches()) {
                    throw new PackException("invalid station code '" + code + "'");
                }
                if (displayNames.containsKey(code)) {
                    throw new PackException("duplicate station code '" + code + "'");
                }
                final String displayName = station.getString("displayName");
                if (displayName.isEmpty()) {
                    throw new PackException("station '" + code + "' has no display name");
                }
                final JSONArray lineIds = station.optJSONArray("lineIds");
                if (lineIds == null || lineIds.length() == 0) {
                    throw new PackException("station '" + code + "' has no lines");
                }
                final List<String> ids = new ArrayList<>(lineIds.length());
                for (int j = 0; j < lineIds.length(); j++) {
                    ids.add(lineIds.getString(j));
                }
                displayNames.put(code, displayName);
                stationLineIds.put(code, ids);
            }

            final Connectivity connectivity = new Connectivity();
            for (String code : displayNames.keySet()) {
                connectivity.add(code);
            }

            final Map<String, String> lineNamesById = new LinkedHashMap<>();
            for (int i = 0; i < lines.length(); i++) {
                final JSONObject line = lines.getJSONObject(i);
                final String id = line.getString("id");
                final String name = line.getString("name");
                if (id.isEmpty() || name.isEmpty()) {
                    throw new PackException("line " + i + " is missing id or name");
                }
                if (lineNamesById.put(id, name) != null) {
                    throw new PackException("duplicate line id '" + id + "'");
                }
                final JSONArray stationCodes = line.optJSONArray("stationCodes");
                if (stationCodes == null || stationCodes.length() < 2) {
                    throw new PackException("line '" + id + "' must reference two stations");
                }
                for (int j = 0; j < stationCodes.length(); j++) {
                    final String code = stationCodes.getString(j);
                    if (!displayNames.containsKey(code)) {
                        throw new PackException("line '" + id + "' references unknown station '"
                                + code + "'");
                    }
                    if (j > 0) {
                        // Consecutive stations of a line are rideable in both directions; transfers
                        // appear as shared stations, so they are connected automatically.
                        connectivity.union(stationCodes.getString(j - 1), code);
                    }
                }
            }

            final Map<String, String> lineNames = new HashMap<>(displayNames.size());
            for (Map.Entry<String, List<String>> entry : stationLineIds.entrySet()) {
                final StringBuilder names = new StringBuilder();
                for (String lineId : entry.getValue()) {
                    final String name = lineNamesById.get(lineId);
                    if (name == null) {
                        throw new PackException("station '" + entry.getKey()
                                + "' references unknown line '" + lineId + "'");
                    }
                    if (names.length() > 0) {
                        names.append(',');
                    }
                    names.append(name);
                }
                lineNames.put(entry.getKey(), names.toString());
            }

            final Map<String, Set<String>> index = new HashMap<>(cells.length() * 2);
            for (int i = 0; i < cells.length(); i++) {
                final JSONObject cell = cells.getJSONObject(i);
                final String stationCode = cell.getString("stationCode");
                if (!displayNames.containsKey(stationCode)) {
                    throw new PackException("cell " + i + " references unknown station '"
                            + stationCode + "'");
                }
                final long cellId = cell.getLong("cellId");
                if (cellId <= 0) {
                    throw new PackException("cell " + i + " has no usable cell id");
                }
                final String key;
                if (matchMode == MetroContract.MATCH_MODE_EXACT) {
                    final String rat = cell.getString("rat");
                    if (!EXACT_RATS.contains(rat)) {
                        throw new PackException("exact pack cell " + i + " has unsupported RAT '"
                                + rat + "'");
                    }
                    final int mcc = cell.getInt("mcc");
                    final int mnc = cell.getInt("mnc");
                    final long tac = cell.getLong("tacOrLac");
                    if (mcc < 0 || mnc < 0 || tac < 0) {
                        throw new PackException("exact pack cell " + i
                                + " has incomplete network identity");
                    }
                    key = exactKey(rat, mcc, mnc, tac, cellId);
                } else {
                    key = cidKey(cellId);
                }
                index.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(stationCode);
            }

            final Map<String, String[]> cellIndex = new HashMap<>(index.size() * 2);
            int ambiguous = 0;
            for (Map.Entry<String, Set<String>> entry : index.entrySet()) {
                final List<String> codes = new ArrayList<>(entry.getValue());
                if (codes.size() > 1) {
                    ambiguous++;
                    Collections.sort(codes);
                }
                cellIndex.put(entry.getKey(), codes.toArray(new String[0]));
            }
            return new MetroPackIndex(city, packVersion, matchMode, declared, cellIndex,
                    Collections.unmodifiableMap(connectivity.toComponents()),
                    Collections.unmodifiableMap(displayNames),
                    Collections.unmodifiableMap(lineNames), cells.length(), lines.length(),
                    ambiguous);
        } catch (JSONException e) {
            throw new PackException("malformed pack field: " + e.getMessage());
        }
    }

    /**
     * Union find over station codes. Built once at load time so connectivity queries stay O(1) and
     * the published index stays immutable.
     */
    private static final class Connectivity {
        private final Map<String, String> mParent = new HashMap<>();

        void add(String code) {
            mParent.put(code, code);
        }

        void union(String left, String right) {
            final String leftRoot = find(left);
            final String rightRoot = find(right);
            if (!leftRoot.equals(rightRoot)) {
                mParent.put(leftRoot, rightRoot);
            }
        }

        private String find(String code) {
            String root = code;
            while (!root.equals(mParent.get(root))) {
                root = mParent.get(root);
            }
            String cursor = code;
            while (!cursor.equals(root)) {
                final String next = mParent.get(cursor);
                mParent.put(cursor, root);
                cursor = next;
            }
            return root;
        }

        Map<String, Integer> toComponents() {
            final Map<String, Integer> rootIds = new HashMap<>();
            final Map<String, Integer> components = new HashMap<>(mParent.size());
            for (String code : mParent.keySet()) {
                final String root = find(code);
                Integer id = rootIds.get(root);
                if (id == null) {
                    id = rootIds.size();
                    rootIds.put(root, id);
                }
                components.put(code, id);
            }
            return components;
        }
    }

    /** Reads the pack with a hard upper bound so a corrupt or replaced file cannot exhaust memory. */
    private static String readBounded(File file) throws IOException, PackException {
        if (!file.isFile()) {
            throw new PackException("pack file " + file.getPath() + " is missing");
        }
        final long length = file.length();
        if (length <= 0) {
            throw new PackException("pack file is empty");
        }
        if (length > MetroContract.MAX_PACK_FILE_BYTES) {
            throw new PackException("pack file is too large: " + length + " bytes");
        }
        final StringBuilder builder = new StringBuilder((int) Math.min(length, 1 << 16));
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        long read = 0;
        try (Reader reader = new InputStreamReader(new FileInputStream(file), decoder)) {
            final char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) > 0) {
                read += count;
                if (read > MetroContract.MAX_PACK_FILE_BYTES) {
                    throw new PackException("pack file grew past the size limit while reading");
                }
                builder.append(buffer, 0, count);
            }
        } catch (CharacterCodingException e) {
            throw new PackException("pack file is not valid UTF-8");
        }
        return builder.toString();
    }
}
