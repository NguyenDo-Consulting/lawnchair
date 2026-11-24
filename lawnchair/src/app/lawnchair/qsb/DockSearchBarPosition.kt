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

package app.lawnchair.qsb

import android.view.View

/**
 * NDShades: Where the dock search bar last sat on the home screen, so the search bar preview in
 * settings can show the part of the wallpaper that's behind it. Kept in memory only: settings run
 * in the launcher's process, and the home screen records it again whenever it lays out the bar.
 */
object DockSearchBarPosition {

    /** The bar's vertical center and the size of the screen it was on, in pixels. */
    data class Position(val centerY: Float, val screenWidth: Float, val screenHeight: Float)

    @Volatile
    var current: Position? = null
        private set

    /** Records where [bar] is now. The launcher draws edge to edge, so its root view is the whole screen. */
    fun record(bar: View) {
        val root = bar.rootView
        if (bar.height == 0 || root.width == 0 || root.height == 0) return
        val location = IntArray(2)
        bar.getLocationOnScreen(location)
        current = Position(
            centerY = location[1] + bar.height / 2f,
            screenWidth = root.width.toFloat(),
            screenHeight = root.height.toFloat(),
        )
    }
}
