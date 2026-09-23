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

import android.metro.MetroSessionFeedback;

/**
 * Session state reported by the assistant application. The platform implements this interface and
 * hands it to the application in {@link IMetroSession#onTriggerConfig}; the application is the
 * only writer. It is one way: the platform never blocks the application on session feedback.
 *
 * @hide
 */
oneway interface IMetroTriggerCallback {
    /**
     * @param feedback one of the {@code MetroContract.FEEDBACK_STATE_*} transitions, carrying the
     *        candidate id and the generation it belongs to.
     */
    void onSessionFeedback(in MetroSessionFeedback feedback);
}
