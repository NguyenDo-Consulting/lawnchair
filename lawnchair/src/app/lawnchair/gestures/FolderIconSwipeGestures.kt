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
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT
import com.android.launcher3.LauncherState
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.ItemInfo

/**
 * Swipe gestures on a home screen or dock folder icon, like the ones on app icons. Set up from
 * the ⋮ button of the open folder.
 */
class FolderIconSwipeGestures(
    private val icon: FolderIcon,
    private val launcher: Launcher,
) {
    private val prefs = PreferenceManager2.getInstance(launcher)
    private var listener: IconGestureListener? = null
    private var listenerFolderId = ItemInfo.NO_ID
    private var handlingTouch = false

    /**
     * Passes a touch on the folder icon to the gesture detector. Returns true once a swipe
     * triggered its action, after which the icon should cancel its click and long press.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            handlingTouch = currentListener()?.hasAnyGestureConfigured() == true
        }
        val listener = listener
        if (!handlingTouch || listener == null) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                handlingTouch = false
            }
            return false
        }
        val handled = listener.onTouchEvent(event)
        if (handled || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            handlingTouch = false
        }
        return handled
    }

    fun hasHorizontalGesture(): Boolean = currentListener()?.hasHorizontalGestureConfigured() == true

    fun hasVerticalGesture(): Boolean = currentListener()?.hasVerticalGestureConfigured() == true

    /**
     * Returns the gesture listener for the folder, or null where folder gestures don't apply.
     * A new folder may get its id after its icon is made, so the listener follows the id.
     */
    private fun currentListener(): IconGestureListener? {
        val info = icon.mInfo ?: return null
        if (info.container != CONTAINER_DESKTOP && info.container != CONTAINER_HOTSEAT) return null
        if (info.id == ItemInfo.NO_ID) return null
        if (launcher.isInState(LauncherState.EDIT_MODE)) return null
        if (listener == null || listenerFolderId != info.id) {
            listener = IconGestureListener(icon, prefs, IconGestureKeys.forFolder(info.id))
            listenerFolderId = info.id
        }
        return listener
    }
}
