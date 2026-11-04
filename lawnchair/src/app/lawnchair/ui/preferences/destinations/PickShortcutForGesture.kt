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

package app.lawnchair.ui.preferences.destinations

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.PinItemRequest
import android.graphics.Bitmap
import android.os.Process
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.lawnchair.gestures.GestureShortcutPins
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.gestures.handlers.OpenAppTarget
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.AppItem
import app.lawnchair.ui.preferences.components.AppItemPlaceholder
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import app.lawnchair.util.kotlinxJson
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.pm.PinRequestHelper
import com.android.launcher3.pm.ShortcutConfigActivityInfo
import com.android.launcher3.pm.ShortcutConfigActivityInfo.ShortcutConfigActivityInfoVO
import com.android.launcher3.shortcuts.ShortcutKey
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An app's shortcut config activity, e.g. "Direct dial" of Contacts. */
private class ShortcutConfigEntry(
    val info: ShortcutConfigActivityInfo,
    val label: String,
    val icon: Bitmap,
)

/**
 * Lets the user create a shortcut with an app's shortcut config activity, the same way as
 * adding it to the home screen from the widgets list, and returns a
 * [GestureHandlerConfig.OpenShortcut] that opens it.
 */
@Composable
fun PickShortcutForGesture() {
    val context = LocalContext.current
    val activity = context as Activity
    val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(context)
    val state = rememberLazyListState()

    val entries by produceState<List<ShortcutConfigEntry>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadShortcutConfigEntries(context) }
    }
    var pendingEntry by remember { mutableStateOf<ShortcutConfigEntry?>(null) }

    fun onConfigResult(result: ActivityResult) {
        val entry = pendingEntry ?: return
        pendingEntry = null
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) return
        val config = acceptShortcut(context, data, entry.label)
        if (config == null) {
            Toast.makeText(context, R.string.shortcut_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        val configString = kotlinxJson.encodeToString(config)
        activity.setResult(Activity.RESULT_OK, Intent().putExtra("config", configString))
        activity.finish()
    }

    val intentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ::onConfigResult,
    )
    val intentSenderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
        ::onConfigResult,
    )

    fun onSelectEntry(entry: ShortcutConfigEntry) {
        pendingEntry = entry
        val info = entry.info
        try {
            // Same as ShortcutConfigActivityInfoVO.startConfigActivity: another profile's config
            // activity can only be started through LauncherApps.
            if (info is ShortcutConfigActivityInfoVO && info.user != Process.myUserHandle()) {
                val sender = context.getSystemService(LauncherApps::class.java)
                    .getShortcutConfigActivityIntent(info.mInfo) ?: return
                intentSenderLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                intentLauncher.launch(
                    Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(info.component),
                )
            }
        } catch (_: ActivityNotFoundException) {
            pendingEntry = null
            Toast.makeText(context, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    PreferenceScaffold(
        label = stringResource(id = R.string.pick_shortcut_for_gesture),
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) {
        Crossfade(targetState = entries, label = "") { loadedEntries ->
            when {
                loadedEntries == null -> {
                    PreferenceLazyColumn(it, enabled = false) {
                        preferenceGroupItems(
                            count = 20,
                            isFirstChild = true,
                        ) {
                            AppItemPlaceholder()
                        }
                    }
                }

                loadedEntries.isEmpty() -> {
                    Text(
                        text = stringResource(id = R.string.pick_shortcut_for_gesture_empty),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(it)
                            .padding(16.dp),
                    )
                }

                else -> {
                    PreferenceLazyColumn(it, state = state) {
                        preferenceGroupItems(
                            items = loadedEntries,
                            isFirstChild = true,
                        ) { _, entry ->
                            AppItem(
                                label = entry.label,
                                icon = entry.icon,
                                onClick = {
                                    mMSDLPlayerWrapper.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                                    onSelectEntry(entry)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun loadShortcutConfigEntries(context: Context): List<ShortcutConfigEntry> {
    val iconCache = LauncherAppState.getInstance(context).iconCache
    return ShortcutConfigActivityInfo.queryList(context, null)
        .map { info ->
            ShortcutConfigEntry(
                info = info,
                label = info.label?.toString().orEmpty(),
                icon = (info.getFullResIcon(iconCache) ?: context.packageManager.defaultActivityIcon)
                    .toBitmap(),
            )
        }
        .sortedBy { it.label.lowercase() }
}

/**
 * Accepts the shortcut that a config activity returned as a pin request, so that it stays
 * startable, and returns the gesture config that opens it.
 */
private fun acceptShortcut(
    context: Context,
    data: Intent,
    kindLabel: String,
): GestureHandlerConfig? {
    val request = PinRequestHelper.getPinItemRequest(data) ?: return null
    if (request.requestType != PinItemRequest.REQUEST_TYPE_SHORTCUT || !request.isValid) {
        return null
    }
    val shortcut = request.shortcutInfo ?: return null
    // Keep it pinned before the launcher model can see it and unpin it as unknown.
    GestureShortcutPins.addPending(ShortcutKey.fromInfo(shortcut))
    if (!request.accept()) return null

    val name = shortcut.shortLabel?.toString() ?: kindLabel
    return GestureHandlerConfig.OpenShortcut(
        label = context.getString(R.string.gesture_handler_open_shortcut_label, name, kindLabel),
        target = OpenAppTarget.Shortcut(
            intent = ShortcutKey.makeIntent(shortcut),
            user = shortcut.userHandle,
            packageName = shortcut.`package`,
            id = shortcut.id,
        ),
    )
}
