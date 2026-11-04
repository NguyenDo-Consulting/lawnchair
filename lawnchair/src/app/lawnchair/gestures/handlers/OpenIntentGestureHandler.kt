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

package app.lawnchair.gestures.handlers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.R

/** Starts an intent that isn't an app or a pinned shortcut, e.g. texting a contact. */
class OpenIntentGestureHandler(
    context: Context,
    private val intent: Intent,
) : GestureHandler(context) {

    override suspend fun onTrigger(launcher: LawnchairLauncher) {
        try {
            launcher.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            onIntentUnavailable(launcher, e)
        } catch (e: SecurityException) {
            onIntentUnavailable(launcher, e)
        }
    }

    private fun onIntentUnavailable(launcher: LawnchairLauncher, e: Exception) {
        Log.w(TAG, "Can't start $intent", e)
        Toast.makeText(launcher, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "OpenIntentGestureHandler"
    }
}
