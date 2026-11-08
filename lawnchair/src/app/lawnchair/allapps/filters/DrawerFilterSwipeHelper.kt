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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.R
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.views.ActivityContext
import kotlin.math.abs

/**
 * Switches between [DrawerFilter] tabs (see [DrawerTabStrip]) by swiping sideways on the personal
 * app list. The list follows the finger with the next tab's apps beside it ([DrawerTabNeighbors]),
 * like a page; on release, a flick or a swipe past [SWITCH_FRACTION] of the width switches.
 *
 * The swipe is only taken when there is a filter tab in its direction. Otherwise, e.g. swiping on
 * to the Work tab after the last filter, the gesture is left to the personal/work tab pager.
 */
class DrawerFilterSwipeHelper<T>(private val appsView: ActivityAllAppsContainerView<T>)
    where T : Context, T : ActivityContext {

    private val prefs2 = PreferenceManager2.getInstance(appsView.context)
    private val touchSlop = ViewConfiguration.get(appsView.context).scaledTouchSlop
    private val flingVelocity = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        FLING_VELOCITY_DP,
        appsView.resources.displayMetrics,
    )
    private val neighbors by lazy { DrawerTabNeighbors(appsView) }

    private var tracking = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var dragOriginX = 0f
    private var offset = 0f
    private var target: DrawerTabNeighbors<T>.Neighbor? = null
    private var velocityTracker: VelocityTracker? = null
    private var settleAnimator: ValueAnimator? = null
    private val tmpLocation = IntArray(2)

    /** Returns true to take the rest of the gesture from the app list and the tab pager. */
    fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A quick flick right after another: finish the last switch first.
                settleAnimator?.end()
                dragging = false
                tracking = canStartAt(ev)
                downX = ev.x
                downY = ev.y
                if (tracking) {
                    neighbors.prepare()
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
                }
            }

            MotionEvent.ACTION_MOVE -> if (tracking) {
                velocityTracker?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
                    // Scrolling the list.
                    tracking = false
                } else if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    // Decided on the same move as the pager (PagedView.determineScrollingStart:
                    // same slop, no angle check), which sees it after us. Waiting for a flatter
                    // swipe let the pager take slightly diagonal ones and jump to the Work tab.
                    tracking = false
                    val isRtl = appsView.layoutDirection == View.LAYOUT_DIRECTION_RTL
                    // Like pages: swiping towards the start shows the next tab.
                    val forward = (dx < 0) != isRtl
                    val neighbor = neighbors.neighbor(forward) ?: return false
                    startDrag(neighbor, ev.x, dx)
                    return true
                }
            }

            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> tracking = false
        }
        return false
    }

    /** Returns true while a drag between tabs is taking the gesture. */
    fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return false
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateDrag(ev.x)
            MotionEvent.ACTION_UP -> release(cancelled = false)
            MotionEvent.ACTION_CANCEL -> release(cancelled = true)
        }
        return true
    }

    private fun canStartAt(ev: MotionEvent): Boolean {
        if (appsView.isSearching || appsView.currentPage != ActivityAllAppsContainerView.AdapterHolder.MAIN) {
            return false
        }
        if (prefs2.drawerFilters.firstCached().isEmpty()) return false
        val rv = appsView.activeRecyclerView ?: return false
        if (!isOver(rv, ev)) return false
        // Swiping on the tab bar scrolls it.
        val tabs = appsView.floatingHeaderView?.findViewById<View>(R.id.drawer_tabs)
        return tabs == null || !isOver(tabs, ev)
    }

    private fun isOver(view: View, ev: MotionEvent): Boolean {
        if (!view.isShown) return false
        view.getLocationOnScreen(tmpLocation)
        val x = ev.rawX - tmpLocation[0]
        val y = ev.rawY - tmpLocation[1]
        return x >= 0 && y >= 0 && x < view.width && y < view.height
    }

    private fun startDrag(neighbor: DrawerTabNeighbors<T>.Neighbor, x: Float, dx: Float) {
        dragging = true
        target = neighbor
        // Start from where the swipe was recognized, so the list doesn't jump by the slop.
        dragOriginX = downX + if (dx < 0) -touchSlop else touchSlop
        neighbors.startDrag(neighbor)
        updateDrag(x)
    }

    private fun updateDrag(x: Float) {
        val neighbor = target ?: return
        val width = listWidth()
        // The list only moves towards the neighbor, and at most by its width.
        offset = if (neighbor.side > 0) {
            (x - dragOriginX).coerceIn(-width, 0f)
        } else {
            (x - dragOriginX).coerceIn(0f, width)
        }
        neighbors.setOffset(neighbor, offset)
    }

    private fun release(cancelled: Boolean) {
        val neighbor = target ?: return
        dragging = false
        val tracker = velocityTracker
        tracker?.computeCurrentVelocity(1000)
        // Positive while moving towards the neighbor.
        val towardsVelocity = -neighbor.side * (tracker?.xVelocity ?: 0f)
        velocityTracker?.recycle()
        velocityTracker = null

        val width = listWidth()
        val progress = if (width > 0) abs(offset) / width else 0f
        val switch = !cancelled &&
            (towardsVelocity > flingVelocity || (progress > SWITCH_FRACTION && towardsVelocity > -flingVelocity))
        val end = if (switch) -neighbor.side * width else 0f
        settle(neighbor, end, switch)
    }

    private fun settle(neighbor: DrawerTabNeighbors<T>.Neighbor, end: Float, switch: Boolean) {
        val width = listWidth()
        val distance = abs(end - offset)
        val duration = if (width > 0) {
            (MAX_SETTLE_DURATION * distance / width).toLong().coerceIn(MIN_SETTLE_DURATION, MAX_SETTLE_DURATION)
        } else {
            MIN_SETTLE_DURATION
        }
        val start = offset
        settleAnimator = ValueAnimator.ofFloat(start, end).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                offset = it.animatedValue as Float
                neighbors.setOffset(neighbor, offset)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleAnimator = null
                    target = null
                    offset = 0f
                    neighbors.finishDrag(neighbor, switch)
                }
            })
            start()
        }
    }

    private fun listWidth(): Float = (appsView.activeRecyclerView?.width ?: 0).toFloat()

    companion object {
        /** How far the list has to be dragged to switch without a flick, of its width. */
        private const val SWITCH_FRACTION = 0.3f

        /** Sideways speed of a flick that switches, like the personal/work pager's. */
        private const val FLING_VELOCITY_DP = 500f
        private const val MIN_SETTLE_DURATION = 80L
        private const val MAX_SETTLE_DURATION = 250L
    }
}
