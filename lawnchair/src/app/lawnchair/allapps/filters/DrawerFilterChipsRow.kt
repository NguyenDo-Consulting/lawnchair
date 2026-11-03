package app.lawnchair.allapps.filters

import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.util.overrideAllAppsTextColor
import com.android.launcher3.R
import com.android.launcher3.allapps.FloatingHeaderRow
import com.android.launcher3.allapps.FloatingHeaderView
import com.android.launcher3.anim.AlphaUpdateListener
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
 * Row of [DrawerFilter] chips in the app drawer header: "All" followed by the user's filters.
 * Hidden while there are no filters.
 */
class DrawerFilterChipsRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs), FloatingHeaderRow {

    private val prefs2 = PreferenceManager2.getInstance(context)
    private val chipContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val rowHeight = dp(52)
    private val chipHeight = dp(36)

    private var parentHeader: FloatingHeaderView? = null
    private var scope: CoroutineScope? = null
    private var filters: List<DrawerFilter> = emptyList()
    private var activeFilterId: String? = null

    // Filters only apply to personal apps, so the chips fade out on the work tab. The row keeps
    // its space, as changing the header height on every tab switch looks abrupt.
    private var workTabActive = false

    init {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(chipContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        prefs2.drawerFilters.get()
            .combine(DrawerFilterState.activeFilterId) { filters, activeId -> filters to activeId }
            .onEach { (newFilters, activeId) -> bind(newFilters, activeId) }
            .launchIn(newScope)
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Fixed height, so that the header can reserve it before layout (getExpectedHeight).
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY))
    }

    private fun bind(newFilters: List<DrawerFilter>, newActiveId: String?) {
        filters = newFilters
        // A deleted filter falls back to "All".
        activeFilterId = newActiveId?.takeIf { id -> newFilters.any { it.id == id } }

        chipContainer.removeAllViews()
        chipContainer.addView(createChip(context.getString(R.string.drawer_filter_all), null))
        newFilters.forEach { chipContainer.addView(createChip(it.title, it.id)) }

        updateVisibility()
        post { scrollToSelectedChip() }
    }

    // A filter can also be selected by swiping on the app list, while its chip is off screen.
    private fun scrollToSelectedChip() {
        val index = if (activeFilterId == null) 0 else filters.indexOfFirst { it.id == activeFilterId } + 1
        val chip = chipContainer.getChildAt(index) ?: return
        val visibleStart = scrollX
        val visibleEnd = scrollX + width - paddingLeft - paddingRight
        if (chip.left < visibleStart) {
            smoothScrollTo(chip.left, 0)
        } else if (chip.right > visibleEnd) {
            smoothScrollTo(chip.right - (visibleEnd - visibleStart), 0)
        }
    }

    /** Called by [FloatingHeaderView] when the personal or work tab becomes active. */
    fun setWorkTabActive(active: Boolean) {
        if (workTabActive == active) return
        workTabActive = active
        // The chips fade, not the row: the header sets the row's alpha while the list scrolls.
        chipContainer.animate().cancel()
        if (active) {
            chipContainer.animate()
                .alpha(0f)
                .setDuration(CHIPS_FADE_DURATION)
                .withEndAction { chipContainer.visibility = INVISIBLE }
                .start()
        } else {
            chipContainer.visibility = VISIBLE
            chipContainer.animate()
                .alpha(1f)
                .setDuration(CHIPS_FADE_DURATION)
                .start()
        }
    }

    private fun updateVisibility() {
        val newVisibility = if (filters.isEmpty()) GONE else VISIBLE
        if ((visibility == GONE) == (newVisibility == GONE)) return
        visibility = newVisibility
        // The header height changes, so it has to lay out the lists again. Posted, as this can
        // run while the header itself is being set up.
        post { parentHeader?.onHeightUpdated() }
    }

    private fun createChip(title: String, filterId: String?): TextView {
        val selected = filterId == activeFilterId
        return TextView(context).apply {
            text = title
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(Themes.getAttrColor(context, android.R.attr.textColorPrimary))
            overrideAllAppsTextColor(this)

            val textColor = currentTextColor
            val accent = ColorTokens.ColorAccent.resolveColor(context)
            background = GradientDrawable().apply {
                cornerRadius = chipHeight / 2f
                if (selected) {
                    setColor(accent)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), ColorUtils.setAlphaComponent(textColor, 0x66))
                }
            }
            if (selected) {
                val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE
                setTextColor(ColorStateList.valueOf(onAccent))
            }

            isSelected = selected
            contentDescription = title
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, chipHeight).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { onChipClicked(filterId) }
        }
    }

    private fun onChipClicked(filterId: String?) {
        if (filterId == activeFilterId) return
        DrawerFilterState.activeFilterId.value = filterId
        val activityContext = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .firstOrNull { it is ActivityContext } as? ActivityContext
        activityContext?.appsView?.activeRecyclerView?.scrollToTop()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    override fun setup(parent: FloatingHeaderView, allRows: Array<FloatingHeaderRow>, tabsHidden: Boolean) {
        parentHeader = parent
    }

    override fun getExpectedHeight(): Int = if (visibility == GONE) 0 else rowHeight

    override fun shouldDraw(): Boolean = visibility != GONE

    override fun hasVisibleContent(): Boolean = filters.isNotEmpty()

    override fun isVisible(): Boolean = visibility == VISIBLE

    override fun setVerticalScroll(scroll: Int, isScrolledOut: Boolean) {
        if (!isScrolledOut) {
            translationY = scroll.toFloat()
        }
        alpha = if (isScrolledOut) 0f else 1f
        if (visibility != GONE) {
            AlphaUpdateListener.updateVisibility(this)
        }
    }

    override fun getTypeClass(): Class<out FloatingHeaderRow> = DrawerFilterChipsRow::class.java

    override fun getFocusedChild(): View? = chipContainer.getChildAt(0)

    companion object {
        private const val CHIPS_FADE_DURATION = 150L
    }
}
