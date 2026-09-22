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

package com.android.server.am.apm;

/**
 * Called-side provider black list. A deny returns before {@code publish} runs, so the
 * provider is not published. An allow leaves the existing publish path to the caller.
 */
public final class ProviderPublishGate {
    private ProviderPublishGate() {}

    public static boolean publishIfAllowed(boolean allowed, Runnable publish) {
        if (!allowed) {
            return false;
        }
        if (publish != null) {
            publish.run();
        }
        return true;
    }
}
