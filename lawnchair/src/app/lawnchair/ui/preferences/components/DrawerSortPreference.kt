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

package app.lawnchair.ui.preferences.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.lawnchair.allapps.DrawerSortMode
import app.lawnchair.folder.FolderUsageStats
import app.lawnchair.folder.openUsageAccessSettings
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import com.android.launcher3.R

/**
 * NDShades: The app drawer's sort order. Picking Most used without usage access opens Android's
 * usage access screen, and until it's granted the line under the label says why it's needed.
 */
@Composable
fun DrawerSortPreference(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adapter = preferenceManager2().drawerSortMode.getAdapter()
    val mode = DrawerSortMode.fromId(adapter.state.value)

    // Checked again on return from Android's usage access screen
    var hasUsageAccess by remember { mutableStateOf(FolderUsageStats.hasUsageAccess(context)) }
    LifecycleResumeEffect(context) {
        hasUsageAccess = FolderUsageStats.hasUsageAccess(context)
        onPauseOrDispose {}
    }

    ListPreference(
        entries = DrawerSortMode.entries.map { entry ->
            ListPreferenceEntry(entry.id) { stringResource(entry.labelRes) }
        },
        value = mode.id,
        onValueChange = { id ->
            adapter.onChange(id)
            if (DrawerSortMode.fromId(id) == DrawerSortMode.MOST_USED && !FolderUsageStats.hasUsageAccess(context)) {
                openUsageAccessSettings(context)
            }
        },
        label = stringResource(R.string.drawer_sort_label),
        // Null shows the chosen sort, like other list settings
        description = if (mode == DrawerSortMode.MOST_USED && !hasUsageAccess) {
            stringResource(R.string.drawer_sort_most_used_needs_access)
        } else {
            null
        },
        modifier = modifier,
    )
}
