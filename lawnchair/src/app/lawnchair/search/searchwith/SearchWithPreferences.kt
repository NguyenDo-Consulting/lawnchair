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

package app.lawnchair.search.searchwith

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.search.searchwith.SearchWithOrder.toPrefString
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.components.reorderable.ReorderableDragHandle
import app.lawnchair.ui.preferences.components.reorderable.ReorderablePreferenceGroup
import app.lawnchair.ui.preferences.navigation.DockSearchProvider
import com.android.launcher3.R
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** NDShades: The checked search providers in order, for the subtitle of the "Search provider" entries. */
@Composable
fun searchProvidersSummary(): String {
    val entries = SearchWithOrder.parse(preferenceManager2().searchWithProviders.getAdapter().state.value)
    val checked = entries.filter { it.enabled }.map { stringResource(it.provider.label) }
    return if (checked.isEmpty()) stringResource(R.string.search_with_none) else checked.joinToString(", ")
}

/**
 * NDShades: The "Search provider" entry in the drawer search settings. The dock search settings
 * have one too, but hide it when the dock uses the Google search bar widget.
 */
@Composable
fun SearchWithPreferenceItem(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    ClickablePreference(
        label = stringResource(R.string.search_provider),
        subtitle = searchProvidersSummary(),
        modifier = modifier,
        onClick = { navController.navigate(DockSearchProvider) },
    )
}

/**
 * NDShades: The "Search provider" screen, in place of Lawnchair's pick-one list: picks which
 * providers the drawer search's "Search with" row shows, by checkbox, and their order, by dragging.
 */
@Composable
fun SearchWithPreferences(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adapter = preferenceManager2().searchWithProviders.getAdapter()
    val entries = SearchWithOrder.parse(adapter.state.value)

    // Null until checked, so nothing says "Not installed" before it's known
    val modes by produceState<Map<SearchWithProvider, SearchWithProvider.Mode>?>(null) {
        value = withContext(Dispatchers.IO) {
            SearchWithProvider.entries.associateWith { it.resolveMode(context) }
        }
    }

    PreferenceLayout(
        label = stringResource(R.string.search_provider),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        ReorderablePreferenceGroup(
            label = stringResource(R.string.search_with_providers_heading),
            items = entries,
            defaultList = SearchWithOrder.DEFAULT,
            onOrderChange = { adapter.onChange(it.toPrefString()) },
        ) { item, _, _ ->
            val interactionSource = remember { MutableInteractionSource() }
            val scope = this
            SearchWithProviderRow(
                entry = item,
                modes = modes,
                interactionSource = interactionSource,
                onCheckedChange = { checked ->
                    adapter.onChange(
                        entries.map { if (it.provider == item.provider) it.copy(enabled = checked) else it }
                            .toPrefString(),
                    )
                },
                dragHandle = {
                    ReorderableDragHandle(
                        scope = scope,
                        interactionSource = interactionSource,
                    )
                },
            )
        }
    }
}

@Composable
private fun SearchWithProviderRow(
    entry: SearchWithEntry,
    modes: Map<SearchWithProvider, SearchWithProvider.Mode>?,
    interactionSource: MutableInteractionSource,
    onCheckedChange: (Boolean) -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val provider = entry.provider
    val icon = remember(provider) {
        val appIcon = provider.packageName?.let { packageName ->
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
        appIcon ?: ContextCompat.getDrawable(context, provider.webIcon)
    }
    // Website-only providers need no note: they always open the website
    val description = when {
        modes == null || provider.isWebOnly -> null
        modes[provider] == SearchWithProvider.Mode.WEB -> R.string.search_with_opens_website
        else -> null
    }

    PreferenceTemplate(
        modifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = ripple(),
            onClick = { onCheckedChange(!entry.enabled) },
        ),
        contentModifier = Modifier.fillMaxHeight(),
        title = { Text(text = stringResource(provider.label)) },
        description = description?.let { { Text(text = stringResource(it)) } },
        startWidget = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dragHandle()
                Spacer(Modifier.width(8.dp))
                if (icon != null) {
                    Image(
                        painter = rememberDrawablePainter(drawable = icon),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                } else {
                    Spacer(Modifier.size(32.dp))
                }
            }
        },
        endWidget = {
            Checkbox(
                checked = entry.enabled,
                onCheckedChange = onCheckedChange,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
