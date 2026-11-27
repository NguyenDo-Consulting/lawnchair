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
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.UserHandle
import android.util.Log
import com.android.launcher3.BaseActivity
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.launcher3.widget.util.WidgetSizes

/**
 * Creating and editing widget stacks from the launcher.
 *
 * [startCreate] and [startEdit] open [WidgetStackActivity]. When it returns, each checked widget is
 * added in the order it was checked: it gets a widget id, Android's "allow widgets" prompt is shown
 * if the launcher isn't allowed yet, and then the widget's setup screen if it has one. Backing out
 * of either skips only that widget. When the list is done, the stack is created or updated in one
 * go; if nothing was added to a new stack, nothing changes.
 *
 * Results come back through [onActivityResult], which LawnchairLauncher calls first.
 */
object WidgetStackFlow {

    private const val TAG = "WidgetStackFlow"

    // Far from Launcher3's own request codes.
    private const val REQUEST_PICK = 0x5710
    private const val REQUEST_BIND = 0x5711
    private const val REQUEST_CONFIGURE = 0x5712

    /** A widget picked in the list, waiting to be added. [index] is its place in the list's result. */
    private data class Pick(val provider: ComponentName, val user: UserHandle, val index: Int)

    /** What's being done: making a stack from [target], or editing [stack]. */
    private class Session(
        val target: LauncherAppWidgetInfo?,
        val stack: WidgetStackInfo?,
        val spanX: Int,
        val spanY: Int,
    ) {
        val queue = ArrayDeque<Pick>()

        /** Widgets added so far, by [Pick.index]. One skipped at its setup screen is missing. */
        val added = linkedMapOf<Int, LauncherAppWidgetInfo>()
        var current: Pick? = null
        var currentId = AppWidgetManager.INVALID_APPWIDGET_ID

        /** The order from the Edit screen, see [WidgetStackActivity.EXTRA_ORDER]. */
        var order: IntArray? = null

        /** The widget that was showing when Edit opened; it stays showing wherever it moved. */
        var activeId: Int? = null
    }

    private var session: Session? = null

    /** Opens the list to make a stack out of the widget [info]. */
    @JvmStatic
    fun startCreate(launcher: Launcher, info: LauncherAppWidgetInfo) {
        session = Session(target = info, stack = null, spanX = info.spanX, spanY = info.spanY)
        openList(launcher, WidgetStackActivity.MODE_CREATE, info.spanX, info.spanY, intArrayOf(info.appWidgetId))
    }

    /** Opens the stack's edit screen, where widgets are removed or added. */
    @JvmStatic
    fun startEdit(launcher: Launcher, stack: WidgetStackInfo) {
        session = Session(target = null, stack = stack, spanX = stack.spanX, spanY = stack.spanY)
        val ids = stack.widgets.map { it.appWidgetId }.toIntArray()
        openList(launcher, WidgetStackActivity.MODE_EDIT, stack.spanX, stack.spanY, ids)
    }

    private fun openList(launcher: Launcher, mode: Int, spanX: Int, spanY: Int, ids: IntArray) {
        val sizePx = WidgetSizes.getWidgetSizePx(launcher.deviceProfile, spanX, spanY)
        val density = launcher.resources.displayMetrics.density
        val intent = WidgetStackActivity.createIntent(
            context = launcher,
            mode = mode,
            spanX = spanX,
            spanY = spanY,
            widthDp = sizePx.width / density,
            heightDp = sizePx.height / density,
            widgetIds = ids,
        )
        launcher.startActivityForResult(intent, REQUEST_PICK)
    }

    /** A result waiting for the launcher to finish resuming, see [onActivityResult]. */
    private var pendingResult: ((Launcher) -> Unit)? = null

    /**
     * Handles the results of this flow. Returns false for any other request.
     *
     * A result arrives before the launcher has resumed. QuickstepLauncher cancels any request still
     * pending once it has (handlePendingActivityRequest), which would cancel the next setup screen
     * if it were opened right away. So each result is handled after that, see
     * [onLauncherDeferredResumed].
     */
    @JvmStatic
    fun onActivityResult(launcher: Launcher, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_PICK && requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE) {
            return false
        }
        val step = { l: Launcher -> handleResult(l, requestCode, resultCode, data) }
        if (launcher.activityFlags and BaseActivity.ACTIVITY_STATE_DEFERRED_RESUMED != 0) {
            launcher.dragLayer.post { step(launcher) }
        } else {
            pendingResult = step
        }
        return true
    }

    /** Called by LawnchairLauncher once the launcher has resumed, after Launcher3's own checks. */
    @JvmStatic
    fun onLauncherDeferredResumed(launcher: Launcher) {
        val step = pendingResult ?: return
        pendingResult = null
        launcher.dragLayer.post { step(launcher) }
    }

    private fun handleResult(launcher: Launcher, requestCode: Int, resultCode: Int, data: Intent?) {
        val s = session ?: return
        when (requestCode) {
            REQUEST_PICK -> onListResult(launcher, s, resultCode, data)
            REQUEST_BIND -> {
                val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, s.currentId) ?: s.currentId
                if (resultCode == Activity.RESULT_OK) configure(launcher, s, id) else skipCurrent(launcher, s, id)
            }
            REQUEST_CONFIGURE -> {
                // The holder may have swapped in a fresh id if the first one went bad.
                val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, s.currentId) ?: s.currentId
                if (resultCode == Activity.RESULT_OK) addCurrent(launcher, s, id) else skipCurrent(launcher, s, id)
            }
        }
    }

    private fun onListResult(launcher: Launcher, s: Session, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            session = null
            return
        }
        val stack = s.stack
        if (stack != null) {
            s.activeId = stack.activeWidget()?.appWidgetId
            s.order = data.getIntArrayExtra(WidgetStackActivity.EXTRA_ORDER)
            val removed = data.getIntArrayExtra(WidgetStackActivity.EXTRA_REMOVED_IDS) ?: IntArray(0)
            stack.widgets.filter { it.appWidgetId in removed }.forEach {
                stack.widgets.remove(it)
                launcher.modelWriter.deleteWidgetInfo(it, launcher.appWidgetHolder, "removed from widget stack")
            }
        }
        val providers = data.getParcelableArrayListExtra<ComponentName>(WidgetStackActivity.EXTRA_ADD_PROVIDERS)
        val users = data.getParcelableArrayListExtra<UserHandle>(WidgetStackActivity.EXTRA_ADD_USERS)
        if (providers != null && users != null) {
            providers.zip(users).forEachIndexed { index, (provider, user) ->
                s.queue.addLast(Pick(provider, user, index))
            }
        }
        next(launcher, s)
    }

    /** Starts adding the next picked widget, or finishes when there are none left. */
    private fun next(launcher: Launcher, s: Session) {
        val pick = s.queue.removeFirstOrNull()
        if (pick == null) {
            finish(launcher, s)
            return
        }
        s.current = pick
        val id = launcher.appWidgetHolder.allocateAppWidgetId()
        s.currentId = id
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            next(launcher, s)
            return
        }
        val options = WidgetSizes.getWidgetSizeOptions(launcher, pick.provider, s.spanX, s.spanY)
        val manager = AppWidgetManager.getInstance(launcher)
        if (manager.bindAppWidgetIdIfAllowed(id, pick.user, pick.provider, options)) {
            configure(launcher, s, id)
        } else {
            // Android asks the user once whether the launcher may add widgets.
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, pick.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, pick.user)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
            launcher.startActivityForResult(intent, REQUEST_BIND)
        }
    }

    /** Opens the widget's setup screen if it has one that must be filled in, else adds it. */
    private fun configure(launcher: Launcher, s: Session, id: Int) {
        s.currentId = id
        val info = AppWidgetManager.getInstance(launcher).getAppWidgetInfo(id)
        if (info == null) {
            skipCurrent(launcher, s, id)
            return
        }
        val launcherInfo = LauncherAppWidgetProviderInfo.fromProviderInfo(launcher, info)
        if (launcherInfo.configure != null && !launcherInfo.isConfigurationOptional) {
            launcher.appWidgetHolder.startConfigActivity(launcher, id, REQUEST_CONFIGURE)
        } else {
            addCurrent(launcher, s, id)
        }
    }

    private fun addCurrent(launcher: Launcher, s: Session, id: Int) {
        val pick = s.current
        if (pick != null) {
            val info = LauncherAppWidgetInfo(id, pick.provider).apply {
                user = pick.user
                spanX = s.spanX
                spanY = s.spanY
                minSpanX = s.spanX
                minSpanY = s.spanY
            }
            s.added[pick.index] = info
        }
        next(launcher, s)
    }

    private fun skipCurrent(launcher: Launcher, s: Session, id: Int) {
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) launcher.appWidgetHolder.deleteAppWidgetId(id)
        next(launcher, s)
    }

    private fun finish(launcher: Launcher, s: Session) {
        session = null
        try {
            val target = s.target
            val stack = s.stack
            when {
                target != null -> if (s.added.isNotEmpty()) createStack(launcher, target, s.added.values.toList())
                stack != null -> updateStack(launcher, stack, s)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widget stack", e)
        }
    }

    /** Turns [target] into a stack, in its place and at its size, with [added] after it. */
    private fun createStack(launcher: Launcher, target: LauncherAppWidgetInfo, added: List<LauncherAppWidgetInfo>) {
        val writer = launcher.modelWriter
        val workspace = launcher.workspace
        val oldView = workspace.getWidgetForAppWidgetId(target.appWidgetId)

        val stack = WidgetStackInfo().apply { setSpan(target.spanX, target.spanY) }
        val pos = launcher.cellPosMapper.mapModelToPresenter(target)
        writer.addItemToDatabase(stack, Favorites.CONTAINER_DESKTOP, pos.screenId, pos.cellX, pos.cellY)

        target.rank = 0
        writer.moveItemInDatabase(target, stack.id, 0, 0, 0)
        stack.widgets.add(target)
        addWidgets(launcher, stack, added)

        if (oldView != null) workspace.removeWorkspaceItem(oldView)
        bindStackView(launcher, stack)
    }

    /**
     * Puts [stack]'s widgets and the ones added in the order from the Edit screen, then dissolves or
     * removes the stack if one or no widgets are left.
     */
    private fun updateStack(launcher: Launcher, stack: WidgetStackInfo, s: Session) {
        val writer = launcher.modelWriter

        // The Edit screen's order; anything it doesn't name (it always should) goes at the end.
        val existing = stack.widgets.associateBy { it.appWidgetId }
        val ordered = mutableListOf<LauncherAppWidgetInfo>()
        s.order?.forEach { slot ->
            val widget = if (slot > 0) existing[slot] else s.added[-slot - 1]
            if (widget != null && widget !in ordered) ordered.add(widget)
        }
        stack.widgets.filterTo(ordered) { it !in ordered }
        s.added.values.filterTo(ordered) { it !in ordered }

        val added = s.added.values.toSet()
        stack.widgets.clear()
        ordered.forEachIndexed { index, widget ->
            if (widget in added) {
                addWidgets(launcher, stack, listOf(widget))
            } else {
                if (widget.rank != index) {
                    widget.rank = index
                    writer.moveItemInDatabase(widget, stack.id, 0, 0, 0)
                }
                stack.widgets.add(widget)
            }
        }
        WidgetStacks.settle(launcher, stack, s.activeId)
    }

    private fun addWidgets(launcher: Launcher, stack: WidgetStackInfo, added: List<LauncherAppWidgetInfo>) {
        val writer = launcher.modelWriter
        added.forEach { widget ->
            widget.rank = stack.widgets.size
            writer.addItemToDatabase(widget, stack.id, 0, 0, 0)
            stack.widgets.add(widget)
            val info = AppWidgetManager.getInstance(launcher).getAppWidgetInfo(widget.appWidgetId)
            WidgetSizes.updateWidgetSizeRangesAsync(widget.appWidgetId, info, launcher, stack.spanX, stack.spanY)
        }
    }

    private fun bindStackView(launcher: Launcher, stack: WidgetStackInfo) {
        val view = launcher.itemInflater.inflateItem(stack) ?: return
        launcher.workspace.addInScreen(view, stack)
    }
}
