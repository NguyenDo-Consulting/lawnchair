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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.AbsListView
import android.widget.AdapterViewAnimator
import android.widget.FrameLayout
import android.widget.ScrollView
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.dragndrop.DraggableView
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.widget.LauncherAppWidgetHostView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Shows a [WidgetStackInfo]: one widget at a time, and a sideways swipe flips to the next or
 * previous one. Every widget stays attached, so the hidden ones keep updating and a flip shows them
 * current. Dots at the bottom show the position while swiping and fade out shortly after.
 *
 * Touch handling: a sideways move claims the gesture before the workspace can page with it, so
 * swiping on a stack flips widgets instead of pages. A move that turns out vertical is handed back,
 * so swiping up for the app drawer still works, and a scrolling widget keeps its own scroll.
 */
class WidgetStackView private constructor(context: Context) : FrameLayout(context), DraggableView {

    /** Dragged like a widget, so the drag code sizes and animates it as one. */
    override fun getViewType(): Int = DraggableView.DRAGGABLE_WIDGET

    /**
     * The whole stack is what's dragged. The drop animation divides by this width, so leaving it
     * empty (the default) makes the drop scale NaN, which crashes.
     */
    override fun getWorkspaceVisualDragBounds(bounds: Rect) {
        bounds.set(0, 0, width, height)
    }

    lateinit var info: WidgetStackInfo
        private set

    private val widgetViews = mutableListOf<View>()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = FLING_DP_PER_SECOND * resources.displayMetrics.density
    private var velocityTracker: VelocityTracker? = null

    private var touchState = TOUCH_IDLE
    private var downX = 0f
    private var downY = 0f
    /** The finger moved past the touch slop in this gesture, so it isn't a long-press. */
    private var movedPastSlop = false
    private var dragStartX = 0f
    private var dragDx = 0f
    private var settleAnimator: ValueAnimator? = null

    private val dotRadius = 3f * resources.displayMetrics.density
    private val dotGap = 6f * resources.displayMetrics.density
    private val dotBottomMargin = 6f * resources.displayMetrics.density
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(2f * resources.displayMetrics.density, 0f, 0f, 0x66000000)
    }
    private var dotsAlpha = 0f
    private var dotsAnimator: ValueAnimator? = null
    private val hideDots = Runnable { animateDots(0f) }

    init {
        setWillNotDraw(false)
        clipChildren = true
    }

    private val isRtl: Boolean
        get() = layoutDirection == LAYOUT_DIRECTION_RTL

    /** The home screen's widget padding, which the stack keeps around its widgets. */
    private var widgetPadding: Rect? = null

    /**
     * The cell container gives every child that isn't a widget an app icon's padding each time it
     * measures it. A stack holds widgets, so it keeps the widget padding instead.
     */
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        val p = widgetPadding
        if (p == null) super.setPadding(left, top, right, bottom) else super.setPadding(p.left, p.top, p.right, p.bottom)
    }

    /** The view of the widget that is showing. */
    val activeWidgetView: View?
        get() = widgetViews.getOrNull(info.activeIndex)

    /** The "Loop widget stacks" setting: past the last widget comes the first, and the other way. */
    private val loops: Boolean
        get() = PreferenceManager2.getInstance(context).loopWidgetStacks.firstCached()

    /** The widget [step] places from the one showing, or null past either end when not looping. */
    private fun indexAt(step: Int): Int? {
        if (widgetViews.size < 2) return null
        val index = info.activeIndex + step
        return if (loops) index.mod(widgetViews.size) else index.takeIf { it in widgetViews.indices }
    }

    /** Index of the widget that a sideways drag of [dx] moves toward, or null at an end. */
    private fun neighborFor(dx: Float): Int? {
        if (dx == 0f) return null
        val forward = if (isRtl) dx > 0 else dx < 0
        return indexAt(if (forward) 1 else -1)
    }

    /** Long-pressing a widget in the stack long-presses the stack, so the whole stack is dragged. */
    private fun onWidgetLongClick(): Boolean = performLongClick()

    /** Lets go of the stack's claim on the gesture first, or the drag wouldn't get the moves. */
    override fun performLongClick(): Boolean {
        // A finger that moved was scrolling or swiping, not holding still.
        if (movedPastSlop) return false
        touchState = TOUCH_RELEASED
        parent?.requestDisallowInterceptTouchEvent(false)
        return super.performLongClick()
    }

    /**
     * A widget in the stack keeps TalkBack reading its content, but not Launcher3's actions for a
     * widget on its own (move to home screen, remove); the stack has its own, see
     * [WidgetStackAccessibilityDelegate].
     */
    private fun prepareWidgetView(view: View) {
        view.setOnLongClickListener { onWidgetLongClick() }
        view.accessibilityDelegate = null
    }

    /** The view of the widget with [appWidgetId] in this stack, if it's here. */
    fun findWidgetView(appWidgetId: Int): LauncherAppWidgetHostView? = widgetViews
        .filterIsInstance<LauncherAppWidgetHostView>()
        .firstOrNull { (it.tag as? LauncherAppWidgetInfo)?.appWidgetId == appWidgetId }

    /** Whether there's a widget [step] places away (1 = next, -1 = previous); see [loops]. */
    fun canStep(step: Int): Boolean = indexAt(step) != null

    /** Shows the widget [step] places away, for TalkBack, which can't swipe. */
    fun step(step: Int): Boolean {
        val index = indexAt(step) ?: return false
        setActiveIndex(index)
        @Suppress("DEPRECATION")
        announceForAccessibility(contentDescription)
        return true
    }

    private fun addWidgetView(view: View) {
        prepareWidgetView(view)
        widgetViews.add(view)
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * Swaps the view of the widget [widget] for [newView], in the same place in the stack. Launcher3
     * makes a placeholder view while the launcher isn't showing (for example right after a widget's
     * setup screen) and replaces it once it is; for a widget in a stack, that happens here.
     */
    fun replaceWidgetView(widget: LauncherAppWidgetInfo, newView: View): Boolean {
        val index = widgetViews.indexOfFirst { (it.tag as? LauncherAppWidgetInfo)?.id == widget.id }
        if (index < 0) return false
        val old = widgetViews[index]
        val childIndex = indexOfChild(old)
        removeView(old)
        prepareWidgetView(newView)
        widgetViews[index] = newView
        addView(newView, childIndex, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showActive()
        return true
    }

    /** Shows the widget at [WidgetStackInfo.activeIndex] and hides the rest. */
    private fun showActive() {
        widgetViews.forEachIndexed { index, view ->
            view.translationX = 0f
            view.visibility = if (index == info.activeIndex) VISIBLE else INVISIBLE
        }
        updateContentDescription()
    }

    private fun updateContentDescription() {
        contentDescription = resources.getString(
            R.string.widget_stack_description,
            info.activeIndex + 1,
            widgetViews.size,
        )
    }

    // ---- Touch ----

    /**
     * Sees every touch on the stack, even while a widget's list is scrolling (a scrolling list
     * stops [onInterceptTouchEvent] from being called). A widget's long-press timer is cancelled
     * only by moves the widget itself checks, and a scrolling list hides those from it, so scrolling
     * a calendar would otherwise count as a long-press and open the menu. Once the finger moves past
     * the slop, the stack cancels the timers itself.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                movedPastSlop = false
            }
            MotionEvent.ACTION_MOVE -> if (!movedPastSlop && hypot(ev.x - downX, ev.y - downY) > touchSlop) {
                movedPastSlop = true
                widgetViews.forEach { it.cancelLongPress() }
            }
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            movedPastSlop = false
        }
        return handled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (widgetViews.size < 2) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settleAnimator?.end()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                // Claim the gesture now. The workspace sees each move before the stack does, so a
                // quick flick could otherwise turn the page before the stack noticed it. The
                // drawer swipe and its own drag controller still saw this down, so handing a
                // vertical move back (releaseVertical) works.
                touchState = TOUCH_CLAIMED
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                when (touchState) {
                    TOUCH_CLAIMED -> when {
                        dx > touchSlop && dx > dy -> {
                            touchState = TOUCH_DRAGGING
                            dragStartX = ev.x
                            showDots()
                            return true
                        }
                        dy > touchSlop && dy > dx -> releaseVertical()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchState = TOUCH_IDLE
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return touchState == TOUCH_DRAGGING
    }

    /** The move turned out vertical: let the drawer swipe or the widget's own scroll have it. */
    private fun releaseVertical() {
        touchState = TOUCH_RELEASED
        parent?.requestDisallowInterceptTouchEvent(false)
        // That also cleared a scrolling widget's own claim on the drag layer, so restore it.
        val active = activeWidgetView
        if (active != null && isScrollable(active)) {
            findActivityContext(context)?.dragLayer?.requestDisallowInterceptTouchEvent(true)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (touchState != TOUCH_DRAGGING) return super.onTouchEvent(ev)
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> dragTo(ev.x - dragStartX)
            MotionEvent.ACTION_UP -> {
                val tracker = velocityTracker
                tracker?.computeCurrentVelocity(1000)
                settle(tracker?.xVelocity ?: 0f)
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                settle(0f)
                endTouch()
            }
        }
        return true
    }

    private fun endTouch() {
        touchState = TOUCH_IDLE
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /** Lays out the flip for a sideways drag of [dx]. Past either end the drag is damped. */
    private fun dragTo(dx: Float, neighborOverride: Int? = null) {
        dragDx = dx
        val neighbor = neighborOverride ?: neighborFor(dx)
        val shown = if (neighbor == null) dx * END_DAMPING else dx
        widgetViews.forEachIndexed { index, view ->
            when (index) {
                info.activeIndex -> {
                    view.visibility = VISIBLE
                    view.translationX = shown
                }
                neighbor -> {
                    view.visibility = VISIBLE
                    view.translationX = shown - sign(dx) * width
                }
                else -> view.visibility = INVISIBLE
            }
        }
        invalidate()
    }

    /** Finishes a flip: to the neighbor if dragged or flung far enough, otherwise back. */
    private fun settle(velocityX: Float) {
        val dx = dragDx
        val neighbor = neighborFor(dx)
        val flungToward = abs(velocityX) > flingVelocity && sign(velocityX) == sign(dx)
        val goes = neighbor != null && (abs(dx) > width * FLIP_FRACTION || flungToward)
        val end = if (goes) sign(dx) * width else 0f
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(dx, end).apply {
            duration = SETTLE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { dragTo(it.animatedValue as Float, neighbor) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleAnimator = null
                    dragDx = 0f
                    if (goes && neighbor != null) setActiveIndex(neighbor) else showActive()
                }
            })
            start()
        }
    }

    /** Shows the widget at [index] and remembers it. */
    fun setActiveIndex(index: Int) {
        if (index !in widgetViews.indices) return
        val changed = index != info.activeIndex
        info.activeIndex = index
        showActive()
        showDots()
        if (changed) {
            (findActivityContext(context) as? Launcher)?.modelWriter?.updateItemInDatabase(info)
        }
    }

    // ---- Dots ----

    private fun showDots() {
        animateDots(1f)
        removeCallbacks(hideDots)
        postDelayed(hideDots, DOTS_VISIBLE_MS)
    }

    private fun animateDots(to: Float) {
        dotsAnimator?.cancel()
        dotsAnimator = ValueAnimator.ofFloat(dotsAlpha, to).apply {
            duration = DOTS_FADE_MS
            addUpdateListener {
                dotsAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val count = widgetViews.size
        if (count < 2 || dotsAlpha <= 0f) return
        val total = count * 2 * dotRadius + (count - 1) * dotGap
        var cx = (width - total) / 2f + dotRadius
        val cy = height - paddingBottom - dotBottomMargin - dotRadius
        for (i in 0 until count) {
            val index = if (isRtl) count - 1 - i else i
            val strength = if (index == info.activeIndex) 1f else INACTIVE_DOT_ALPHA
            dotPaint.alpha = (255 * strength * dotsAlpha).toInt()
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)
            cx += 2 * dotRadius + dotGap
        }
    }

    /**
     * Launcher3 makes home screen items on a background thread, and a widget made there gets no
     * updates (it stays on "Loading...") until the launcher attaches it to the widget host on the
     * main thread. It does that only for widgets that sit on a page, so the stack does it for its
     * own. While the launcher isn't listening yet, the host hands back a placeholder, which is
     * replaced once it is (see [WidgetStacks.bindInStack]).
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val holder = (findActivityContext(context) as? Launcher)?.appWidgetHolder ?: return
        for (view in widgetViews.toList()) {
            val widgetView = view as? LauncherAppWidgetHostView ?: continue
            val info = widgetView.tag as? LauncherAppWidgetInfo ?: continue
            val attached = holder.attachViewToHostAndGetAttachedView(widgetView)
            if (attached !== widgetView) {
                attached.tag = info
                attached.isFocusable = true
                replaceWidgetView(info, attached)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hideDots)
        settleAnimator?.end()
        dotsAnimator?.cancel()
        dotsAlpha = 0f
    }

    companion object {
        private const val TOUCH_IDLE = 0
        private const val TOUCH_CLAIMED = 1
        private const val TOUCH_DRAGGING = 2
        private const val TOUCH_RELEASED = 3

        private const val FLIP_FRACTION = 0.35f
        private const val END_DAMPING = 0.25f
        private const val FLING_DP_PER_SECOND = 400f
        private const val SETTLE_DURATION_MS = 250L
        private const val DOTS_VISIBLE_MS = 1500L
        private const val DOTS_FADE_MS = 200L
        private const val INACTIVE_DOT_ALPHA = 0.4f

        /**
         * Builds the view for [info]. [inflateWidget] makes each widget's view the same way a
         * widget on the home screen is made; a widget it can't make is dropped from the stack.
         */
        @JvmStatic
        fun create(
            context: Context,
            info: WidgetStackInfo,
            inflateWidget: (LauncherAppWidgetInfo) -> View?,
        ): WidgetStackView {
            val view = WidgetStackView(context)
            view.info = info
            view.tag = info
            val activity = findActivityContext(context)
            view.widgetPadding = Rect(activity?.deviceProfile?.widgetPadding ?: Rect())
            view.setPadding(0, 0, 0, 0)
            // TalkBack: the stack is one item with its own actions (move, remove, edit, next...).
            view.isFocusable = true
            view.accessibilityDelegate = activity?.accessibilityDelegate
            val iterator = info.widgets.iterator()
            while (iterator.hasNext()) {
                val widgetView = inflateWidget(iterator.next())
                if (widgetView == null) iterator.remove() else view.addWidgetView(widgetView)
            }
            info.clampActiveIndex()
            view.showActive()
            return view
        }

        @JvmStatic
        fun findActivityContext(context: Context): ActivityContext? {
            var c: Context? = context
            while (c != null) {
                if (c is ActivityContext) return c
                c = (c as? ContextWrapper)?.baseContext
            }
            return null
        }

        /** Whether [view] holds a list or scroll view, which wants vertical drags for itself. */
        private fun isScrollable(view: View): Boolean = when (view) {
            is AbsListView, is ScrollView, is AdapterViewAnimator -> true
            is ViewGroup -> (0 until view.childCount).any { isScrollable(view.getChildAt(it)) }
            else -> false
        }
    }
}
