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

package app.lawnchair.widgetstack

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import app.lawnchair.ui.preferences.components.reorderable.ObserveReorderHapticFeedback
import app.lawnchair.ui.preferences.components.reorderable.ReorderHapticFeedbackType
import app.lawnchair.ui.preferences.components.reorderable.rememberReorderHapticFeedback
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The full-screen widget list and the stack's Edit screen. See [WidgetStackFlow].
 *
 * - [MODE_CREATE] opens the list straight away; Add returns the checked widgets.
 * - [MODE_EDIT] opens the stack's widgets, each with a remove button on the left and a drag handle
 *   on the right (kept far apart so a drag can't remove one by accident), and Add widgets opens the
 *   list. Leaving the Edit screen returns what was removed and added, and the new order.
 *
 * The list only shows widgets that fit the stack's size, grouped by app. Each app is one row that
 * opens to show its widgets, one app at a time (every match is open while searching); each widget
 * has a checkbox on the right.
 * Widgets are returned in the order they were checked. On wide screens the previews flow into more
 * columns; folding or unfolding only re-lays it out (see the manifest).
 */
class WidgetStackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = intent.getIntExtra(EXTRA_MODE, MODE_CREATE)
        val spanX = intent.getIntExtra(EXTRA_SPAN_X, 1)
        val spanY = intent.getIntExtra(EXTRA_SPAN_Y, 1)
        val size = PreviewSize(
            intent.getFloatExtra(EXTRA_WIDTH_DP, 200f).dp,
            intent.getFloatExtra(EXTRA_HEIGHT_DP, 100f).dp,
        )
        val currentIds = intent.getIntArrayExtra(EXTRA_WIDGET_IDS) ?: IntArray(0)
        setContent {
            LawnchairTheme {
                EdgeToEdge()
                WidgetStackScreen(
                    mode = mode,
                    spanX = spanX,
                    spanY = spanY,
                    size = size,
                    currentIds = currentIds,
                    onDone = ::finishWith,
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                )
            }
        }
    }

    /**
     * Returns what changed. [order] is the stack's order on screen (empty when making a stack): a
     * widget already in the stack is its widget id, and an added one is -(its place in [added] + 1).
     */
    private fun finishWith(removed: List<Int>, added: List<WidgetEntry>, order: IntArray) {
        val currentIds = intent.getIntArrayExtra(EXTRA_WIDGET_IDS) ?: IntArray(0)
        val reordered = order.isNotEmpty() && !order.contentEquals(currentIds)
        if (removed.isEmpty() && added.isEmpty() && !reordered) {
            setResult(Activity.RESULT_CANCELED)
        } else {
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(EXTRA_REMOVED_IDS, removed.toIntArray())
                    .putExtra(EXTRA_ORDER, order)
                    .putParcelableArrayListExtra(EXTRA_ADD_PROVIDERS, ArrayList(added.map { it.provider }))
                    .putParcelableArrayListExtra(EXTRA_ADD_USERS, ArrayList(added.map { it.user })),
            )
        }
        finish()
    }

    companion object {
        const val MODE_CREATE = 0
        const val MODE_EDIT = 1

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_SPAN_X = "span_x"
        private const val EXTRA_SPAN_Y = "span_y"
        private const val EXTRA_WIDTH_DP = "width_dp"
        private const val EXTRA_HEIGHT_DP = "height_dp"
        private const val EXTRA_WIDGET_IDS = "widget_ids"
        const val EXTRA_REMOVED_IDS = "removed_ids"
        const val EXTRA_ORDER = "order"
        const val EXTRA_ADD_PROVIDERS = "add_providers"
        const val EXTRA_ADD_USERS = "add_users"

        fun createIntent(
            context: Context,
            mode: Int,
            spanX: Int,
            spanY: Int,
            widthDp: Float,
            heightDp: Float,
            widgetIds: IntArray,
        ): Intent = Intent(context, WidgetStackActivity::class.java)
            .putExtra(EXTRA_MODE, mode)
            .putExtra(EXTRA_SPAN_X, spanX)
            .putExtra(EXTRA_SPAN_Y, spanY)
            .putExtra(EXTRA_WIDTH_DP, widthDp)
            .putExtra(EXTRA_HEIGHT_DP, heightDp)
            .putExtra(EXTRA_WIDGET_IDS, widgetIds)
    }
}

/** The widget's size on the home screen, so previews have its real shape. */
internal data class PreviewSize(val width: Dp, val height: Dp)

/** A widget in the list, or one already in the stack ([widgetId] set). */
internal data class WidgetEntry(
    val key: String,
    val provider: ComponentName,
    val user: UserHandle,
    val label: String,
    val description: String?,
    val info: AppWidgetProviderInfo,
    val widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
)

internal data class AppGroup(
    val key: String,
    val label: String,
    val icon: ImageBitmap?,
    val widgets: List<WidgetEntry>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetStackScreen(
    mode: Int,
    spanX: Int,
    spanY: Int,
    size: PreviewSize,
    currentIds: IntArray,
    onDone: (removed: List<Int>, added: List<WidgetEntry>, order: IntArray) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var showingList by rememberSaveable { mutableStateOf(mode == WidgetStackActivity.MODE_CREATE) }
    val removed = remember { mutableStateListOf<Int>() }
    // Added widgets in the order they were checked, which is the order their setup screens open.
    val pendingAdds = remember { mutableStateListOf<WidgetEntry>() }
    // Everything on the Edit screen, in the order shown (and dragged into).
    val rows = remember { mutableStateListOf<WidgetEntry>() }
    var nextNewKey by remember { mutableIntStateOf(0) }

    // The stack's widgets (when making a stack, the widget it starts from). Only shown when editing,
    // but the list also uses them to mark what's in the stack.
    LaunchedEffect(currentIds) {
        val current = withContext(Dispatchers.IO) { loadCurrent(context, currentIds) }
        rows.addAll(0, current)
    }

    if (showingList) {
        WidgetList(
            spanX = spanX,
            spanY = spanY,
            size = size,
            // Everything on the Edit screen counts, so it matches what the stack will end up with.
            inStack = rows.groupingBy { listKey(it.provider, it.user) }.eachCount(),
            onBack = {
                if (mode == WidgetStackActivity.MODE_CREATE) onCancel() else showingList = false
            },
            onAdd = { checked ->
                if (mode == WidgetStackActivity.MODE_CREATE) {
                    onDone(emptyList(), checked, IntArray(0))
                } else {
                    // Each added row gets its own key, so adding the same widget twice can't clash.
                    val fresh = checked.map { it.copy(key = "new:${nextNewKey++}") }
                    pendingAdds.addAll(fresh)
                    rows.addAll(fresh)
                    showingList = false
                }
            },
        )
    } else {
        val leave = {
            val order = rows.map { entry ->
                if (entry.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    entry.widgetId
                } else {
                    -(pendingAdds.indexOf(entry) + 1)
                }
            }.toIntArray()
            onDone(removed.toList(), pendingAdds.toList(), order)
        }
        BackHandler(onBack = leave)
        val listState = rememberLazyListState()
        val haptic = rememberReorderHapticFeedback()
        val reorderState = rememberReorderableLazyListState(listState) { from, to ->
            // By key, so the Add widgets button below the rows is never a drop target.
            val fromIndex = rows.indexOfFirst { it.key == from.key }
            val toIndex = rows.indexOfFirst { it.key == to.key }
            if (fromIndex >= 0 && toIndex >= 0) {
                rows.add(toIndex, rows.removeAt(fromIndex))
                haptic.performHapticFeedback(ReorderHapticFeedbackType.MOVE)
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.widget_stack_edit_title)) },
                    navigationIcon = {
                        IconButton(onClick = leave) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.key }) { entry ->
                    ReorderableItem(reorderState, key = entry.key) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "lift")
                        Surface(shape = RoundedCornerShape(16.dp), shadowElevation = elevation) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Remove on the far left, the drag handle on the far right: a drag
                                // can't land on remove by accident.
                                IconButton(onClick = {
                                    rows.remove(entry)
                                    if (entry.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                        removed.add(entry.widgetId)
                                    } else {
                                        pendingAdds.remove(entry)
                                    }
                                }) {
                                    Icon(
                                        Icons.Rounded.RemoveCircleOutline,
                                        contentDescription = stringResource(
                                            R.string.widget_stack_remove_widget,
                                            entry.label,
                                        ),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.width(120.dp), contentAlignment = Alignment.Center) {
                                    WidgetPreview(entry.info, size, maxWidth = 120.dp, maxHeight = 80.dp)
                                }
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    text = entry.label,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(8.dp))
                                val dragInteraction = remember { MutableInteractionSource() }
                                ObserveReorderHapticFeedback(dragInteraction)
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(interactionSource = dragInteraction),
                                    interactionSource = dragInteraction,
                                ) {
                                    Icon(
                                        Icons.Rounded.DragHandle,
                                        contentDescription = stringResource(
                                            R.string.widget_stack_reorder_widget,
                                            entry.label,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "add_widgets") {
                    OutlinedButton(
                        onClick = { showingList = true },
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.widget_stack_add_widgets))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetList(
    spanX: Int,
    spanY: Int,
    size: PreviewSize,
    inStack: Map<String, Int>,
    onBack: () -> Unit,
    onAdd: (List<WidgetEntry>) -> Unit,
) {
    val context = LocalContext.current
    val groups by produceState<List<AppGroup>?>(null, spanX, spanY) {
        value = withContext(Dispatchers.IO) { loadFitting(context, spanX, spanY) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    // Keys in the order they were checked; that's the order they're added to the stack.
    val checked = remember { mutableStateListOf<String>() }
    // The one app whose widgets are showing. While searching, every match shows instead.
    var openApp by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.widget_stack_search)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        val all = groups.orEmpty().flatMap { it.widgets }.associateBy { it.key }
                        onAdd(checked.mapNotNull { all[it] })
                    },
                    enabled = checked.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.widget_stack_add_count, checked.size))
                }
            }
        },
    ) { padding ->
        val loaded = groups
        if (loaded == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val filtered = filterGroups(loaded, query)
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.widget_stack_none_fit))
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            // One column on a phone, more as the screen gets wider.
            columns = GridCells.Adaptive(minSize = 300.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val searching = query.isNotBlank()
            val fullGroups = loaded.associateBy { it.key }
            filtered.forEach { group ->
                val isOpen = searching || openApp == group.key
                item(key = "app:" + group.key, span = { GridItemSpan(maxLineSpan) }) {
                    // Counts come from the whole app, not just the widgets matching the search.
                    val all = fullGroups[group.key]?.widgets ?: group.widgets
                    val selected = all.count { it.key in checked }
                    val stacked = all.sumOf { inStack[it.key] ?: 0 }
                    val subtitle = listOfNotNull(
                        pluralStringResource(R.plurals.widget_stack_app_widget_count, all.size, all.size),
                        if (stacked > 0) stringResource(R.string.widget_stack_app_in_stack, stacked) else null,
                        if (selected > 0) stringResource(R.string.widget_stack_app_selected, selected) else null,
                    ).joinToString(" · ")
                    val state = stringResource(
                        if (isOpen) R.string.widget_stack_app_expanded else R.string.widget_stack_app_collapsed,
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !searching) {
                                openApp = if (openApp == group.key) null else group.key
                            }
                            .semantics { stateDescription = state }
                            .animateItem(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            group.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(group.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!searching) {
                                Icon(
                                    if (isOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                }
                if (!isOpen) return@forEach
                items(group.widgets, key = { it.key }) { entry ->
                    val isChecked = entry.key in checked
                    val toggle = { if (isChecked) checked.remove(entry.key) else checked.add(entry.key) }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { toggle() }
                            .animateItem(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                WidgetPreview(entry.info, size, maxWidth = 280.dp, maxHeight = 160.dp)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.label, style = MaterialTheme.typography.bodyLarge)
                                    if ((inStack[entry.key] ?: 0) > 0) {
                                        Text(
                                            stringResource(R.string.widget_stack_in_stack),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    entry.description?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Checkbox(checked = isChecked, onCheckedChange = { toggle() })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A widget's preview at the stack's shape, scaled down to fit. Uses the widget's preview layout
 * when it has one, else its preview image, else its icon.
 */
@Composable
private fun WidgetPreview(info: AppWidgetProviderInfo, size: PreviewSize, maxWidth: Dp, maxHeight: Dp) {
    val scale = minOf(maxWidth / size.width, maxHeight / size.height, 1f)
    Box(
        modifier = Modifier
            .size(size.width * scale, size.height * scale)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val usesLayout = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.previewLayout != 0
        AndroidView(
            factory = { context ->
                if (usesLayout) {
                    AppWidgetHostView(context).apply {
                        setAppWidget(AppWidgetManager.INVALID_APPWIDGET_ID, info)
                        updateAppWidget(RemoteViews(info.provider.packageName, info.previewLayout))
                    }
                } else {
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        val density = context.resources.displayMetrics.densityDpi
                        setImageDrawable(info.loadPreviewImage(context, density) ?: info.loadIcon(context, density))
                    }
                }
            },
            // Laid out at the real size, then scaled, so a preview layout isn't squeezed.
            modifier = Modifier
                .requiredSize(size.width, size.height)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

private fun filterGroups(groups: List<AppGroup>, query: String): List<AppGroup> {
    val q = query.trim()
    if (q.isEmpty()) return groups
    return groups.mapNotNull { group ->
        if (group.label.contains(q, ignoreCase = true)) {
            group
        } else {
            group.widgets.filter { it.label.contains(q, ignoreCase = true) }
                .takeIf { it.isNotEmpty() }
                ?.let { group.copy(widgets = it) }
        }
    }
}

/** A widget's key in the list: one per widget kind and profile. */
private fun listKey(provider: ComponentName, user: UserHandle): String =
    provider.flattenToString() + "/" + user.hashCode()

/** The stack's current widgets, in order. */
private fun loadCurrent(context: Context, ids: IntArray): List<WidgetEntry> {
    val manager = AppWidgetManager.getInstance(context)
    return ids.toList().mapNotNull { id ->
        val info = manager.getAppWidgetInfo(id) ?: return@mapNotNull null
        WidgetEntry(
            key = "id:$id",
            provider = info.provider,
            user = info.profile,
            label = info.loadLabel(context.packageManager) ?: info.provider.className,
            description = null,
            info = info,
            widgetId = id,
        )
    }
}

/** Every home screen widget that fits [spanX] by [spanY], grouped by app and sorted by name. */
private fun loadFitting(context: Context, spanX: Int, spanY: Int): List<AppGroup> {
    val manager = AppWidgetManager.getInstance(context)
    val pm = context.packageManager
    val users = context.getSystemService(UserManager::class.java)?.userProfiles.orEmpty()
    val iconSize = (32 * context.resources.displayMetrics.density).toInt()
    return users.flatMap { user -> manager.getInstalledProvidersForProfile(user) }
        .filter { info ->
            info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 &&
                !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER != 0) &&
                WidgetStacks.fits(LauncherAppWidgetProviderInfo.fromProviderInfo(context, info), spanX, spanY)
        }
        .groupBy { it.provider.packageName to it.profile }
        .map { (key, infos) ->
            val appInfo = infos.first().providerInfo.applicationInfo
            val icon = runCatching {
                pm.getUserBadgedIcon(appInfo.loadIcon(pm), key.second).toBitmap(iconSize, iconSize).asImageBitmap()
            }.getOrNull()
            AppGroup(
                key = key.first + "/" + key.second.hashCode(),
                label = appInfo.loadLabel(pm).toString(),
                icon = icon,
                widgets = infos.map { info ->
                    WidgetEntry(
                        key = listKey(info.provider, info.profile),
                        provider = info.provider,
                        user = info.profile,
                        label = info.loadLabel(pm) ?: info.provider.className,
                        description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            info.loadDescription(context)?.toString()
                        } else {
                            null
                        },
                        info = info,
                    )
                }.sortedBy { it.label.lowercase() },
            )
        }
        .sortedBy { it.label.lowercase() }
}
