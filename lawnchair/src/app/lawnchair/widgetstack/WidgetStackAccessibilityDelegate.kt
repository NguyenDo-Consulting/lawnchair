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

import android.view.KeyEvent
import android.view.View
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.QuickstepAccessibilityDelegate
import com.android.launcher3.R
import com.android.launcher3.accessibility.BaseAccessibilityDelegate.DragType
import com.android.launcher3.accessibility.LauncherAccessibilityDelegate
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.launcher3.widget.LauncherAppWidgetHostView
import com.android.launcher3.widget.PendingAppWidgetHostView

/**
 * TalkBack actions for widget stacks, which TalkBack users can't long-press or swipe.
 *
 * - A stack: Move and Remove (the whole stack), Edit stack, Widget settings for the widget showing,
 *   and Next / Previous widget.
 * - A widget on the home screen: Create stack, next to Launcher3's own actions.
 * - A widget inside a stack: no launcher actions. Launcher3 would offer to move it to the home
 *   screen or remove it on its own, which only Edit may do (and which would break the stack).
 *
 * The actions that open something are left out while the home screen is locked, like the menu.
 */
class WidgetStackAccessibilityDelegate(
    private val launcher: QuickstepLauncher,
) : QuickstepAccessibilityDelegate(launcher) {

    init {
        add(ACTION_CREATE, R.string.widget_menu_create_stack)
        add(ACTION_EDIT, R.string.widget_menu_edit_stack)
        add(ACTION_SETTINGS, R.string.widget_menu_settings)
        add(ACTION_NEXT, R.string.widget_stack_next)
        add(ACTION_PREVIOUS, R.string.widget_stack_previous)
    }

    private fun add(id: Int, label: Int) {
        mActions.put(id, LauncherAction(id, label, KeyEvent.KEYCODE_UNKNOWN))
    }

    private val isLocked: Boolean
        get() = PreferenceManager2.getInstance(launcher).lockHomeScreen.firstCached()

    private fun isInStack(item: ItemInfo) =
        item is LauncherAppWidgetInfo && WidgetStacks.isPossibleStackChild(item.container)

    override fun itemSupportsAccessibleDrag(item: ItemInfo): Boolean =
        item is WidgetStackInfo || (!isInStack(item) && super.itemSupportsAccessibleDrag(item))

    override fun getSupportedActions(host: View, item: ItemInfo, out: MutableList<LauncherAction>) {
        if (isInStack(item)) return
        super.getSupportedActions(host, item, out)
        when {
            item is WidgetStackInfo && host is WidgetStackView -> {
                // Launcher3's Remove only knows widgets, folders and icons; a stack is removed whole.
                out.add(mActions.get(LauncherAccessibilityDelegate.REMOVE))
                if (!isLocked) out.add(mActions.get(ACTION_EDIT))
                val active = host.activeWidgetView as? LauncherAppWidgetHostView
                if (active != null && WidgetMenu.isReconfigurable(active)) out.add(mActions.get(ACTION_SETTINGS))
                if (host.canStep(1)) out.add(mActions.get(ACTION_NEXT))
                if (host.canStep(-1)) out.add(mActions.get(ACTION_PREVIOUS))
            }
            item is LauncherAppWidgetInfo && item.container == Favorites.CONTAINER_DESKTOP &&
                host is LauncherAppWidgetHostView && host !is PendingAppWidgetHostView &&
                !item.isCustomWidget && !isLocked -> out.add(mActions.get(ACTION_CREATE))
        }
    }

    override fun performAction(host: View, item: ItemInfo, action: Int, fromKeyboard: Boolean): Boolean {
        if (isInStack(item)) return false
        if (item is WidgetStackInfo && host is WidgetStackView) {
            when (action) {
                LauncherAccessibilityDelegate.REMOVE -> {
                    launcher.removeItem(host, item, true, "widget stack removed by accessibility")
                    launcher.workspace.stripEmptyScreens()
                    announceConfirmation(launcher.getString(R.string.item_removed))
                    return true
                }
                ACTION_EDIT -> {
                    WidgetStackFlow.startEdit(launcher, item)
                    return true
                }
                ACTION_SETTINGS -> {
                    val active = host.activeWidgetView as? LauncherAppWidgetHostView ?: return false
                    val activeInfo = item.activeWidget() ?: return false
                    WidgetMenu.openSettings(launcher, active, activeInfo)
                    return true
                }
                ACTION_NEXT -> return host.step(1)
                ACTION_PREVIOUS -> return host.step(-1)
            }
        }
        if (action == ACTION_CREATE && item is LauncherAppWidgetInfo) {
            WidgetStackFlow.startCreate(launcher, item)
            return true
        }
        return super.performAction(host, item, action, fromKeyboard)
    }

    override fun beginAccessibleDrag(item: View, info: ItemInfo, fromKeyboard: Boolean): Boolean {
        val started = super.beginAccessibleDrag(item, info, fromKeyboard)
        // Placed like a widget: every cell under it must be free, not just one like an icon.
        if (started && info is WidgetStackInfo) mDragInfo?.dragType = DragType.WIDGET
        return started
    }

    companion object {
        private val ACTION_CREATE = R.id.action_widget_stack_create
        private val ACTION_EDIT = R.id.action_widget_stack_edit
        private val ACTION_SETTINGS = R.id.action_widget_stack_settings
        private val ACTION_NEXT = R.id.action_widget_stack_next
        private val ACTION_PREVIOUS = R.id.action_widget_stack_previous
    }
}
