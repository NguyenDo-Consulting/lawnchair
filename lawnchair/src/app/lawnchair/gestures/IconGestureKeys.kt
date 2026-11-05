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

import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.shortcuts.ShortcutKey

/**
 * Keys that icon swipe gestures are stored under in
 * [app.lawnchair.preferences2.PreferenceManager2.setGestureForIcon].
 */
object IconGestureKeys {
    private const val SHORTCUT_PREFIX = "shortcut:"
    const val FOLDER_PREFIX = "folder:"

    /**
     * Returns the gesture key for an icon. A pinned shortcut has its own gestures, so that e.g.
     * every conversation shortcut of a messaging app can do something different. Anything
     * else uses the app's component key.
     */
    @JvmStatic
    fun forItem(info: ItemInfo): String? {
        if (info.itemType == ITEM_TYPE_DEEP_SHORTCUT) {
            return forShortcut(ShortcutKey.fromItemInfo(info))
        }
        return info.componentKey?.toString()
    }

    @JvmStatic
    fun forShortcut(key: ShortcutKey): String = SHORTCUT_PREFIX + key

    /**
     * Returns the gesture key for a home screen or dock folder. Folders have no other lasting
     * identity, so their gestures follow the folder's id in the launcher database.
     */
    @JvmStatic
    fun forFolder(folderId: Int): String = FOLDER_PREFIX + folderId
}
