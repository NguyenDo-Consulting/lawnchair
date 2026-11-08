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
import androidx.core.view.doOnPreDraw
import app.lawnchair.allapps.LawnchairAlphabeticalAppsList
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.AllAppsRecyclerView

/**
 * Switches the personal app list between drawer tabs (All and the [DrawerFilter]s). Each tab keeps
 * its own scroll position in [DrawerFilterState.scrollOffsets]. Without "Remember position", those
 * are forgotten when the drawer closes, so tabs only keep their place while the drawer is open.
 */
object DrawerTabSwitcher {

    /** Tab ids in order: null for "All", then the filters. */
    fun tabIds(context: Context): List<String?> =
        listOf<String?>(null) + PreferenceManager2.getInstance(context).drawerFilters.firstCached().map { it.id }

    /** Where a tab's list should be scrolled to when it's shown, in pixels. */
    fun savedOffset(tabId: String?): Int = DrawerFilterState.scrollOffsets[tabId] ?: 0

    /**
     * Shows [targetId] in the personal list. The current tab's position is saved first, and the
     * target's is restored before the list is drawn. [onShown] runs just before the first frame
     * that draws the target, e.g. to hide a view that covered the switch.
     */
    fun switchTo(
        appsView: ActivityAllAppsContainerView<*>,
        targetId: String?,
        onShown: (() -> Unit)? = null,
    ) {
        val currentId = DrawerFilterState.activeFilterId.value
        if (currentId == targetId) {
            onShown?.invoke()
            return
        }
        val personalList = appsView.personalAppList
        val rv = appsView.activeRecyclerView?.takeIf { it.apps === personalList }
        if (rv != null) {
            DrawerFilterState.scrollOffsets[currentId] = rv.computeVerticalScrollOffset()
        }

        (personalList as? LawnchairAlphabeticalAppsList<*>)?.showTab(targetId)
        DrawerFilterState.activeFilterId.value = targetId

        if (rv == null) {
            onShown?.invoke()
            return
        }
        val targetOffset = savedOffset(targetId)
        // The new apps are laid out by then, so the position can be restored.
        rv.doOnPreDraw {
            scrollTo(rv, targetOffset)
            rv.saveScrollPosition()
            appsView.floatingHeaderView?.syncToScrollPosition()
            onShown?.invoke()
        }
        rv.requestLayout()
    }

    /** Scrolls a laid-out list to [offset] pixels from its top, as far as it goes. */
    fun scrollTo(rv: AllAppsRecyclerView, offset: Int) {
        rv.scrollbar?.reattachThumbToScroll()
        val delta = offset - rv.computeVerticalScrollOffset()
        if (delta != 0) rv.scrollBy(0, delta)
    }
}
