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

package app.lawnchair.hotseat

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import com.android.launcher3.CellLayout
import com.android.launcher3.DeviceProfile
import com.android.launcher3.PagedView
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.Workspace
import com.android.launcher3.pageindicators.PageIndicatorDots

/**
 * Horizontally pageable container for dock [CellLayout] pages.
 */
class HotseatPagedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : PagedView<PageIndicatorDots>(context, attrs, defStyle) {

    fun interface OnDockPageChangeListener {
        fun onDockPageChanged(page: Int)
    }

    var isPagingEnabled: Boolean = false
        private set(value) {
            field = value
            applyPageIndicatorVisibility()
        }

    private var onDockPageChangeListener: OnDockPageChangeListener? = null

    private var touchOnIconSwipe = false // NDShades: icon swipe gestures on the dock

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setPageIndicator(indicator: PageIndicatorDots?) {
        mPageIndicator = indicator
        mPageIndicator?.let {
            it.setMarkersCount(childCount)
            it.setActiveMarker(nextPage)
        }
        applyPageIndicatorVisibility()
    }

    private fun applyPageIndicatorVisibility() {
        val indicator = mPageIndicator ?: return
        if (isPagingEnabled) {
            indicator.visibility = VISIBLE
            // Same as workspace: show while paging, fade out when idle.
            indicator.setShouldAutoHide(true)
        } else {
            indicator.setShouldAutoHide(false)
            indicator.visibility = GONE
        }
    }

    override fun getPageAt(index: Int): CellLayout? = getChildAt(index) as? CellLayout

    fun getCurrentCellLayout(): CellLayout? = getPageAt(nextPage)

    fun resetPages(hasVerticalHotseat: Boolean, workspace: Workspace<*>?, dp: DeviceProfile) {
        removeAllViews()
        val pageCount = if (hasVerticalHotseat) 1 else maxOf(1, dp.numHotseatPages)
        isPagingEnabled = pageCount > 1

        val inflater = LayoutInflater.from(context)
        for (i in 0 until pageCount) {
            val page = inflater.inflate(R.layout.hotseat_page, this, false) as CellLayout
            page.setHotseatPageIndex(i)
            if (workspace != null) {
                page.setCellLayoutContainer(workspace)
            }
            page.resetCellSize(dp)
            page.isLongClickable = false
            page.isHapticFeedbackEnabled = false
            if (hasVerticalHotseat) {
                page.setGridSize(1, dp.numShownHotseatIcons)
            } else {
                page.setGridSize(dp.numShownHotseatIcons, dp.numHotseatRows)
            }
            addView(page, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        mPageIndicator?.let {
            it.setMarkersCount(pageCount)
            it.setActiveMarker(0)
        }
        applyPageIndicatorVisibility()
        setCurrentPage(0)
        requestLayout()
    }

    fun setOnDockPageChangeListener(listener: OnDockPageChangeListener?) {
        onDockPageChangeListener = listener
    }

    override fun onPageEndTransition() {
        super.onPageEndTransition()
        onDockPageChangeListener?.onDockPageChanged(nextPage)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (mMaxScroll > 0) {
            mPageIndicator?.setScroll(l, mMaxScroll)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isPagingEnabled) return false
        // NDShades: a touch starting on an icon with a left/right swipe gesture belongs to it.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOnIconSwipe = isTouchOnIconWithSwipeGesture(ev.x, ev.y, false)
        }
        if (touchOnIconSwipe) return false
        return super.onInterceptTouchEvent(ev)
    }

    /**
     * NDShades: Returns whether the given point, in this view's coordinates, is on an icon with
     * a swipe gesture configured. [vertical] picks up/down gestures, otherwise left/right.
     */
    fun isTouchOnIconWithSwipeGesture(x: Float, y: Float, vertical: Boolean): Boolean {
        val page = findPageAtLocalX(x) ?: return false
        val coord = floatArrayOf(x, y)
        Utilities.mapCoordInSelfToDescendant(page, this, coord)
        if (!Utilities.pointInView(page, coord[0], coord[1], 0f)) return false
        val icon = Workspace.findIconInCellLayout(page, coord[0], coord[1]) ?: return false
        return if (vertical) {
            icon.hasConfiguredVerticalIconSwipeGesture()
        } else {
            icon.hasConfiguredHorizontalIconSwipeGesture()
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isPagingEnabled) return false
        return super.onTouchEvent(ev)
    }

    /** Returns the page CellLayout under the given x in this view's coordinates. */
    fun findPageAtLocalX(x: Float): CellLayout? {
        val width = measuredWidth
        if (width <= 0 || childCount == 0) {
            return getCurrentCellLayout()
        }
        var raw = (scrollX + x).toInt() / width
        if (mIsRtl) {
            raw = childCount - 1 - raw
        }
        val page = raw.coerceIn(0, childCount - 1)
        return getPageAt(page)
    }
}
