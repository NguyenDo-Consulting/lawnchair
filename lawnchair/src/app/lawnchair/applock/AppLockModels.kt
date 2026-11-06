/*
 * Copyright 2026, Nguyen-Do Consulting
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.applock

import androidx.annotation.StringRes
import com.android.launcher3.R

/** How the locked apps are unlocked. See docs/ndc/locked-apps.md. */
enum class LockMethod(val id: String, @StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    /**
     * Whichever fingerprint or face Android rates as secure (an app can't pick one), tied to a key
     * that a new one destroys. Removing protection asks for the phone's pattern, PIN or password.
     */
    BIOMETRIC_ONLY("biometric", R.string.app_lock_method_biometric, R.string.app_lock_method_biometric_description),

    /** A PIN that only this app knows, also asked for when removing protection. */
    PIN("pin", R.string.app_lock_method_pin, R.string.app_lock_method_pin_description),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** When an unlock of the locked apps expires. */
enum class RelockMode(val id: String, @StringRes val labelRes: Int) {
    IMMEDIATELY("immediately", R.string.app_lock_relock_immediately),
    SCREEN_OFF("screen_off", R.string.app_lock_relock_screen_off),
    SCREEN_OFF_5_MIN("screen_off_5min", R.string.app_lock_relock_screen_off_5min),
    RESTART("restart", R.string.app_lock_relock_restart),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: SCREEN_OFF
    }
}
