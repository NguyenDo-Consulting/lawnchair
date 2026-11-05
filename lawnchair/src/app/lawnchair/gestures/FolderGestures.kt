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

import android.content.Context
import android.util.Log
import app.lawnchair.LawnchairApp
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_FOLDER
import com.android.launcher3.model.data.ItemInfo
import kotlinx.coroutines.runBlocking

/**
 * Keeps folder swipe gestures in step with the launcher database. Folder gestures are stored by
 * folder id ([IconGestureKeys.forFolder]), so they're dropped when the folder is deleted, before
 * its id can go to a new folder, and carried over when a grid change gives the folder a new id.
 *
 * Both hooks run on the model thread, so they can wait for the preferences to be written.
 */
object FolderGestures {
    private const val TAG = "FolderGestures"

    /** Called after items were deleted from the launcher database. */
    @JvmStatic
    fun onItemsDeleted(context: Context, items: Collection<ItemInfo>) {
        val keys = items.filter { it.itemType == ITEM_TYPE_FOLDER }
            .map { IconGestureKeys.forFolder(it.id) }
            .toSet()
        if (keys.isEmpty()) return
        runCatching {
            runBlocking {
                PreferenceManager2.getInstance(context).removeGesturesForIcons { it in keys }
            }
        }.onFailure { Log.w(TAG, "Couldn't remove gestures of deleted folders", it) }
    }

    /** Called when a grid size migration copies a folder to the new grid under a new id. */
    @JvmStatic
    fun onFolderIdChanged(oldId: Int, newId: Int) {
        if (oldId == newId || newId < 0) return
        runCatching {
            runBlocking {
                PreferenceManager2.getInstance(LawnchairApp.instance).copyGesturesForIcon(
                    IconGestureKeys.forFolder(oldId),
                    IconGestureKeys.forFolder(newId),
                )
            }
        }.onFailure { Log.w(TAG, "Couldn't move gestures of folder $oldId to $newId", it) }
    }

    /** Removes every folder's gestures, e.g. before importing a whole new home screen. */
    suspend fun removeAll(prefs: PreferenceManager2) {
        prefs.removeGesturesForIcons { it.startsWith(IconGestureKeys.FOLDER_PREFIX) }
    }
}
