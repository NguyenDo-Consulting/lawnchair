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

package app.lawnchair.gestures.config

import android.app.Activity
import android.content.Context
import app.lawnchair.BlankActivity
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.GesturesPickApp
import app.lawnchair.ui.preferences.navigation.GesturesPickShortcut
import app.lawnchair.util.kotlinxJson
import com.android.launcher3.R

sealed class GestureHandlerOption(
    private val labelRes: Int,
    val iconRes: Int,
    val configClass: Class<*>,
) {
    fun getLabel(context: Context) = context.getString(labelRes)

    abstract suspend fun buildConfig(activity: Activity): GestureHandlerConfig?

    sealed class Simple(labelRes: Int, iconRes: Int, val obj: GestureHandlerConfig) : GestureHandlerOption(labelRes, iconRes, obj::class.java) {
        constructor(obj: GestureHandlerConfig.Simple) : this(obj.labelRes, obj.iconRes, obj)
        override suspend fun buildConfig(activity: Activity) = obj
    }

    data object NoOp : Simple(GestureHandlerConfig.NoOp)
    data object Sleep : Simple(GestureHandlerConfig.Sleep)
    data object Recents : Simple(GestureHandlerConfig.Recents)
    data object OpenNotifications : Simple(GestureHandlerConfig.OpenNotifications)
    data object OpenQuickSettings : Simple(GestureHandlerConfig.OpenQuickSettings)
    data object OpenAppDrawer : Simple(GestureHandlerConfig.OpenAppDrawer)
    data object OpenAppSearch : Simple(GestureHandlerConfig.OpenAppSearch)
    data object OpenSearch : Simple(GestureHandlerConfig.OpenSearch)
    data object OpenAssistant : Simple(GestureHandlerConfig.OpenAssistant)

    data object OpenApp : GestureHandlerOption(
        R.string.gesture_handler_open_app_option,
        R.drawable.ic_launcher_home,
        GestureHandlerConfig.OpenApp::class.java,
    ) {
        override suspend fun buildConfig(activity: Activity): GestureHandlerConfig? {
            val intent = PreferenceActivity.createIntent(activity, GesturesPickApp)
            val result = BlankActivity.startBlankActivityForResult(activity, intent)
            val configString = result.data?.getStringExtra("config") ?: return null
            return kotlinxJson.decodeFromString(configString)
        }
    }

    // NDShades: "Open shortcut" gesture action, picks a pinned shortcut
    data object OpenShortcut : GestureHandlerOption(
        R.string.gesture_handler_open_shortcut_option,
        R.drawable.ic_launcher_home,
        GestureHandlerConfig.OpenShortcut::class.java,
    ) {
        override suspend fun buildConfig(activity: Activity): GestureHandlerConfig? {
            val intent = PreferenceActivity.createIntent(activity, GesturesPickShortcut)
            val result = BlankActivity.startBlankActivityForResult(activity, intent)
            val configString = result.data?.getStringExtra("config") ?: return null
            return kotlinxJson.decodeFromString(configString)
        }
    }
}
