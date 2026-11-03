package app.lawnchair.allapps.filters

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import kotlin.math.abs

/**
 * Switches between [DrawerFilter] chips by swiping sideways on the personal app list.
 *
 * The swipe is only taken when there is a chip in its direction. Otherwise, e.g. swiping on to
 * the next page after the last filter, the gesture is left to the personal/work tab pager.
 */
class DrawerFilterSwipeHelper(private val appsView: ActivityAllAppsContainerView<*>) {

    private val prefs2 = PreferenceManager2.getInstance(appsView.context)
    private val touchSlop = ViewConfiguration.get(appsView.context).scaledTouchSlop

    private var tracking = false
    private var consuming = false
    private var downX = 0f
    private var downY = 0f
    private val tmpLocation = IntArray(2)

    /** Returns true to take the rest of the gesture from the app list and the tab pager. */
    fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                consuming = false
                tracking = canStartAt(ev)
                downX = ev.x
                downY = ev.y
            }

            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
                    // Scrolling the list.
                    tracking = false
                } else if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 2) {
                    tracking = false
                    if (switchFilter(swipedLeft = dx < 0)) {
                        consuming = true
                        return true
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> tracking = false
        }
        return false
    }

    /** Returns true while the rest of a gesture that switched the filter is being consumed. */
    fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!consuming) return false
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            consuming = false
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
        // Swiping on the chips scrolls them.
        val chips = appsView.floatingHeaderView?.findFixedRowByType(DrawerFilterChipsRow::class.java)
        return chips == null || !isOver(chips, ev)
    }

    private fun isOver(view: View, ev: MotionEvent): Boolean {
        if (!view.isShown) return false
        view.getLocationOnScreen(tmpLocation)
        val x = ev.rawX - tmpLocation[0]
        val y = ev.rawY - tmpLocation[1]
        return x >= 0 && y >= 0 && x < view.width && y < view.height
    }

    /** Selects the next or previous chip. Returns false if there is none in that direction. */
    private fun switchFilter(swipedLeft: Boolean): Boolean {
        val isRtl = appsView.layoutDirection == View.LAYOUT_DIRECTION_RTL
        // Like pages: swiping towards the start shows the next chip.
        val forward = swipedLeft != isRtl

        val chipIds = listOf<String?>(null) + prefs2.drawerFilters.firstCached().map { it.id }
        val current = chipIds.indexOf(DrawerFilterState.activeFilterId.value).coerceAtLeast(0)
        val target = if (forward) current + 1 else current - 1
        if (target !in chipIds.indices) return false

        DrawerFilterState.activeFilterId.value = chipIds[target]
        animateListIn(fromRight = swipedLeft)
        return true
    }

    private fun animateListIn(fromRight: Boolean) {
        val rv = appsView.activeRecyclerView ?: return
        rv.scrollToTop()
        rv.animate().cancel()
        val offset = rv.width * SLIDE_IN_FRACTION
        rv.translationX = if (fromRight) offset else -offset
        rv.alpha = 0f
        rv.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(SLIDE_IN_DURATION)
            .start()
    }

    companion object {
        private const val SLIDE_IN_FRACTION = 0.15f
        private const val SLIDE_IN_DURATION = 200L
    }
}
