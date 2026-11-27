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

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.AppWidgetResizeFrame
import com.android.launcher3.CellLayout
import com.android.launcher3.DropTarget.DragObject
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherConstants.ActivityCodes
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.util.PendingRequestArgs
import com.android.launcher3.util.Themes
import com.android.launcher3.views.BaseDragLayer
import com.android.launcher3.widget.LauncherAppWidgetHostView
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.launcher3.widget.PendingAppWidgetHostView

/**
 * The menu a long-press on a widget or a widget stack opens: a row of icons with no labels.
 *
 * - A widget: Create stack, Resize (if the widget can be resized) and Widget settings (if the
 *   widget can be reconfigured).
 * - A stack: Edit stack, and Widget settings for the widget that is showing.
 *
 * It works like an app's long-press menu: the drag waits until the finger moves, and moving closes
 * the menu and picks the item up. Letting go without moving leaves the menu open, and the next
 * touch outside it closes it. There's no menu while the home screen is locked.
 */
class WidgetMenu private constructor(
    private val launcher: Launcher,
    private val target: View,
) : AbstractFloatingView(launcher, null) {

    private val startDragThreshold =
        resources.getDimensionPixelSize(R.dimen.deep_shortcuts_start_drag_threshold)
    private val gap = (8 * resources.displayMetrics.density).toInt()
    private val buttonSize = (48 * resources.displayMetrics.density).toInt()
    private val sidePadding = (4 * resources.displayMetrics.density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(sidePadding, 0, sidePadding, 0)
        background = GradientDrawable().apply {
            setColor(Themes.getAttrColor(launcher, R.attr.popupColorPrimary))
            cornerRadius = buttonSize / 2f
        }
        elevation = 4 * resources.displayMetrics.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
    }

    private fun addAction(iconRes: Int, labelRes: Int, onClick: () -> Unit) {
        val button = ImageButton(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(Themes.getAttrColor(context, R.attr.popupTextColor))
            contentDescription = context.getString(labelRes)
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener {
                close(true)
                onClick()
            }
        }
        addView(button, LayoutParams(buttonSize, buttonSize))
    }

    private fun show() {
        mIsOpen = true
        val dragLayer = launcher.dragLayer
        val lp = BaseDragLayer.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.customPosition = true
        dragLayer.addView(this, lp)

        measure(
            MeasureSpec.makeMeasureSpec(dragLayer.width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(dragLayer.height, MeasureSpec.AT_MOST),
        )
        lp.width = measuredWidth
        lp.height = measuredHeight

        val targetRect = Rect()
        dragLayer.getDescendantRectRelativeToSelf(target, targetRect)
        val insets = dragLayer.insets
        val minX = insets.left + gap
        val maxX = dragLayer.width - insets.right - gap - measuredWidth
        lp.x = (targetRect.centerX() - measuredWidth / 2).coerceIn(minX, maxOf(minX, maxX))
        val above = targetRect.top - gap - measuredHeight
        lp.y = if (above >= insets.top + gap) above else targetRect.bottom + gap

        alpha = 0f
        scaleX = OPEN_SCALE
        scaleY = OPEN_SCALE
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ANIM_MS).start()
    }

    override fun handleClose(animate: Boolean) {
        if (!mIsOpen) return
        mIsOpen = false
        animate().cancel()
        if (animate) {
            animate().alpha(0f).scaleX(OPEN_SCALE).scaleY(OPEN_SCALE).setDuration(ANIM_MS)
                .withEndAction { launcher.dragLayer.removeView(this) }
                .start()
        } else {
            launcher.dragLayer.removeView(this)
        }
    }

    /**
     * Not [TYPE_ACTION_POPUP]: Launcher3 assumes every open view of that type is an app's menu and
     * casts it to one, which crashed when the app list refreshed while this menu was open.
     */
    override fun isOfType(type: Int): Boolean = type and MENU_TYPE != 0

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && !launcher.dragLayer.isEventOverView(this, ev)) {
            // A touch outside only closes the menu; it doesn't also act on what's under it.
            close(true)
            return true
        }
        return false
    }

    /** Holds the drag until the finger moves; moving closes the menu and picks the item up. */
    private fun createPreDragCondition() = object : DragOptions.PreDragCondition {
        override fun shouldStartDrag(distanceDragged: Double): Boolean =
            distanceDragged > startDragThreshold

        override fun onPreDragStart(dragObject: DragObject) {
            // A widget's live view was lifted into the drag view. Put it back and drag a snapshot,
            // so letting go without moving leaves the widget where it was.
            dragObject.dragView?.takeIf { it.containsAppWidgetHostView() }
                ?.detachContentView(/* reattachToPreviousParent= */ true)
        }

        override fun onPreDragEnd(dragObject: DragObject, dragStarted: Boolean) {
            if (dragStarted) {
                target.visibility = INVISIBLE
                close(true)
            } else {
                target.visibility = VISIBLE
            }
        }
    }

    companion object {
        private const val ANIM_MS = 150L
        private const val OPEN_SCALE = 0.9f

        /**
         * The floating view type the menu registers as. Only the keyboard drag-and-drop view also
         * uses it, and nothing casts it. It closes when the launcher rebinds, like an app's menu.
         */
        private const val MENU_TYPE = AbstractFloatingView.TYPE_DRAG_DROP_POPUP

        /** Whether a widget menu is open. The resize frame doesn't open by itself while it is. */
        @JvmStatic
        fun isOpen(launcher: Launcher): Boolean {
            // The keyboard drag view shares this type, so check the class without casting to it.
            val open: AbstractFloatingView? = AbstractFloatingView.getOpenView(launcher, MENU_TYPE)
            return open is WidgetMenu
        }

        /**
         * Opens the menu for [view] if it's a widget or widget stack on the home screen, and
         * returns the condition that holds its drag until the finger moves. Returns null, and opens
         * nothing, for anything else or while the home screen is locked.
         */
        @JvmStatic
        fun showForView(launcher: Launcher, view: View): DragOptions.PreDragCondition? {
            if (PreferenceManager2.getInstance(launcher).lockHomeScreen.firstCached()) return null
            val info = view.tag
            val menu = WidgetMenu(launcher, view)
            when {
                view is WidgetStackView && info is WidgetStackInfo -> {
                    menu.addAction(R.drawable.ic_widget_stack_edit, R.string.widget_menu_edit_stack) {
                        WidgetStackFlow.startEdit(launcher, info)
                    }
                    val active = view.activeWidgetView as? LauncherAppWidgetHostView
                    val activeInfo = info.activeWidget()
                    if (active != null && activeInfo != null && isReconfigurable(active)) {
                        menu.addSettings(active, activeInfo)
                    }
                }
                view is LauncherAppWidgetHostView && view !is PendingAppWidgetHostView &&
                    info is LauncherAppWidgetInfo && info.container == Favorites.CONTAINER_DESKTOP -> {
                    if (!info.isCustomWidget) {
                        menu.addAction(R.drawable.ic_widget_stack, R.string.widget_menu_create_stack) {
                            WidgetStackFlow.startCreate(launcher, info)
                        }
                    }
                    val provider = view.appWidgetInfo
                    if (provider != null && provider.resizeMode != AppWidgetProviderInfo.RESIZE_NONE) {
                        menu.addAction(R.drawable.ic_widget_resize, R.string.widget_menu_resize) {
                            val cellLayout = view.parent?.parent as? CellLayout
                            if (cellLayout != null) AppWidgetResizeFrame.showForWidget(view, cellLayout)
                        }
                    }
                    if (isReconfigurable(view)) menu.addSettings(view, info)
                }
                else -> return null
            }
            if (menu.childCount == 0) return null
            AbstractFloatingView.closeOpenViews(
                launcher,
                true,
                AbstractFloatingView.TYPE_ACTION_POPUP or MENU_TYPE,
            )
            menu.show()
            return menu.createPreDragCondition()
        }

        internal fun isReconfigurable(view: LauncherAppWidgetHostView): Boolean =
            (view.appWidgetInfo as? LauncherAppWidgetProviderInfo)?.isReconfigurable == true

        /** Reopens the widget's setup screen, the same way the resize frame's settings button does. */
        internal fun openSettings(launcher: Launcher, view: LauncherAppWidgetHostView, info: LauncherAppWidgetInfo) {
            launcher.setWaitingForResult(PendingRequestArgs.forWidgetInfo(view.appWidgetId, null, info))
            launcher.appWidgetHolder.startConfigActivity(
                launcher,
                view.appWidgetId,
                ActivityCodes.REQUEST_RECONFIGURE_APPWIDGET,
            )
        }

        private fun WidgetMenu.addSettings(view: LauncherAppWidgetHostView, info: LauncherAppWidgetInfo) {
            addAction(R.drawable.ic_setting, R.string.widget_menu_settings) {
                openSettings(launcher, view, info)
            }
        }
    }
}
