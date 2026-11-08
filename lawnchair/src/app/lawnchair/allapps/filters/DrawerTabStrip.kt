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

import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.lawnchair.font.FontManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.util.overrideAllAppsTextColor
import com.android.launcher3.R
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.util.Themes
import com.android.launcher3.views.ActivityContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The app drawer's tab bar, shown in place of the upstream personal/work pill buttons: "Personal"
 * and "Work" without [DrawerFilter]s, or "All", the filters and "Work" with them. "Work" is only
 * there with a work profile, and is always last. The selected tab is underlined.
 *
 * It lives inside the upstream [com.android.launcher3.workprofile.PersonalWorkSlidingTabStrip],
 * which keeps its place in the header, and hides that strip's buttons. Personal and work clicks
 * are passed on to those hidden buttons, so the upstream page switching keeps working.
 */
class DrawerTabStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    private data class Tab(val title: CharSequence, val filterId: String?, val isWork: Boolean)

    private val prefs2 = PreferenceManager2.getInstance(context)
    private val tabContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorRect = RectF()
    private val indicatorHeight = tabBarHeight(context, labelsHidden = true).toFloat()
    private var indicatorStart = 0f
    private var indicatorEnd = 0f
    private var indicatorAnimator: ValueAnimator? = null

    // With hidden labels, the strip is a line and the underline only flashes on a tab switch.
    private var labelsHidden = false
    private var indicatorAlpha = 1f
    private var fadeAnimator: ValueAnimator? = null

    private var scope: CoroutineScope? = null
    private var tabs: List<Tab> = emptyList()
    private var filters: List<DrawerFilter> = emptyList()
    private var activeFilterId: String? = null
    private var hasWorkTab = false
    private var workTabActive = false
    private var selectedIndex = -1

    init {
        isHorizontalScrollBarEnabled = false
        // Tabs share the width when they fit, and scroll when they don't.
        isFillViewport = true
        setWillNotDraw(false)
        addView(tabContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hideUpstreamButtons()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        combine(
            prefs2.drawerFilters.get(),
            prefs2.hideDrawerTabLabels.get(),
            DrawerFilterState.activeFilterId,
        ) { filters, hideLabels, activeId -> Triple(filters, hideLabels, activeId) }
            .onEach { (newFilters, hideLabels, activeId) ->
                setLabelsHidden(hideLabels)
                onFiltersChanged(newFilters, activeId)
            }
            .launchIn(newScope)
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        indicatorAnimator?.cancel()
        fadeAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun setLabelsHidden(hidden: Boolean) {
        if (labelsHidden == hidden) return
        labelsHidden = hidden
        tabContainer.visibility = if (hidden) INVISIBLE else VISIBLE
        fadeAnimator?.cancel()
        indicatorAlpha = if (hidden) 0f else 1f
        scrollTo(0, 0)
        (parent as? View)?.let { strip ->
            strip.layoutParams = strip.layoutParams.apply { height = tabBarHeight(context, hidden) }
        }
    }

    private fun hideUpstreamButtons() {
        val strip = parent as? ViewGroup ?: return
        for (i in 0 until strip.childCount) {
            val child = strip.getChildAt(i)
            if (child !== this) child.visibility = GONE
        }
    }

    /**
     * Called by [com.android.launcher3.allapps.FloatingHeaderView] when the header is set up or
     * the personal or work page becomes active.
     */
    fun setWorkTabState(hasWorkTab: Boolean, workTabActive: Boolean) {
        val rebuild = this.hasWorkTab != hasWorkTab
        this.hasWorkTab = hasWorkTab
        this.workTabActive = workTabActive && hasWorkTab
        if (rebuild) rebuildTabs() else updateSelection(animate = true)
    }

    private fun onFiltersChanged(newFilters: List<DrawerFilter>, newActiveId: String?) {
        // The tab bar also shows without a work profile once there is a filter, and the list makes
        // room for its height.
        appsView()?.setDrawerTabBarState(newFilters.isNotEmpty(), tabBarHeight(context, labelsHidden))

        // A deleted filter falls back to "All".
        val activeId = newActiveId?.takeIf { id -> newFilters.any { it.id == id } }
        if (newFilters == filters && tabs.isNotEmpty()) {
            activeFilterId = activeId
            updateSelection(animate = true)
            return
        }
        filters = newFilters
        activeFilterId = activeId
        rebuildTabs()
    }

    private fun rebuildTabs() {
        tabs = buildList {
            if (filters.isEmpty()) {
                add(Tab(upstreamTabText(R.id.tab_personal, R.string.all_apps_personal_tab), null, false))
            } else {
                add(Tab(context.getString(R.string.drawer_tab_all), null, false))
                filters.forEach { add(Tab(it.title, it.id, false)) }
            }
            if (hasWorkTab) {
                add(Tab(upstreamTabText(R.id.tab_work, R.string.all_apps_work_tab), null, true))
            }
        }
        tabContainer.removeAllViews()
        tabs.forEach { tabContainer.addView(createTabView(it)) }
        selectedIndex = -1
        updateSelection(animate = false)
    }

    // The upstream buttons get the device-policy wording for work profiles.
    private fun upstreamTabText(buttonId: Int, fallback: Int): CharSequence {
        val text = (parent as? View)?.findViewById<TextView>(buttonId)?.text
        return if (text.isNullOrEmpty()) context.getString(fallback) else text
    }

    private fun createTabView(tab: Tab): TextView = TextView(context).apply {
        text = tab.title
        gravity = Gravity.CENTER
        maxLines = 1
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(16), 0, dp(16), 0)
        FontManager.INSTANCE.get(context).setCustomFont(this, R.id.font_body_medium)
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        setBackgroundResource(outValue.resourceId)
        contentDescription = tab.title
        layoutParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.MATCH_PARENT,
            1f,
        )
        setOnClickListener { onTabClicked(tab) }
    }

    private fun currentSelectedIndex(): Int {
        if (workTabActive) return tabs.indexOfFirst { it.isWork }
        return tabs.indexOfFirst { !it.isWork && it.filterId == activeFilterId }.coerceAtLeast(0)
    }

    private fun updateSelection(animate: Boolean) {
        val newIndex = currentSelectedIndex()
        val changed = newIndex != selectedIndex
        selectedIndex = newIndex

        val accent = ColorTokens.ColorAccent.resolveColor(context)
        indicatorPaint.color = accent
        for (i in 0 until tabContainer.childCount) {
            val tabView = tabContainer.getChildAt(i) as TextView
            val selected = i == newIndex
            tabView.setTextColor(Themes.getAttrColor(context, android.R.attr.textColorPrimary))
            overrideAllAppsTextColor(tabView)
            tabView.setTextColor(
                if (selected) accent else ColorUtils.setAlphaComponent(tabView.currentTextColor, 0xA6),
            )
            tabView.isSelected = selected
        }

        if (changed) {
            if (animate && isLaidOut) animateIndicator() else post { snapIndicator() }
            if (animate && labelsHidden) flashIndicator()
            post { scrollToSelectedTab() }
        }
    }

    /** Start and end of the underline across the selected tab, in this view's content. */
    private fun indicatorTarget(): Pair<Float, Float>? {
        if (labelsHidden) {
            // No labels to line up with: the width is split evenly, so the bar shows the place.
            val count = tabs.size
            val available = width - paddingLeft - paddingRight
            if (count == 0 || available <= 0 || selectedIndex !in 0 until count) return null
            val segment = available.toFloat() / count
            val position = if (layoutDirection == LAYOUT_DIRECTION_RTL) count - 1 - selectedIndex else selectedIndex
            val start = scrollX + paddingLeft + position * segment
            return start to start + segment
        }
        val tabView = tabContainer.getChildAt(selectedIndex) ?: return null
        if (tabView.width == 0) return null
        val start = (tabContainer.left + tabView.left).toFloat()
        return start to start + tabView.width
    }

    private fun snapIndicator() {
        indicatorAnimator?.cancel()
        val (start, end) = indicatorTarget() ?: return
        indicatorStart = start
        indicatorEnd = end
        invalidate()
    }

    private fun animateIndicator() {
        val (targetStart, targetEnd) = indicatorTarget() ?: return snapIndicator()
        if (indicatorEnd <= indicatorStart) return snapIndicator()
        indicatorAnimator?.cancel()
        val fromStart = indicatorStart
        val fromEnd = indicatorEnd
        indicatorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INDICATOR_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                indicatorStart = fromStart + (targetStart - fromStart) * t
                indicatorEnd = fromEnd + (targetEnd - fromEnd) * t
                invalidate()
            }
            start()
        }
    }

    /** Fades the underline in, holds it, then fades it out, to show the new tab's place. */
    private fun flashIndicator() {
        fadeAnimator?.cancel()
        val fromAlpha = indicatorAlpha
        val total = (FLASH_IN_DURATION + FLASH_HOLD_DURATION + FLASH_OUT_DURATION).toFloat()
        fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                val time = it.animatedFraction * total
                indicatorAlpha = when {
                    time < FLASH_IN_DURATION -> fromAlpha + (1f - fromAlpha) * (time / FLASH_IN_DURATION)
                    time < FLASH_IN_DURATION + FLASH_HOLD_DURATION -> 1f
                    else -> 1f - (time - FLASH_IN_DURATION - FLASH_HOLD_DURATION) / FLASH_OUT_DURATION
                }.coerceIn(0f, 1f)
                invalidate()
            }
            start()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (indicatorAnimator?.isRunning != true) snapIndicator()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (labelsHidden && tabs.isNotEmpty()) {
            // Always-visible track at half the bar's height across the strip. The selected tab's
            // bar stands up from it, bottoms lined up.
            val trackHeight = indicatorHeight / 2f
            indicatorPaint.alpha = 255
            indicatorRect.set(
                (scrollX + paddingLeft).toFloat(),
                height - trackHeight,
                (scrollX + width - paddingRight).toFloat(),
                height.toFloat(),
            )
            canvas.drawRoundRect(indicatorRect, trackHeight / 2f, trackHeight / 2f, indicatorPaint)
        }
        if (selectedIndex < 0 || indicatorEnd <= indicatorStart || indicatorAlpha <= 0f) return
        indicatorPaint.alpha = (indicatorAlpha * 255).toInt()
        indicatorRect.set(indicatorStart, height - indicatorHeight, indicatorEnd, height.toFloat())
        canvas.drawRoundRect(indicatorRect, indicatorHeight / 2f, indicatorHeight / 2f, indicatorPaint)
    }

    // A tab can also be selected by swiping on the app list, while it's off screen.
    private fun scrollToSelectedTab() {
        if (labelsHidden) return // Everything fits: the width is split evenly.
        val tabView = tabContainer.getChildAt(selectedIndex) ?: return
        val visibleStart = scrollX
        val visibleEnd = scrollX + width - paddingLeft - paddingRight
        if (tabView.left < visibleStart) {
            smoothScrollTo(tabView.left, 0)
        } else if (tabView.right > visibleEnd) {
            smoothScrollTo(tabView.right - (visibleEnd - visibleStart), 0)
        }
    }

    private fun onTabClicked(tab: Tab) {
        val strip = parent as? View
        if (tab.isWork) {
            strip?.findViewById<View>(R.id.tab_work)?.performClick()
            return
        }
        // First back to the personal page, so that it's the personal list that scrolls to the top.
        if (workTabActive) {
            strip?.findViewById<View>(R.id.tab_personal)?.performClick()
        }
        if (tab.filterId != activeFilterId) {
            val appsView = appsView()
            if (appsView != null) {
                DrawerTabSwitcher.switchTo(appsView, tab.filterId)
            } else {
                DrawerFilterState.activeFilterId.value = tab.filterId
            }
        }
    }

    private fun appsView(): ActivityAllAppsContainerView<*>? {
        val activityContext = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .firstOrNull { it is ActivityContext } as? ActivityContext
        return activityContext?.appsView
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    companion object {
        private const val INDICATOR_DURATION = 200L
        private const val FLASH_IN_DURATION = 150L
        private const val FLASH_HOLD_DURATION = 600L
        private const val FLASH_OUT_DURATION = 350L

        /** Height of the underline, and of the whole strip while the labels are hidden. */
        private const val INDICATOR_HEIGHT_DP = 3f

        /** Height the app list leaves for the tab bar, which depends on "Hide tab labels". */
        @JvmStatic
        fun tabBarHeight(context: Context): Int =
            tabBarHeight(context, PreferenceManager2.getInstance(context).hideDrawerTabLabels.firstCached())

        private fun tabBarHeight(context: Context, labelsHidden: Boolean): Int = if (labelsHidden) {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                INDICATOR_HEIGHT_DP,
                context.resources.displayMetrics,
            ).toInt()
        } else {
            context.resources.getDimensionPixelSize(R.dimen.all_apps_header_pill_height)
        }
    }
}
