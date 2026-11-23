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

package app.lawnchair.util

import com.android.launcher3.folder.ClippedFolderIconLayoutRule.ICON_OVERLAP_FACTOR
import kotlin.math.ceil

object DockCellSize {
    @JvmStatic
    fun calculate(iconSize: Int, labelHeight: Int, showLabels: Boolean, minimumTouchSize: Int): Int {
        val base = ceil(iconSize * 2 * ICON_OVERLAP_FACTOR).toInt() - iconSize / 2
        val height = if (showLabels) base + labelHeight else base
        return maxOf(height, minimumTouchSize)
    }
}
