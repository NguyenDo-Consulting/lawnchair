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

package app.lawnchair

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.RemoteViews
import app.lawnchair.applock.AppLockManager
import app.lawnchair.smartspace.SmartspaceAppWidgetProvider
import com.android.launcher3.R
import com.android.launcher3.util.Themes
import com.android.launcher3.widget.LauncherAppWidgetHostView
import com.android.launcher3.widget.RoundedCornerEnforcement
import kotlin.math.min

class LawnchairAppWidgetHostView @JvmOverloads constructor(
    context: Context,
    private var previewMode: Boolean = false,
) : LauncherAppWidgetHostView(context) {

    private var customView: ViewGroup? = null

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo) {
        inflateCustomView(info)
        super.setAppWidget(appWidgetId, info)
    }

    fun disablePreviewMode() {
        previewMode = false
        inflateCustomView(appWidgetInfo)
    }

    private fun inflateCustomView(info: AppWidgetProviderInfo) {
        customView = inflateCustomView(context, info, previewMode)
        if (customView == null) {
            return
        }
        customView!!.setOnLongClickListener(this)
        removeAllViews()
        addView(customView, MATCH_PARENT, MATCH_PARENT)
    }

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        if (customView != null) return
        super.updateAppWidget(remoteViews)
    }

    override fun getDefaultView(): View {
        if (customView != null) return getEmptyView()
        return super.getDefaultView()
    }

    override fun getErrorView(): View {
        if (customView != null) return getEmptyView()
        return super.getErrorView()
    }

    private fun getEmptyView(): View {
        return View(context)
    }

    // NDShades: a widget of a locked app is covered until the locked apps are unlocked. A tap on
    // the cover asks for the unlock, and a long press still moves the widget.

    private val lockManager by lazy { AppLockManager.getInstance(context) }
    private var covered = false
    private val coverListener = Runnable { post(::updateCover) }
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lockIcon by lazy {
        context.getDrawable(R.drawable.ic_lock)?.mutate()?.apply {
            setTint(Themes.getAttrColor(context, android.R.attr.textColorPrimary))
        }
    }

    private fun updateCover() {
        val info = appWidgetInfo
        val shouldCover = !previewMode &&
            info != null &&
            lockManager.isPackageLocked(info.provider.packageName, info.profile) &&
            !lockManager.isUnlocked
        if (shouldCover == covered) return
        covered = shouldCover
        // TalkBack mustn't read out what the cover hides.
        importantForAccessibility = if (covered) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        contentDescription = if (covered) context.getString(R.string.app_lock_widget_covered) else null
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lockManager.addListener(coverListener)
        updateCover()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lockManager.removeListener(coverListener)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!covered) {
            super.dispatchDraw(canvas)
            return
        }
        val radius = RoundedCornerEnforcement.computeEnforcedRadius(context)
        coverPaint.color = Themes.getAttrColor(context, R.attr.popupColorPrimary)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, coverPaint)
        lockIcon?.let { icon ->
            val maxSize = (COVER_ICON_MAX_DP * resources.displayMetrics.density).toInt()
            val size = (min(width, height) / 3).coerceAtMost(maxSize)
            val left = (width - size) / 2
            val top = (height - size) / 2
            icon.setBounds(left, top, left + size, top + size)
            icon.draw(canvas)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val intercept = super.onInterceptTouchEvent(ev)
        return covered || intercept
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.onTouchEvent(ev)
        if (covered && ev.actionMasked == MotionEvent.ACTION_UP &&
            ev.eventTime - ev.downTime < ViewConfiguration.getLongPressTimeout()
        ) {
            lockManager.requestUnlock(context, onSuccess = ::updateCover)
        }
        return handled
    }

    companion object {
        private const val COVER_ICON_MAX_DP = 48 // NDShades: widget cover

        private val customLayouts = mapOf(
            SmartspaceAppWidgetProvider.componentName to R.layout.smartspace_widget,
        )

        @JvmStatic
        fun inflateCustomView(context: Context, info: AppWidgetProviderInfo, previewMode: Boolean): ViewGroup? {
            val layoutId = customLayouts[info.provider] ?: return null

            val inflationContext = if (previewMode) Themes.createWidgetPreviewContext(context) else context
            return LayoutInflater.from(inflationContext)
                .inflate(layoutId, null, false) as ViewGroup
        }
    }
}
