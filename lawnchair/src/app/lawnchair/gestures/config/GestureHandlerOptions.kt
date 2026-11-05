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

val gestureHandlerOptions = listOf(
    GestureHandlerOption.NoOp,
    GestureHandlerOption.Sleep,
    GestureHandlerOption.Recents,
    GestureHandlerOption.OpenNotifications,
    GestureHandlerOption.OpenQuickSettings,
    GestureHandlerOption.OpenAppDrawer,
    GestureHandlerOption.OpenAppSearch,
    GestureHandlerOption.OpenSearch,
    GestureHandlerOption.OpenApp,
    GestureHandlerOption.OpenShortcut, // NDShades
    GestureHandlerOption.OpenAssistant,
)

private val optionsDisabledInDeckLayout = setOf(
    GestureHandlerOption.OpenAppDrawer,
    GestureHandlerOption.OpenAppSearch,
)

fun filterGestureHandlerOptions(deckLayoutEnabled: Boolean): List<GestureHandlerOption> {
    if (!deckLayoutEnabled) return gestureHandlerOptions
    return gestureHandlerOptions.filterNot { it in optionsDisabledInDeckLayout }
}

suspend fun GestureHandlerOption.buildConfigFrom(context: Context): GestureHandlerConfig? {
    val activity = context as? Activity ?: return null
    return buildConfig(activity)
}
