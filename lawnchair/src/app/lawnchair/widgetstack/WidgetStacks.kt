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

import android.appwidget.AppWidgetProviderInfo
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.Utilities
import com.android.launcher3.Workspace
import com.android.launcher3.model.LoaderCursor
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.model.ModelWriter
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.util.IntArray
import com.android.launcher3.util.IntSparseArrayMap
import com.android.launcher3.widget.LauncherAppWidgetHostView
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.launcher3.widget.LauncherWidgetHolder
import java.util.function.Predicate

/**
 * Widget stacks: the item type and the helpers the Launcher3 loader and grid migration call into.
 * See [WidgetStackInfo] for how a stack is stored.
 */
object WidgetStacks {

    private const val TAG = "WidgetStacks"

    /** Item type of a stack row. Far above Launcher3's own types so a new upstream one can't clash. */
    const val ITEM_TYPE_WIDGET_STACK = 1000

    /**
     * Whether a widget row with this container may belong to a stack. The loader keeps such rows,
     * and [finalizeLoad] drops the ones whose container turns out not to be a stack.
     */
    @JvmStatic
    fun isPossibleStackChild(container: Int): Boolean = container >= 0

    /**
     * Whether a widget can be shown at [spanX] by [spanY]: in each direction it can resize in, the
     * size is within its smallest and largest; in a direction it can't, it must be its own size.
     */
    @JvmStatic
    fun fits(info: LauncherAppWidgetProviderInfo, spanX: Int, spanY: Int): Boolean {
        val resizesX = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val resizesY = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val fitsX = if (resizesX) spanX in info.minSpanX..info.maxSpanX else spanX == info.spanX
        val fitsY = if (resizesY) spanY in info.minSpanY..info.maxSpanY else spanY == info.spanY
        return fitsX && fitsY
    }

    /** Builds a stack from the loader's current row. */
    @JvmStatic
    fun loadStack(c: LoaderCursor): WidgetStackInfo {
        val info = WidgetStackInfo()
        c.applyCommonProperties(info)
        info.setSpan(c.spanX, c.spanY)
        info.activeIndex = c.options
        c.markRestored()
        return info
    }

    /**
     * Runs after every row is loaded. Puts each stack's widgets in it, in order, and tidies up:
     * a stack with no widgets is deleted, a stack with one widget becomes that widget in the same
     * place and size, and a widget whose container isn't a stack is deleted.
     *
     * @return the ids of the items to drop from the loaded items.
     */
    @JvmStatic
    fun finalizeLoad(loadedItems: IntSparseArrayMap<ItemInfo>, db: ModelDbController): IntArray {
        val removed = IntArray()
        val stacks = mutableListOf<WidgetStackInfo>()
        val orphans = mutableListOf<LauncherAppWidgetInfo>()
        for (item in loadedItems) {
            when {
                item is WidgetStackInfo -> stacks.add(item)
                item is LauncherAppWidgetInfo && isPossibleStackChild(item.container) -> {
                    val stack = loadedItems[item.container] as? WidgetStackInfo
                    if (stack != null) stack.widgets.add(item) else orphans.add(item)
                }
            }
        }

        try {
            for (stack in stacks) {
                stack.widgets.sortBy { it.rank }
                stack.widgets.forEachIndexed { index, widget -> widget.rank = index }
                when (stack.widgets.size) {
                    0 -> {
                        db.delete(Favorites._ID + "=" + stack.id, null)
                        removed.add(stack.id)
                    }
                    1 -> {
                        dissolveOnLoad(stack, stack.widgets[0], db)
                        removed.add(stack.id)
                    }
                    else -> stack.clampActiveIndex()
                }
            }
            if (orphans.isNotEmpty()) {
                val ids = IntArray()
                orphans.forEach { ids.add(it.id) }
                db.delete(Utilities.createDbSelectionQuery(Favorites._ID, ids), null)
                removed.addAll(ids)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to tidy up widget stacks", e)
        }
        return removed
    }

    /** Turns a one-widget stack back into that widget, in the stack's place and at its size. */
    private fun dissolveOnLoad(stack: WidgetStackInfo, widget: LauncherAppWidgetInfo, db: ModelDbController) {
        widget.container = Favorites.CONTAINER_DESKTOP
        widget.screenId = stack.screenId
        widget.cellX = stack.cellX
        widget.cellY = stack.cellY
        widget.spanX = stack.spanX
        widget.spanY = stack.spanY
        widget.rank = 0
        val values = ContentValues().apply {
            put(Favorites.CONTAINER, widget.container)
            put(Favorites.SCREEN, widget.screenId)
            put(Favorites.CELLX, widget.cellX)
            put(Favorites.CELLY, widget.cellY)
            put(Favorites.SPANX, widget.spanX)
            put(Favorites.SPANY, widget.spanY)
            put(Favorites.RANK, widget.rank)
        }
        db.update(values, Favorites._ID + "=" + widget.id, null)
        db.delete(Favorites._ID + "=" + stack.id, null)
    }

    /**
     * Re-makes the view of a widget that's in a stack, inside the stack. Launcher3 re-makes a
     * widget's view by adding it to its page, which a widget in a stack doesn't have.
     *
     * @return true if [widget] is in a stack, so the caller must not add it to a page.
     */
    @JvmStatic
    fun bindInStack(launcher: Launcher, widget: LauncherAppWidgetInfo): Boolean {
        if (!isPossibleStackChild(widget.container)) return false
        val stackView = launcher.workspace.getViewByItemId(widget.container) as? WidgetStackView
            ?: return true
        val view = launcher.itemInflater.inflateItem(widget) ?: return true
        stackView.replaceWidgetView(widget, view)
        return true
    }

    /**
     * Finds the view of the widget with [appWidgetId] inside a stack. Launcher3 looks widgets up
     * only on pages, so without this, finishing a restored widget's setup (or anything else that
     * looks a widget up by id) gives up on a widget in a stack and leaves its "set up" placeholder.
     */
    @JvmStatic
    fun findWidgetView(workspace: Workspace<*>, appWidgetId: Int): LauncherAppWidgetHostView? {
        var found: LauncherAppWidgetHostView? = null
        workspace.mapOverItems { _, view ->
            found = (view as? WidgetStackView)?.findWidgetView(appWidgetId)
            found != null
        }
        return found
    }

    /**
     * Takes widgets matching [matcher] out of every stack on the home screen, then settles each
     * stack (see [settle]). Launcher3 clears matching items only from pages and folders, so without
     * this an uninstalled app's widget, or one Android says is gone, stays in its stack until the
     * launcher restarts.
     *
     * @param deleteFromDb whether to delete the widgets from the database too. Not when an app is
     *   uninstalled: Launcher3's model has already deleted them then.
     */
    @JvmStatic
    fun removeWidgets(launcher: Launcher, matcher: Predicate<ItemInfo>, deleteFromDb: Boolean, reason: String) {
        val stackViews = mutableListOf<WidgetStackView>()
        launcher.workspace.mapOverItems { _, view ->
            if (view is WidgetStackView) stackViews.add(view)
            false
        }
        for (stackView in stackViews) {
            val stack = stackView.info
            val gone = stack.widgets.filter { matcher.test(it) }
            if (gone.isEmpty()) continue
            val showingId = stack.activeWidget()?.appWidgetId
            stack.widgets.removeAll(gone)
            if (deleteFromDb) {
                gone.forEach { launcher.modelWriter.deleteWidgetInfo(it, launcher.appWidgetHolder, reason) }
            }
            settle(launcher, stack, showingId)
        }
    }

    /**
     * Puts the home screen right after [stack]'s widgets changed, keeping the widget [showingId]
     * showing if it's still there:
     * - none left: the stack is removed.
     * - one left: it becomes a plain widget in the stack's place and size; nothing else moves.
     * - otherwise: the order's gaps are closed and the stack is shown again.
     */
    @JvmStatic
    fun settle(launcher: Launcher, stack: WidgetStackInfo, showingId: Int?) {
        val writer = launcher.modelWriter
        val workspace = launcher.workspace
        val stackView = workspace.getViewByItemId(stack.id)
        when (stack.widgets.size) {
            0 -> launcher.removeItem(stackView, stack, true, "widget stack emptied")
            1 -> {
                val widget = stack.widgets[0]
                val pos = launcher.cellPosMapper.mapModelToPresenter(stack)
                widget.rank = 0
                writer.modifyItemInDatabase(
                    widget, Favorites.CONTAINER_DESKTOP, pos.screenId, pos.cellX, pos.cellY,
                    stack.spanX, stack.spanY,
                )
                stack.widgets.clear()
                writer.deleteItemFromDatabase(stack, "widget stack down to one widget")
                if (stackView != null) workspace.removeWorkspaceItem(stackView)
                launcher.itemInflater.inflateItem(widget)?.let { workspace.addInScreen(it, widget) }
            }
            else -> {
                stack.widgets.forEachIndexed { index, widget ->
                    if (widget.rank != index) {
                        widget.rank = index
                        writer.moveItemInDatabase(widget, stack.id, 0, 0, 0)
                    }
                }
                val before = stack.activeIndex
                val showing = stack.widgets.indexOfFirst { it.appWidgetId == showingId }
                if (showing >= 0) stack.activeIndex = showing
                stack.clampActiveIndex()
                if (stack.activeIndex != before) writer.updateItemInDatabase(stack)
                if (stackView != null) workspace.removeWorkspaceItem(stackView)
                launcher.itemInflater.inflateItem(stack)?.let { workspace.addInScreen(it, stack) }
            }
        }
    }

    /**
     * Deletes [stack] and every widget in it, releasing their widget ids. The deletes go through
     * the model writer's queue, so while an Undo is offered nothing is final and Undo brings the
     * whole stack back, widget settings and all.
     */
    @JvmStatic
    fun deleteStack(
        writer: ModelWriter,
        holder: LauncherWidgetHolder?,
        stack: WidgetStackInfo,
        reason: String?,
    ) {
        stack.widgets.forEach { writer.deleteWidgetInfo(it, holder, reason) }
        writer.deleteItemFromDatabase(stack, reason)
    }

    /**
     * Identifies a stack during a grid size migration, so the same stack in the old and new grid
     * is recognized as one. Built from its widgets' ids, in order. Null if the stack has no widgets.
     */
    @JvmStatic
    fun migrationKey(db: SQLiteDatabase, tableName: String, stackId: Int): String? {
        val ids = mutableListOf<Int>()
        db.query(
            tableName,
            arrayOf(Favorites.APPWIDGET_ID),
            Favorites.CONTAINER + "=" + stackId,
            null,
            null,
            null,
            Favorites.RANK,
        ).use { c ->
            while (c.moveToNext()) ids.add(c.getInt(0))
        }
        return if (ids.isEmpty()) null else "widgetstack:" + ids.joinToString(",")
    }
}
