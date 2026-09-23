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

import android.metro.IMetroTriggerCallback;
import android.metro.MetroCandidate;
import android.metro.MetroTriggerConfig;

/**
 * Implemented by the metro assistant application. The platform binds the service explicitly and
 * keeps the binding for the lifetime of a candidate or a session.
 *
 * @hide
 */
interface IMetroSession {
    /**
     * Pushes the effective configuration and the feedback callback. Called on every bind and on
     * every generation change.
     */
    void onTriggerConfig(in MetroTriggerConfig config, in IMetroTriggerCallback callback);

    /**
     * Delivers a station candidate. The return value is the acknowledgement: {@code true} means the
     * application takes responsibility for confirming the session, {@code false} means it declines
     * and the platform backs off.
     */
    boolean onCandidate(in MetroCandidate candidate);

    /** Reports that the platform gating opened or closed. */
    void onGatingChanged(boolean eligible, in MetroTriggerConfig config);

    /**
     * Reports a new generation. Candidates, sessions and pending feedback from an older generation
     * must be discarded by the application.
     */
    void onGenerationChanged(long generation);
}
