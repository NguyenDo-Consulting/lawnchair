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

package app.lawnchair.allapps.drag

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import app.lawnchair.LawnchairLauncher
import app.lawnchair.allapps.filters.DrawerFilterState
import app.lawnchair.allapps.filters.DrawerFilterSwipeHelper
import app.lawnchair.allapps.filters.DrawerTabStrip
import app.lawnchair.allapps.filters.DrawerTabSwitcher
import app.lawnchair.preferences.PreferenceManager
import com.android.launcher3.BubbleTextView
import com.android.launcher3.R
import com.android.launcher3.DropTarget
import com.android.launcher3.DropTarget.DragObject
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.LauncherState
import com.android.launcher3.allapps.AllAppsRecyclerView
import com.android.launcher3.dragndrop.DragController
import com.android.launcher3.dragndrop.DragLayer
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ComponentKey

/**
 * Keeps the app drawer open while one of its apps is dragged, and handles the drop there:
 *
 * - Over a drawer folder: it opens after a moment and works as on the home screen. In a
 *   free-sorted folder the app goes where it's dropped, in a sorted one in its sorted place. The
 *   app leaves any other folder. Dropped on the folder before it opens, the app goes at the end.
 * - On another app (in "All", with custom drawer folders): the two make a new folder, which
 *   opens to be named.
 * - Anywhere else in the list: the app leaves the folder it was dragged out of, if any.
 * - In another tab than it started in: the app leaves that tab and joins this one ("All" only
 *   takes it out). Over a tab label (when labels are shown) that tab opens right away; holding
 *   near the left or right edge slides to the next tab at half a swipe's speed.
 * - Above the bottom of the search bar: the drawer closes and the drag goes on on the home
 *   screen as upstream, with Remove and Uninstall.
 *
 * Holding near the top or bottom of the list scrolls it. Only personal apps from the app list
 * or a drawer folder stay in the drawer; search results and work apps go home as upstream.
 */
class DrawerDropTarget(private val launcher: LawnchairLauncher) : DropTarget, DragController.DragListener {

    private sealed interface HoverTarget {
        val view: View
    }

    private class FolderTarget(override val view: FolderIcon, val folderId: Int) : HoverTarget

    private class AppTarget(override val view: BubbleTextView, val key: String) : HoverTarget

    private val organizer = DrawerOrganizer(launcher)
    private val prefs = PreferenceManager.getInstance(launcher)
    private val handler = Handler(Looper.getMainLooper())
    private val tmpRect = Rect()
    private val tmpLocation = IntArray(2)

    // The current drag, while it's in the drawer
    private var active = false
    private var handedOff = false
    private var handOffPending = false
    private var dragKey: String? = null
    private var originTab: String? = null
    private var originFolderId: Int? = null

    /** Dragging above this line (drag layer y) hands the drag to the home screen. */
    private var handOffY = 0

    private var hovered: HoverTarget? = null
    private var edgeDirection = 0
    private var scrollDirection = 0

    private var hiddenDropBar: View? = null

    /** Whether a drag is going on in the open drawer (not handed to the home screen). */
    val isDrawerDragActive: Boolean get() = active && !handedOff

    fun register() {
        launcher.dragController.addDragListener(this)
        // Added after the drop target bar, so this is asked first and the bar's buttons only
        // take drops once the drag is on the home screen.
        launcher.dragController.addDropTarget(this)
    }

    /** Whether a drag that's starting should keep the drawer open. */
    fun canKeepDrawerOpen(dragObject: DragObject, options: DragOptions): Boolean {
        if (options.isAccessibleDrag) return false
        if (!launcher.isInState(LauncherState.ALL_APPS)) return false
        val appsView = launcher.appsView ?: return false
        if (appsView.isSearching || personalRecyclerView() == null) return false
        val info = dragObject.dragInfo ?: return false
        if (info.itemType != ITEM_TYPE_APPLICATION || info.targetComponent == null) return false
        // Drawer folders and tabs only hold personal apps.
        if (info.user != Process.myUserHandle()) return false
        val source = dragObject.dragSource
        return source === appsView || (source is Folder && source.isInAppDrawer)
    }

    // DragController.DragListener

    override fun onDragStart(dragObject: DragObject, options: DragOptions) {
        active = canKeepDrawerOpen(dragObject, options)
        if (!active) return
        handedOff = false
        handOffPending = false
        val info = dragObject.dragInfo
        dragKey = ComponentKey(info.targetComponent, info.user).toString()
        originTab = DrawerFilterState.activeFilterId.value
        // Auto-category folders (no custom drawer folders) aren't saved anywhere.
        originFolderId = (dragObject.dragSource as? Folder)?.info?.id
            ?.takeIf { it != ItemInfo.NO_ID && prefs.drawerList.get() }
        handOffY = computeHandOffY()
        hideDropTargetBar()
    }

    override fun onDragEnd() {
        if (!active) return
        active = false
        handedOff = false
        handOffPending = false
        handler.removeCallbacksAndMessages(null)
        launcher.dragLayer.removeCallbacks(scrollRunnable)
        edgeDirection = 0
        scrollDirection = 0
        setHover(null)
        // Never shown: leave it as it would be after fading out.
        hiddenDropBar?.visibility = View.INVISIBLE
        hiddenDropBar = null
        dragKey = null
        originFolderId = null
    }

    /**
     * Saves an app dropped into an open drawer folder once the folder closes. Saving earlier would
     * redraw the folder's row in the drawer, and the open folder would lose its icon.
     */
    fun onDroppedInFolder(folder: Folder, item: ItemInfo) {
        val component = item.targetComponent ?: return
        val key = ComponentKey(component, item.user).toString()
        val folderId = folder.info.id
        if (folderId == ItemInfo.NO_ID) return
        val fromTab = originTab
        val toTab = DrawerFilterState.activeFilterId.value
        folder.addOnFolderStateChangedListener(object : Folder.OnFolderStateChangedListener {
            override fun onFolderStateChanged(newState: Int) {
                if (newState != Folder.STATE_CLOSED) return
                folder.removeOnFolderStateChangedListener(this)
                val keys = folder.info.getContents().mapNotNull { content ->
                    content.targetComponent?.let { ComponentKey(it, content.user).toString() }
                }.distinct()
                organizer.placeInFolder(key, folderId, keys, fromTab, toTab)
            }
        })
    }

    // DropTarget

    override fun isDropEnabled(): Boolean =
        isDrawerDragActive && launcher.isInState(LauncherState.ALL_APPS)

    override fun getHitRectRelativeToDragLayer(outRect: Rect) {
        val dragLayer = launcher.dragLayer
        outRect.set(0, 0, dragLayer.width, dragLayer.height)
    }

    // Coordinates stay in the drag layer's.
    override fun getDropView(): View? = null

    override fun onDragEnter(dragObject: DragObject) = Unit

    override fun onDragOver(dragObject: DragObject) {
        val x = dragObject.x
        val y = dragObject.y
        if (y < handOffY) {
            setEdge(0)
            setScroll(0)
            setHover(null)
            if (!handOffPending) {
                handOffPending = true
                // Not from inside this drag callback, which handing off would re-enter.
                handler.post(handOffRunnable)
            }
            return
        }
        // Over a tab label: that tab, right away.
        val tab = tabLabelAt(x, y)
        if (tab != null) {
            setEdge(0)
            setScroll(0)
            setHover(null)
            val appsView = launcher.appsView
            if (appsView != null && tab.filterId != DrawerFilterState.activeFilterId.value &&
                DrawerFilterSwipeHelper.of(appsView)?.isMoving != true
            ) {
                DrawerTabSwitcher.switchTo(appsView, tab.filterId)
            }
            return
        }
        setEdge(edgeDirectionAt(x))
        setScroll(scrollDirectionAt(y))
        setHover(if (edgeDirection == 0) findTarget(dragObject) else null)
    }

    override fun onDragExit(dragObject: DragObject) {
        setEdge(0)
        setScroll(0)
        setHover(null)
    }

    override fun acceptDrop(dragObject: DragObject): Boolean = isDrawerDragActive

    override fun onDrop(dragObject: DragObject, options: DragOptions) {
        val key = dragKey ?: return
        val target = findTarget(dragObject)
        val toTab = DrawerFilterState.activeFilterId.value
        val action: DrawerDropAction? = when (target) {
            is FolderTarget -> if (target.folderId == originFolderId) null else DrawerDropAction.MoveToFolder(target.folderId)
            is AppTarget -> DrawerDropAction.CreateFolder(target.key)
            null -> if (originFolderId != null) DrawerDropAction.RemoveFromFolder else null
        }
        val changed = action != null || toTab != originTab
        animateDrop(dragObject, target, changed)
        if (changed) organizer.apply(key, action, originTab, toTab)
    }

    override fun prepareAccessibilityDrop() = Unit

    // Handing off to the home screen

    /**
     * The bottom of the search bar when it's at the top of the drawer, otherwise the top of the
     * app list.
     */
    private fun computeHandOffY(): Int {
        val dragLayer = launcher.dragLayer
        val searchView = launcher.appsView?.searchView
        if (searchView != null && searchView.isShown && searchView.height > 0) {
            dragLayer.getViewRectRelativeToSelf(searchView, tmpRect)
            if (tmpRect.centerY() < dragLayer.height / 2) return tmpRect.bottom
        }
        val rv = personalRecyclerView() ?: return 0
        dragLayer.getViewRectRelativeToSelf(rv, tmpRect)
        return tmpRect.top
    }

    /** Closes the drawer and lets the drag go on to the home screen as upstream. */
    private val handOffRunnable = Runnable {
        if (!isDrawerDragActive) return@Runnable
        handedOff = true
        setEdge(0)
        setScroll(0)
        setHover(null)
        hiddenDropBar?.animate()?.alpha(1f)?.setDuration(FADE_MS)?.start()
        hiddenDropBar = null
        launcher.stateManager.goToState(LauncherState.SPRING_LOADED)
        launcher.dragController.forceTouchMove()
    }

    /**
     * Remove, Uninstall and Cancel only show once the drag is on the home screen. The bar fades
     * in when a drag starts, so this runs just after it.
     */
    private fun hideDropTargetBar() {
        val bar = launcher.dropTargetBar ?: return
        bar.animate().cancel()
        bar.alpha = 0f
        hiddenDropBar = bar
    }

    // Switching tabs: on a tab label, or at the left and right edges

    /** The personal tab label at drag layer ([x], [y]), when the tab labels are shown. */
    private fun tabLabelAt(x: Int, y: Int): DrawerTabStrip.PersonalTab? {
        val strip = launcher.appsView?.floatingHeaderView
            ?.findViewById<DrawerTabStrip>(R.id.drawer_tabs) ?: return null
        launcher.dragLayer.getLocationOnScreen(tmpLocation)
        return strip.personalTabAt(tmpLocation[0] + x, tmpLocation[1] + y)
    }

    private fun edgeDirectionAt(x: Int): Int {
        if (DrawerTabSwitcher.tabIds(launcher).size < 2) return 0
        val band = dp(EDGE_BAND_DP)
        val width = launcher.dragLayer.width
        val direction = when {
            x < band -> -1
            x > width - band -> 1
            else -> 0
        }
        return if (launcher.dragLayer.layoutDirection == View.LAYOUT_DIRECTION_RTL) -direction else direction
    }

    private fun setEdge(direction: Int) {
        if (edgeDirection == direction) return
        edgeDirection = direction
        handler.removeCallbacks(pageRunnable)
        if (direction != 0) handler.postDelayed(pageRunnable, EDGE_DELAY_MS)
    }

    private val pageRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!isDrawerDragActive || edgeDirection == 0) return
            val appsView = launcher.appsView ?: return
            val ids = DrawerTabSwitcher.tabIds(launcher)
            val current = ids.indexOf(DrawerFilterState.activeFilterId.value).coerceAtLeast(0)
            val next = current + edgeDirection
            if (next in ids.indices) {
                setHover(null)
                // Slides like a swipe, at half speed (skipped while a slide is still moving).
                // Instantly if the drawer has no swipe helper.
                val swipeHelper = DrawerFilterSwipeHelper.of(appsView)
                if (swipeHelper != null) {
                    swipeHelper.slideToTab(forward = edgeDirection > 0, duration = TAB_SLIDE_MS)
                } else {
                    DrawerTabSwitcher.switchTo(appsView, ids[next])
                }
            }
            handler.postDelayed(this, EDGE_REPEAT_MS)
        }
    }

    // Scrolling at the top and bottom of the list

    private fun scrollDirectionAt(y: Int): Int {
        val rv = personalRecyclerView() ?: return 0
        launcher.dragLayer.getViewRectRelativeToSelf(rv, tmpRect)
        val band = dp(SCROLL_BAND_DP)
        return when {
            y < tmpRect.top + band -> -1
            y > tmpRect.bottom - band -> 1
            else -> 0
        }
    }

    private fun setScroll(direction: Int) {
        if (scrollDirection == direction) return
        scrollDirection = direction
        launcher.dragLayer.removeCallbacks(scrollRunnable)
        if (direction != 0) launcher.dragLayer.postOnAnimation(scrollRunnable)
    }

    private val scrollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (scrollDirection == 0 || !isDrawerDragActive) return
            val rv = personalRecyclerView() ?: return
            rv.scrollBy(0, scrollDirection * dp(SCROLL_STEP_DP))
            // What's under the finger changed.
            launcher.dragController.forceTouchMove()
            if (scrollDirection != 0) launcher.dragLayer.postOnAnimation(this)
        }
    }

    // Folders and apps under the dragged app

    /** The personal app list, while it's the one shown. */
    private fun personalRecyclerView(): AllAppsRecyclerView? {
        val appsView = launcher.appsView ?: return null
        return appsView.activeRecyclerView?.takeIf { it.apps === appsView.personalAppList }
    }

    /**
     * The drawer folder or app the dragged app is over. Only in "All" with custom drawer folders:
     * filter tabs show a plain list, and auto-category folders can't be changed.
     */
    private fun findTarget(dragObject: DragObject): HoverTarget? {
        if (DrawerFilterState.activeFilterId.value != null || !prefs.drawerList.get()) return null
        val rv = personalRecyclerView() ?: return null
        val dragLayer = launcher.dragLayer
        val center = dragObject.getVisualCenter(null)
        val cx = center[0].toInt()
        val cy = center[1].toInt()
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val folderIcon = (child as? ViewGroup)?.getChildAt(0) as? FolderIcon
            if (folderIcon != null) {
                val folderId = folderIcon.mInfo.id
                if (folderId == ItemInfo.NO_ID) continue
                dragLayer.getViewRectRelativeToSelf(folderIcon, tmpRect)
                if (tmpRect.contains(cx, cy)) return FolderTarget(folderIcon, folderId)
                continue
            }
            val icon = child as? BubbleTextView ?: continue
            val info = icon.tag as? AppInfo ?: continue
            if (info.user != Process.myUserHandle()) continue
            val key = info.toComponentKey().toString()
            if (key == dragKey) continue
            // The icon itself, not the whole cell, so a drop between apps doesn't make a folder.
            dragLayer.getViewRectRelativeToSelf(icon, tmpRect)
            val left = tmpRect.left
            val top = tmpRect.top
            icon.getIconBounds(tmpRect)
            tmpRect.offset(left, top)
            tmpRect.inset(-dp(ICON_SLOP_DP), -dp(ICON_SLOP_DP))
            if (tmpRect.contains(cx, cy)) return AppTarget(icon, key)
        }
        return null
    }

    /**
     * Grows the folder or app under the dragged app, and shrinks the previous one back. A folder
     * held over long enough opens.
     */
    private fun setHover(target: HoverTarget?) {
        if (target?.view === hovered?.view) {
            hovered = target
            return
        }
        hovered?.view?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(HOVER_MS)?.start()
        handler.removeCallbacks(openFolderRunnable)
        hovered = target
        target?.view?.animate()?.scaleX(HOVER_SCALE)?.scaleY(HOVER_SCALE)?.setDuration(HOVER_MS)?.start()
        if (target is FolderTarget && canOpenDuringDrag(target.view)) {
            handler.postDelayed(openFolderRunnable, FOLDER_OPEN_DELAY_MS)
        }
    }

    /** Not the folder the app came from or one it's already in: dropping there changes nothing. */
    private fun canOpenDuringDrag(icon: FolderIcon): Boolean {
        val folder = icon.folder ?: return false
        if (folder.isOpen || folder.isDestroyed || icon.mInfo.id == originFolderId) return false
        return icon.mInfo.getContents().none { content ->
            content.targetComponent?.let { ComponentKey(it, content.user).toString() } == dragKey
        }
    }

    /** Opens the held folder as the home screen does. It then takes the drag until it's left. */
    private val openFolderRunnable = Runnable {
        val target = hovered as? FolderTarget ?: return@Runnable
        if (!isDrawerDragActive || !canOpenDuringDrag(target.view)) return@Runnable
        setHover(null)
        setScroll(0)
        target.view.folder.beginExternalDrag()
    }

    /**
     * Into the folder or app dropped on, back where it came from when nothing changed, or a fade
     * where it was dropped. The drag ends when the animation does.
     */
    private fun animateDrop(dragObject: DragObject, target: HoverTarget?, changed: Boolean) {
        setHover(null)
        setEdge(0)
        setScroll(0)
        val dragView = dragObject.dragView ?: return
        val dragLayer = launcher.dragLayer
        when {
            target != null -> {
                dragLayer.getViewRectRelativeToSelf(target.view, tmpRect)
                val toX = tmpRect.centerX() - dragView.measuredWidth / 2
                val toY = tmpRect.centerY() - dragView.measuredHeight / 2
                dragLayer.animateViewIntoPosition(
                    dragView, intArrayOf(toX, toY), 0f, DROP_SCALE, DROP_SCALE,
                    DragLayer.ANIMATION_END_DISAPPEAR, null, -1,
                )
            }
            !changed -> launcher.dragController.animateDragViewToOriginalPosition(null, null, -1)
            else -> dragLayer.animateViewIntoPosition(
                dragView,
                intArrayOf(dragView.translationX.toInt(), dragView.translationY.toInt()),
                0f, dragView.scaleX, dragView.scaleY,
                DragLayer.ANIMATION_END_DISAPPEAR, null, FADE_MS.toInt(),
            )
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        launcher.resources.displayMetrics,
    ).toInt()

    private companion object {
        const val EDGE_BAND_DP = 24
        const val EDGE_DELAY_MS = 500L
        const val EDGE_REPEAT_MS = 900L

        /** Half the speed of a swiped tab switch. */
        const val TAB_SLIDE_MS = DrawerFilterSwipeHelper.MAX_SETTLE_DURATION_MS * 2
        const val SCROLL_BAND_DP = 56
        const val SCROLL_STEP_DP = 8
        const val ICON_SLOP_DP = 8
        const val HOVER_SCALE = 1.15f
        const val HOVER_MS = 150L

        /** As for home screen folders (FolderIcon.ON_OPEN_DELAY). */
        const val FOLDER_OPEN_DELAY_MS = 800L
        const val DROP_SCALE = 0.5f
        const val FADE_MS = 150L
    }
}
