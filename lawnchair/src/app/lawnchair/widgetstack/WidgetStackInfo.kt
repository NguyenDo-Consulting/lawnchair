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

import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.util.ContentWriter

/**
 * Several widgets sharing one slot on the home screen. You swipe sideways to switch between them.
 *
 * The stack is a row in the favorites table, like a folder. Its widgets are ordinary widget rows
 * whose container is the stack's id and whose rank is their order. Every widget in a stack has the
 * stack's span, so it is always laid out at the size it is shown at. The widget that is showing is
 * kept in the stack row's options column.
 */
class WidgetStackInfo : ItemInfo() {

    /** The widgets in the stack, in order. */
    @JvmField
    val widgets: MutableList<LauncherAppWidgetInfo> = mutableListOf()

    /** Index into [widgets] of the widget that is showing. */
    @JvmField
    var activeIndex: Int = 0

    init {
        itemType = WidgetStacks.ITEM_TYPE_WIDGET_STACK
    }

    /** Sets the stack's span. A stack is never shrunk to fit on a drop, so its minimum is its span. */
    fun setSpan(spanX: Int, spanY: Int) {
        this.spanX = spanX
        this.spanY = spanY
        minSpanX = spanX
        minSpanY = spanY
    }

    /** The widget that is showing, if the stack has any. */
    fun activeWidget(): LauncherAppWidgetInfo? =
        widgets.getOrNull(activeIndex.coerceIn(0, (widgets.size - 1).coerceAtLeast(0)))

    /** Keeps [activeIndex] inside [widgets]. */
    fun clampActiveIndex() {
        activeIndex = if (widgets.isEmpty()) 0 else activeIndex.coerceIn(0, widgets.size - 1)
    }

    override fun onAddToDatabase(writer: ContentWriter) {
        super.onAddToDatabase(writer)
        writer.put(Favorites.OPTIONS, activeIndex)
    }

    override fun dumpProperties(): String =
        super.dumpProperties() + " widgets=${widgets.size} activeIndex=$activeIndex"
}
