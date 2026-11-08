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

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lawnchair.allapps.filters.DrawerFilter
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.model.FolderViewModel
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.colorpreference.ColorPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.components.reorderable.ReorderableDragHandle
import app.lawnchair.ui.preferences.components.reorderable.ReorderablePreferenceGroup
import app.lawnchair.ui.preferences.navigation.AppDrawerFilterApps
import app.lawnchair.ui.util.bottomSheetHandler
import app.lawnchair.util.appsState
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken
import java.util.UUID

/** Lists the user's app drawer filters, see [DrawerFilter]. */
@Composable
fun AppDrawerFiltersPreference(
    modifier: Modifier = Modifier,
) {
    val prefs2 = preferenceManager2()
    val filtersAdapter = prefs2.drawerFilters.getAdapter()
    val filters = filtersAdapter.state.value
    val navController = LocalNavController.current
    val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(LocalContext.current)
    val bottomSheetHandler = bottomSheetHandler

    fun update(transform: (List<DrawerFilter>) -> List<DrawerFilter>) {
        filtersAdapter.onChange(transform(filtersAdapter.state.value))
    }

    // The tab waiting for the user to confirm its deletion.
    var pendingDelete by remember { mutableStateOf<DrawerFilter?>(null) }
    pendingDelete?.let { filter ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = stringResource(R.string.delete_drawer_tab_title)) },
            text = { Text(text = stringResource(R.string.delete_drawer_tab_text, filter.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        mMSDLPlayerWrapper.playToken(MSDLToken.SUCCESS)
                        update { list -> list.filterNot { it.id == filter.id } }
                        pendingDelete = null
                    },
                ) {
                    Text(text = stringResource(R.string.delete_label))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }

    PreferenceLayout(
        label = stringResource(id = R.string.drawer_tabs_label),
        backArrowVisible = true,
        modifier = modifier,
    ) {
        PreferenceGroup(
            heading = stringResource(R.string.settings),
            description = stringResource(R.string.drawer_tabs_description),
        ) {
            SwitchPreference(
                adapter = prefs2.hideFilteredAppsFromAll.getAdapter(),
                label = stringResource(id = R.string.hide_tab_apps_from_all_label),
                description = stringResource(id = R.string.hide_tab_apps_from_all_description),
            )
            SwitchPreference(
                adapter = prefs2.hideDrawerTabLabels.getAdapter(),
                label = stringResource(id = R.string.hide_drawer_tab_labels_label),
                description = stringResource(id = R.string.hide_drawer_tab_labels_description),
            )
            ColorPreference(preference = prefs2.workProfileTabBackgroundColor)
            SwitchPreference(
                label = stringResource(id = R.string.work_profile_tab_container_background_label),
                adapter = prefs2.workProfileTabContainerBackground.getAdapter(),
            )
        }
        PreferenceGroup(heading = stringResource(R.string.drawer_tabs_label)) {
            PreferenceTemplate(
                title = {
                    Text(
                        text = stringResource(R.string.add_drawer_tab),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                startWidget = {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                },
                onClick = {
                    mMSDLPlayerWrapper.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                    bottomSheetHandler.show {
                        // The sheet is shared with drawer folders, which identify items by index here.
                        FolderEditSheet(
                            folderId = 0,
                            initialTitle = stringResource(R.string.my_drawer_tab_label),
                            itemCount = 0,
                            onRename = { _, title ->
                                if (title.isNotBlank()) {
                                    update { it + DrawerFilter(id = UUID.randomUUID().toString(), title = title) }
                                }
                            },
                            onNavigate = {},
                            onDismiss = { bottomSheetHandler.hide() },
                            hideAppPicker = true,
                        )
                    }
                },
            )
        }
        ReorderablePreferenceGroup(
            label = null,
            items = filters,
            defaultList = filters,
            onOrderChange = { reordered -> filtersAdapter.onChange(reordered) },
        ) { filter, index, _ ->
            val interactionSource = remember { MutableInteractionSource() }
            DrawerFilterItem(
                filter = filter,
                onClick = {
                    mMSDLPlayerWrapper.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                    bottomSheetHandler.show {
                        FolderEditSheet(
                            folderId = index,
                            initialTitle = filter.title,
                            itemCount = filter.componentKeys.size,
                            onRename = { _, title ->
                                if (title.isNotBlank()) {
                                    update { list -> list.map { if (it.id == filter.id) it.copy(title = title) else it } }
                                }
                            },
                            onNavigate = {
                                navController.navigate(AppDrawerFilterApps(filter.id))
                                bottomSheetHandler.hide()
                            },
                            onDismiss = { bottomSheetHandler.hide() },
                        )
                    }
                },
                onDelete = {
                    mMSDLPlayerWrapper.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                    pendingDelete = filter
                },
                dragIndicator = {
                    ReorderableDragHandle(
                        interactionSource = interactionSource,
                        scope = this,
                    )
                },
                interactionSource = interactionSource,
            )
        }
    }
}

@Composable
private fun DrawerFilterItem(
    filter: DrawerFilter,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    dragIndicator: @Composable () -> Unit,
    interactionSource: MutableInteractionSource,
) {
    val resources = LocalResources.current
    val count = filter.componentKeys.size
    PreferenceTemplate(
        title = { Text(text = filter.title) },
        description = { Text(text = resources.getQuantityString(R.plurals.apps_count, count, count)) },
        startWidget = { dragIndicator() },
        endWidget = {
            IconButton(
                onClick = onDelete,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.delete_label),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onClick,
        interactionSource = interactionSource,
    )
}

/** Picks the apps of one drawer filter. */
@Composable
fun SelectAppsForDrawerFilter(
    filterId: String,
    modifier: Modifier = Modifier,
    viewModel: FolderViewModel = viewModel(),
) {
    val prefs2 = preferenceManager2()
    val filtersAdapter = prefs2.drawerFilters.getAdapter()
    val filters = filtersAdapter.state.value
    val filter = filters.firstOrNull { it.id == filterId }
    val apps by appsState()
    val allFolderPackages by viewModel.allFolderPackages.collectAsStateWithLifecycle()
    // Apps already in a drawer folder or in another tab count as taken.
    val takenPackages = remember(allFolderPackages, filters, filterId) {
        allFolderPackages?.let { folderPackages ->
            folderPackages + filters.filter { it.id != filterId }
                .flatMap { it.componentKeys }
                .map { it.substringBefore("/") }
        }
    }

    if (filter == null) {
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        LaunchedEffect(Unit) { backDispatcher?.onBackPressed() }
        return
    }

    // The app picker of drawer folders works on a FolderEntry.
    val folderEntry = remember(filter) {
        FolderEntry(id = 0, title = filter.title, itemComponentKeys = filter.componentKeys)
    }
    SelectAppsForDrawerFolder(
        folderEntry = folderEntry,
        apps = apps,
        allFolderPackages = takenPackages,
        onUpdate = { _, componentKeys ->
            filtersAdapter.onChange(
                filtersAdapter.state.value.map {
                    if (it.id == filterId) it.copy(componentKeys = componentKeys) else it
                },
            )
        },
        modifier = modifier,
    )
}
