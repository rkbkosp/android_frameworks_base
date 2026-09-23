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

package android.apm;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The per user whitelists of the adaptive process manager, shared by {@code system_server}
 * and the Settings entry point.
 *
 * <p>One value per user holds the whole table: {@code pkg:bits} entries, comma separated.
 * A package name cannot contain {@code :} or {@code ,}, so the encoding is unambiguous. A
 * missing value, a malformed entry, and an entry whose bits are zero all mean the same
 * thing: the package is not in the table. Bits above {@link #BITS_ALL} are dropped on read,
 * so a newer writer cannot leave an older reader holding a column it does not know.
 *
 * <p>Settings replaces one entry through {@link #setBits}; {@code system_server} reads the
 * whole table through {@link #parse}. Both sides go through this class, so neither keeps its
 * own copy of the format.
 *
 * @hide
 */
public final class ApmWhitelist {
    private static final String TAG = "ApmWhitelist";

    /** {@code Settings.Secure} key holding the table. */
    public static final String SETTING = "apm_whitelist";

    /** Auto-start interception: the package may be started, bound, and delivered to. */
    public static final int AUTO_START = 1;
    /** The package is not frozen. */
    public static final int DENY_FREEZE = 2;
    /** The cached killer leaves the package alone. */
    public static final int DENY_KILL = 4;
    /** The package keeps its network while it is frozen. */
    public static final int ALLOW_NETWORK = 8;
    /** Jobs, alarms, and service starts of the package are not deferred. */
    public static final int ALLOW_WAKEUP = 16;

    /** Every column. */
    public static final int BITS_ALL = AUTO_START | DENY_FREEZE | DENY_KILL | ALLOW_NETWORK
            | ALLOW_WAKEUP;

    private static final int[] COLUMNS = {
            AUTO_START, DENY_FREEZE, DENY_KILL, ALLOW_NETWORK, ALLOW_WAKEUP,
    };
    private static final String[] COLUMN_NAMES = {
            "AUTO_START", "DENY_FREEZE", "DENY_KILL", "ALLOW_NETWORK", "ALLOW_WAKEUP",
    };

    private static final char ENTRY_SEPARATOR = ',';
    private static final char BITS_SEPARATOR = ':';
    /** {@code PackageParser} upper bound. A longer name is not a package on this device. */
    private static final int MAX_PACKAGE_LENGTH = 255;
    /** {@link #BITS_ALL} needs two digits; a longer run of digits cannot be a valid column. */
    private static final int MAX_BITS_LENGTH = 9;

    private ApmWhitelist() {}

    /** The settings row the table lives in, per user. */
    public static Uri uri() {
        return Settings.Secure.getUriFor(SETTING);
    }

    /** @return true when {@code bits} holds {@code column}. */
    public static boolean has(int bits, int column) {
        return (bits & column) != 0;
    }

    /**
     * @return a stable name for one of the {@code COLUMN} bits, for logs and for a caller
     *         that names the columns itself. Never allocates a second table.
     */
    public static String columnName(int column) {
        for (int i = 0; i < COLUMNS.length; i++) {
            if (COLUMNS[i] == column) {
                return COLUMN_NAMES[i];
            }
        }
        return "UNKNOWN";
    }

    /**
     * Encodes one user's table. Entries whose bits are not in {@link #BITS_ALL} are dropped,
     * and the surviving entries are written in package name order, so the same table always
     * produces the same string.
     */
    public static String encode(@Nullable Map<String, Integer> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        final List<String> names = new ArrayList<>(entries.size());
        for (Map.Entry<String, Integer> entry : entries.entrySet()) {
            final Integer bits = entry.getValue();
            if (entry.getKey() == null || bits == null || (bits & BITS_ALL) == 0) {
                continue;
            }
            names.add(entry.getKey());
        }
        Collections.sort(names);
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(ENTRY_SEPARATOR);
            }
            final String name = names.get(i);
            out.append(name).append(BITS_SEPARATOR).append(entries.get(name) & BITS_ALL);
        }
        return out.toString();
    }

    /**
     * Decodes one user's table. Never returns null and never throws: a value that was
     * written by hand or by another build is read as the entries it does contain.
     */
    public static Map<String, Integer> parse(@Nullable String raw) {
        final Map<String, Integer> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        final int length = raw.length();
        int start = 0;
        for (int i = 0; i <= length; i++) {
            if (i == length || raw.charAt(i) == ENTRY_SEPARATOR) {
                parseEntry(raw, start, i, out);
                start = i + 1;
            }
        }
        return out;
    }

    /** One {@code pkg:bits} entry of {@code raw}. A malformed entry is skipped. */
    private static void parseEntry(String raw, int start, int end, Map<String, Integer> out) {
        int separator = -1;
        for (int i = start; i < end; i++) {
            if (raw.charAt(i) == BITS_SEPARATOR) {
                separator = i;
                break;
            }
        }
        if (separator <= start || separator == end - 1) {
            return;
        }
        final String packageName = raw.substring(start, separator);
        if (!isValidPackageName(packageName)) {
            return;
        }
        final int bits = parseBits(raw, separator + 1, end);
        if (bits != 0) {
            out.put(packageName, bits);
        }
    }

    /** @return the masked bits, or 0 when the field is not a plain positive decimal number. */
    private static int parseBits(String raw, int start, int end) {
        final int length = end - start;
        if (length == 0 || length > MAX_BITS_LENGTH) {
            return 0;
        }
        int bits = 0;
        for (int i = start; i < end; i++) {
            final char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
            bits = bits * 10 + (c - '0');
        }
        return bits & BITS_ALL;
    }

    /**
     * One user's table.
     *
     * @return null when the value could not be read. The caller must not treat that as an
     *         empty table: writing an empty one back would drop every other entry.
     */
    @Nullable
    private static Map<String, Integer> entriesFor(@Nullable ContentResolver cr, int userId) {
        if (cr == null) {
            return null;
        }
        try {
            return parse(Settings.Secure.getStringForUser(cr, SETTING, userId));
        } catch (Throwable t) {
            Log.w(TAG, "whitelist read failed for user " + userId, t);
            return null;
        }
    }

    /** One package's bits, or 0 when it is not in the table. */
    public static int bitsFor(@Nullable ContentResolver cr, int userId, @Nullable String pkg) {
        if (cr == null || pkg == null) {
            return 0;
        }
        final Map<String, Integer> entries = entriesFor(cr, userId);
        if (entries == null) {
            return 0;
        }
        final Integer bits = entries.get(pkg);
        return bits == null ? 0 : bits;
    }

    /**
     * Replaces one package's row. {@code bits} outside {@link #BITS_ALL}, and 0, both remove
     * the row. A read that failed skips the write, because the table would otherwise be
     * rewritten from an empty one; a write that failed is logged and leaves the table as it
     * was.
     */
    public static void setBits(@Nullable ContentResolver cr, int userId, @Nullable String pkg,
            int bits) {
        if (cr == null || !isValidPackageName(pkg)) {
            return;
        }
        final Map<String, Integer> entries = entriesFor(cr, userId);
        if (entries == null) {
            Log.w(TAG, "whitelist read failed; not writing user " + userId);
            return;
        }
        final int masked = bits & BITS_ALL;
        if (masked == 0) {
            entries.remove(pkg);
        } else {
            entries.put(pkg, masked);
        }
        try {
            Settings.Secure.putStringForUser(cr, SETTING, encode(entries), userId);
        } catch (Throwable t) {
            Log.w(TAG, "whitelist write failed for user " + userId, t);
        }
    }

    /**
     * @return true when {@code name} itself is a package name this table can hold. The rule
     *         is the one the reader applies, so a name a writer stores here is one the reader
     *         reads back; a caller that takes a name from a user validates it with this.
     */
    public static boolean isValidPackageName(@Nullable String name) {
        if (name == null) {
            return false;
        }
        final int length = name.length();
        if (length == 0 || length > MAX_PACKAGE_LENGTH) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            final char c = name.charAt(i);
            final boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
