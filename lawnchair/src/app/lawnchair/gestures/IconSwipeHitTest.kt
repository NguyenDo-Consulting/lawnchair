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

package app.lawnchair.gestures

import android.view.MotionEvent
import com.android.launcher3.Launcher
import com.android.launcher3.folder.Folder

/**
 * Tells the launcher's swipe controllers (swipe up to the app drawer, swipe down for
 * notifications) whether a touch belongs to an icon with a swipe gesture instead.
 */
object IconSwipeHitTest {

    /**
     * Returns whether [ev], in drag layer coordinates, is on an open folder, workspace or dock
     * icon with a swipe gesture configured. [vertical] picks up/down gestures, otherwise
     * left/right.
     */
    @JvmStatic
    fun isTouchOnIconWithSwipeGesture(launcher: Launcher, ev: MotionEvent, vertical: Boolean): Boolean {
        val dragLayer = launcher.dragLayer ?: return false
        // An open folder is on top, so its icons get the touch.
        val openFolder = Folder.getOpen(launcher)
        if (openFolder != null && dragLayer.isEventOverView(openFolder, ev)) {
            val content = openFolder.content
            val coord = floatArrayOf(ev.x, ev.y)
            dragLayer.mapCoordInSelfToDescendant(openFolder, coord)
            return content.isTouchOnIconWithSwipeGesture(
                coord[0] - content.left,
                coord[1] - content.top,
                vertical,
            )
        }
        val workspace = launcher.workspace
        if (workspace != null) {
            val coord = floatArrayOf(ev.x, ev.y)
            dragLayer.mapCoordInSelfToDescendant(workspace, coord)
            if (workspace.isTouchOnIconWithSwipeGesture(coord[0], coord[1], vertical)) {
                return true
            }
        }
        val hotseat = launcher.hotseat
        if (hotseat != null && dragLayer.isEventOverView(hotseat, ev)) {
            val coord = floatArrayOf(ev.x, ev.y)
            dragLayer.mapCoordInSelfToDescendant(hotseat, coord)
            return hotseat.isTouchOnIconWithSwipeGesture(coord[0], coord[1], vertical)
        }
        return false
    }
}
