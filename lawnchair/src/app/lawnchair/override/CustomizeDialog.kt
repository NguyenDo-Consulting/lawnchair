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

package app.lawnchair.override

import android.app.Activity
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.BlankActivity
import app.lawnchair.gestures.type.GestureType
import app.lawnchair.launcher
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.asState
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.components.AppGesturePreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.ClickableIcon
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.navigation.SelectIcon
import app.lawnchair.ui.util.addIfNotNull
import app.lawnchair.util.navigationBarsOrDisplayCutoutPadding
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.util.ComponentKey
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.launch

@Composable
fun CustomizeDialog(
    icon: Drawable,
    title: String,
    onTitleChange: (String) -> Unit,
    defaultTitle: String,
    launchSelectIcon: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .navigationBarsOrDisplayCutoutPadding()
            .fillMaxWidth(),
    ) {
        val iconPainter = rememberDrawablePainter(drawable = icon)
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 24.dp)
                .clip(MaterialTheme.shapes.small)
                .addIfNotNull(launchSelectIcon) {
                    clickable(onClick = it)
                }
                .padding(all = 8.dp),
        ) {
            Image(
                painter = iconPainter,
                contentDescription = null,
                modifier = Modifier.size(54.dp),
            )
            if (launchSelectIcon != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(12.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            trailingIcon = {
                if (title != defaultTitle) {
                    ClickableIcon(
                        painter = painterResource(id = R.drawable.ic_undo),
                        onClick = { onTitleChange(defaultTitle) },
                    )
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
            shape = MaterialTheme.shapes.large,
            label = { Text(text = stringResource(id = R.string.label)) },
            isError = title.isEmpty(),
        )
        content?.invoke()
    }
}

@Composable
fun CustomizeAppDialog(
    icon: Drawable,
    defaultTitle: String,
    componentKey: ComponentKey,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    val prefs = preferenceManager()
    val preferenceManager2 = preferenceManager2()
    val showComponentNames by preferenceManager2.showComponentNames.asState()
    val hiddenApps by preferenceManager2.hiddenApps.asState()
    val adapter = preferenceManager2.hiddenApps.getAdapter()
    val context = LocalContext.current
    var title by remember {
        mutableStateOf(prefs.customAppName[componentKey] ?: defaultTitle)
    }
    val launcherAppState = LauncherAppState.getInstance(context)

    val route = SelectIcon(componentKey.toString())

    Log.d("CustomizeDialog", route.toString())

    val scope = rememberCoroutineScope()
    val openIconPicker = {
        val intent = PreferenceActivity.createIntent(context, route)
        scope.launch {
            val result = BlankActivity.startBlankActivityForResult(context as Activity, intent)
            if (result.resultCode == Activity.RESULT_OK) {
                onClose()
            }
        }
        Unit
    }

    DisposableEffect(Unit) {
        onDispose {
            val previousTitle = prefs.customAppName[componentKey]
            val newTitle = if (title != defaultTitle) title else null
            if (newTitle != previousTitle) {
                prefs.customAppName[componentKey] = newTitle
                val model = launcherAppState.model
                model.onAppIconChanged(componentKey.componentName.packageName, componentKey.user)
            }
        }
    }
    CustomizeDialog(
        icon = icon,
        title = title,
        onTitleChange = { title = it },
        defaultTitle = defaultTitle,
        launchSelectIcon = openIconPicker,
        modifier = modifier,
    ) {
        PreferenceGroup(
            description = componentKey.componentName.flattenToString(),
            showDescription = showComponentNames,
        ) {
            val stringKey = componentKey.toString()
            SwitchPreference(
                checked = hiddenApps.contains(stringKey),
                label = stringResource(id = R.string.hide_from_drawer),
                onCheckedChange = { newValue ->
                    val newSet = hiddenApps.toMutableSet()
                    if (newValue) newSet.add(stringKey) else newSet.remove(stringKey)
                    adapter.onChange(newSet)
                },
            )
        }
        if (context.launcher.stateManager.state != LauncherState.ALL_APPS) {
            IconGesturesGroup(iconKey = componentKey.toString())
        }
    }
}

/**
 * Customize dialog of a pinned shortcut (e.g. a conversation or a direct dial shortcut). A
 * shortcut can't be renamed or re-iconed here, but it has its own swipe gestures.
 */
@Composable
fun CustomizeShortcutDialog(
    icon: Drawable,
    title: String,
    iconKey: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .navigationBarsOrDisplayCutoutPadding()
            .fillMaxWidth(),
    ) {
        Image(
            painter = rememberDrawablePainter(drawable = icon),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 32.dp, bottom = 8.dp)
                .size(54.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 16.dp),
        )
        IconGesturesGroup(iconKey = iconKey)
    }
}

@Composable
private fun IconGesturesGroup(iconKey: String) {
    PreferenceGroup(heading = stringResource(R.string.gestures_label)) {
        listOf(
            GestureType.SWIPE_UP,
            GestureType.SWIPE_DOWN,
            GestureType.SWIPE_LEFT,
            GestureType.SWIPE_RIGHT,
        ).map { gestureType ->
            AppGesturePreference(
                iconKey,
                gestureType,
                stringResource(id = gestureType.labelResId),
            )
        }
    }
}
