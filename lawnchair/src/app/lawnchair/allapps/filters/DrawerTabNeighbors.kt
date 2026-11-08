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

package app.lawnchair.allapps.filters

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.core.view.doOnPreDraw
import app.lawnchair.allapps.LawnchairAlphabeticalAppsList
import com.android.launcher3.R
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.AllAppsGridAdapter
import com.android.launcher3.allapps.AllAppsRecyclerView
import com.android.launcher3.views.ActivityContext
import kotlin.math.abs

/**
 * Two app lists kept off screen, showing the drawer tabs before and after the selected one at
 * their remembered positions. While swiping between tabs, the one in the swipe's direction slides
 * in next to the personal list, so the next tab's apps follow the finger like a page.
 *
 * They're only live lists, not pictures, so they're always up to date, and they share the
 * launcher's icon cache. Only the neighbors exist, however many tabs there are.
 */
class DrawerTabNeighbors<T>(private val appsView: ActivityAllAppsContainerView<T>)
    where T : Context, T : ActivityContext {

    /** One off-screen list. [side] is +1 when it sits right of the personal list, -1 when left. */
    inner class Neighbor {
        internal val list = LawnchairAlphabeticalAppsList(
            activityContext,
            appsView.appsStore,
            null,
            null,
            followsSelectedTab = false,
        )
        internal val rv = appsView.layoutInflater
            .inflate(R.layout.all_apps_rv_layout, appsView, false) as AllAppsRecyclerView
        internal var exists = false
        var tabId: String? = null
            internal set
        var side = 1
            internal set
        private var appliedOffset = -1

        init {
            val adapter = AllAppsGridAdapter(activityContext, appsView.layoutInflater, list, appsView.mainAdapterProvider)
            list.setAdapter(adapter)
            // The layout's id belongs to the personal list, which the container looks up by it.
            rv.id = View.NO_ID
            rv.apps = list
            rv.layoutManager = adapter.layoutManager
            rv.adapter = adapter
            rv.setHasFixedSize(true)
            rv.itemAnimator = null
            rv.isFocusable = false
            rv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            rv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            rv.visibility = View.INVISIBLE
        }

        internal fun bind(newTabId: String?, newExists: Boolean, newSide: Int, mainList: LawnchairAlphabeticalAppsList<*>?) {
            exists = newExists
            side = newSide
            if (!newExists) return
            val baseFilter = mainList?.baseItemFilter
            if (list.baseItemFilter !== baseFilter) list.updateItemFilter(baseFilter)
            val tabChanged = tabId != newTabId || appliedOffset < 0
            tabId = newTabId
            list.showTab(newTabId)
            val offset = DrawerTabSwitcher.savedOffset(appsView.context, newTabId)
            if (tabChanged || offset != appliedOffset) {
                appliedOffset = offset
                rv.doOnPreDraw { DrawerTabSwitcher.scrollTo(rv, offset) }
                rv.requestLayout()
            }
        }
    }

    private val activityContext: T = ActivityContext.lookupContext(appsView.context)
    private val previous = Neighbor()
    private val next = Neighbor()
    private val tmpRect = Rect()

    // How far the dragged-in neighbor is scrolled, which decides where the header ends up.
    private var targetScrollOffset = 0

    /**
     * Points the neighbors at the tabs around the selected one and matches them to the personal
     * list's size and padding. Cheap when nothing changed, so it can run on every touch down.
     */
    fun prepare() {
        val main = mainRecyclerView() ?: return
        val ids = DrawerTabSwitcher.tabIds(appsView.context)
        val index = ids.indexOf(DrawerFilterState.activeFilterId.value).coerceAtLeast(0)
        val rtl = appsView.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val mainList = appsView.personalAppList as? LawnchairAlphabeticalAppsList<*>

        attach(previous)
        attach(next)
        matchLayout(main, previous.rv)
        matchLayout(main, next.rv)
        // Like pages: the next tab is to the right, or to the left in RTL.
        previous.bind(ids.getOrNull(index - 1), index > 0, if (rtl) 1 else -1, mainList)
        next.bind(ids.getOrNull(index + 1), index + 1 < ids.size, if (rtl) -1 else 1, mainList)
    }

    /** The neighbor for the next ([forward]) or previous tab, if there is such a tab. */
    fun neighbor(forward: Boolean): Neighbor? = (if (forward) next else previous).takeIf { it.exists }

    /** Shows [neighbor] next to the personal list, ready to follow the finger. */
    fun startDrag(neighbor: Neighbor) {
        val main = mainRecyclerView() ?: return
        main.animate().cancel()
        main.alpha = 1f
        neighbor.rv.clipBounds = main.clipBounds?.let { Rect(it) }
        neighbor.rv.visibility = View.VISIBLE
        targetScrollOffset = neighbor.rv.computeVerticalScrollOffset()
        appsView.floatingHeaderView?.beginTabSwipe()
        setOffset(neighbor, 0f)
    }

    /**
     * Moves the personal list by [offset] pixels, with [neighbor] right beside it. The header moves
     * just as far between the two tabs' header positions, e.g. sliding in when the neighbor is at
     * the top and the current tab is scrolled down.
     */
    fun setOffset(neighbor: Neighbor, offset: Float) {
        val main = mainRecyclerView() ?: return
        main.translationX = offset
        neighbor.rv.translationX = neighbor.side * main.width + offset
        val progress = if (main.width > 0) (abs(offset) / main.width).coerceIn(0f, 1f) else 0f
        appsView.floatingHeaderView?.setTabSwipeProgress(targetScrollOffset, progress)
    }

    /**
     * Ends a drag. When [switched], the neighbor covers the personal list while it switches to the
     * neighbor's tab, and is hidden once the list draws it. Otherwise both are back in place.
     */
    fun finishDrag(neighbor: Neighbor, switched: Boolean) {
        mainRecyclerView()?.translationX = 0f
        if (!switched) {
            // The header back exactly as it was.
            appsView.floatingHeaderView?.setTabSwipeProgress(targetScrollOffset, 0f)
            hide(neighbor)
            return
        }
        neighbor.rv.translationX = 0f
        DrawerTabSwitcher.switchTo(appsView, neighbor.tabId) {
            hide(neighbor)
            // Ready for the next swipe, around the new tab.
            appsView.post { prepare() }
        }
    }

    private fun hide(neighbor: Neighbor) {
        neighbor.rv.visibility = View.INVISIBLE
        neighbor.rv.translationX = 0f
    }

    private fun mainRecyclerView(): AllAppsRecyclerView? =
        appsView.activeRecyclerView?.takeIf { it.apps === appsView.personalAppList }

    // Right above the personal list (so it covers it when a switch finishes), below the header.
    private fun attach(neighbor: Neighbor) {
        if (neighbor.rv.parent != null) return
        val index = appsView.indexOfChild(appsView.appsRecyclerViewContainer) + 1
        val params = RelativeLayout.LayoutParams(0, 0).apply {
            addRule(RelativeLayout.ALIGN_PARENT_LEFT)
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        appsView.addView(neighbor.rv, index, params)
    }

    // Same place, size and padding as the personal list, which can sit inside the work pager.
    private fun matchLayout(main: AllAppsRecyclerView, rv: AllAppsRecyclerView) {
        tmpRect.set(0, 0, main.width, main.height)
        appsView.offsetDescendantRectToMyCoords(main, tmpRect)
        val params = rv.layoutParams as RelativeLayout.LayoutParams
        val left = tmpRect.left - appsView.paddingLeft
        val top = tmpRect.top - appsView.paddingTop
        if (params.width != main.width || params.height != main.height ||
            params.leftMargin != left || params.topMargin != top
        ) {
            params.width = main.width
            params.height = main.height
            params.leftMargin = left
            params.topMargin = top
            rv.layoutParams = params
        }
        if (rv.paddingLeft != main.paddingLeft || rv.paddingTop != main.paddingTop ||
            rv.paddingRight != main.paddingRight || rv.paddingBottom != main.paddingBottom
        ) {
            rv.setPadding(main.paddingLeft, main.paddingTop, main.paddingRight, main.paddingBottom)
        }
    }
}
