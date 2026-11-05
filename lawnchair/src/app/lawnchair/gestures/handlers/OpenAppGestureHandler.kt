/*
 * Modifications copyright 2026, Nguyen-Do Consulting
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
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.R
import app.lawnchair.util.ComponentKeySerializer
import app.lawnchair.util.IntentSerializer
import app.lawnchair.util.UserHandlerSerializer
import com.android.launcher3.util.ComponentKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class OpenAppGestureHandler(
    context: Context,
    private val target: OpenAppTarget,
) : GestureHandler(context) {

    override suspend fun onTrigger(launcher: LawnchairLauncher) {
        when (target) {
            is OpenAppTarget.App -> {
                val key = target.key
                launcher.getSystemService<LauncherApps>()?.startMainActivity(
                    key.componentName,
                    key.user,
                    null,
                    null,
                )
            }

            // NDShades: starts the pinned shortcut (upstream: did nothing)
            is OpenAppTarget.Shortcut -> {
                try {
                    launcher.getSystemService<LauncherApps>()?.startShortcut(
                        target.packageName,
                        target.id,
                        null,
                        null,
                        target.user,
                    )
                } catch (e: ActivityNotFoundException) {
                    onShortcutUnavailable(launcher, e)
                } catch (e: IllegalStateException) {
                    onShortcutUnavailable(launcher, e)
                } catch (e: SecurityException) {
                    onShortcutUnavailable(launcher, e)
                }
            }
        }
    }

    // NDShades: The shortcut was removed by its app, or its app was uninstalled.
    private fun onShortcutUnavailable(launcher: LawnchairLauncher, e: Exception) {
        Log.w(TAG, "Can't start shortcut", e)
        Toast.makeText(launcher, R.string.shortcut_not_available, Toast.LENGTH_SHORT).show()
    }

    companion object { // NDShades
        private const val TAG = "OpenAppGestureHandler"
    }
}

@Serializable
sealed class OpenAppTarget {
    @Serializable
    @SerialName("app")
    data class App(
        @Serializable(ComponentKeySerializer::class) val key: ComponentKey,
    ) : OpenAppTarget()

    @Serializable
    @SerialName("shortcut")
    data class Shortcut(
        @Serializable(IntentSerializer::class) val intent: Intent,
        @Serializable(UserHandlerSerializer::class) val user: UserHandle,
        val packageName: String,
        val id: String,
    ) : OpenAppTarget()
}
