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

package app.lawnchair.allapps.drag

import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import app.lawnchair.LawnchairLauncher
import app.lawnchair.allapps.filters.DrawerFilterState
import app.lawnchair.data.folder.service.FolderService
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherState
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What a drop in the app drawer changes. Apps are [com.android.launcher3.util.ComponentKey] strings. */
internal sealed interface DrawerDropAction {
    /** Puts the app in a drawer folder, out of any other folder. */
    data class MoveToFolder(val folderId: Int) : DrawerDropAction

    /** Makes a new drawer folder of the app dropped on and the dragged app, then opens it to be named. */
    data class CreateFolder(val targetKey: String) : DrawerDropAction

    /** Takes the app out of the drawer folder it was dragged from. */
    data object RemoveFromFolder : DrawerDropAction
}

/** Saves the result of a drop in the app drawer to the drawer folders and tabs. */
internal class DrawerOrganizer(private val launcher: LawnchairLauncher) {

    private val folderService get() = FolderService.INSTANCE.get(launcher)
    private val prefs2 get() = PreferenceManager2.getInstance(launcher)

    /**
     * Applies a drop of the app [key]. [fromTab] and [toTab] are the tabs it was dragged from and
     * dropped in ([app.lawnchair.allapps.filters.DrawerFilter] ids, null for "All"). A different
     * tab moves the app there.
     */
    fun apply(key: String, action: DrawerDropAction?, fromTab: String?, toTab: String?) {
        afterOpenFolderCloses { save(key, action, fromTab, toTab) }
    }

    /**
     * Runs [block] now, or once an open drawer folder has closed. Saving redraws the drawer's
     * folder rows, and an open folder would then close toward an icon that's gone. A drop can
     * land here with a folder open, e.g. during its open animation, before it takes drops.
     */
    private fun afterOpenFolderCloses(block: () -> Unit) {
        val folder = Folder.getOpen(launcher)?.takeIf { it.isInAppDrawer }
        if (folder == null) {
            block()
            return
        }
        folder.addOnFolderStateChangedListener(object : Folder.OnFolderStateChangedListener {
            override fun onFolderStateChanged(newState: Int) {
                if (newState != Folder.STATE_CLOSED) return
                folder.removeOnFolderStateChangedListener(this)
                block()
            }
        })
        folder.close(true)
    }

    private fun save(key: String, action: DrawerDropAction?, fromTab: String?, toTab: String?) {
        launcher.lifecycleScope.launch {
            if (fromTab != toTab) moveBetweenTabs(key, fromTab, toTab)
            when (action) {
                is DrawerDropAction.MoveToFolder -> folderService.moveItemToFolder(key, action.folderId)
                is DrawerDropAction.RemoveFromFolder -> folderService.removeItemFromFolders(key)
                is DrawerDropAction.CreateFolder -> {
                    val folderId = folderService.createFolderWithItems("", listOf(action.targetKey, key))
                    openToName(folderId)
                }
                null -> Unit
            }
        }
    }

    /**
     * Saves an open drawer folder's apps in [orderedKeys] order after the app [key] was dropped
     * into it, and moves the app between tabs as [apply] does.
     */
    fun placeInFolder(key: String, folderId: Int, orderedKeys: List<String>, fromTab: String?, toTab: String?) {
        launcher.lifecycleScope.launch {
            if (fromTab != toTab) moveBetweenTabs(key, fromTab, toTab)
            folderService.setFolderItems(folderId, orderedKeys)
        }
    }

    private suspend fun moveBetweenTabs(key: String, fromTab: String?, toTab: String?) {
        val filters = prefs2.drawerFilters.get().first()
        val updated = filters.map { filter ->
            when (filter.id) {
                fromTab -> filter.copy(componentKeys = filter.componentKeys - key)
                toTab -> if (key in filter.componentKeys) filter else filter.copy(componentKeys = filter.componentKeys + key)
                else -> filter
            }
        }
        if (updated != filters) prefs2.drawerFilters.set(updated)
    }

    /**
     * Opens a new drawer folder with its name field ready, once the drawer list shows it. Gives up
     * if the drawer closes or the "All" tab isn't shown, e.g. the user moved on.
     */
    private suspend fun openToName(folderId: Int) {
        val icon = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            var found: FolderIcon? = null
            while (found == null) {
                delay(FRAME_MS)
                if (!stillInAllTab()) return@withTimeoutOrNull null
                found = findFolderIcon(folderId)
            }
            found
        } ?: return
        if (!stillInAllTab() || icon.folder.isOpen) return
        icon.folder.openToNameInAppDrawer()
    }

    private fun stillInAllTab(): Boolean = launcher.isInState(LauncherState.ALL_APPS) &&
        !launcher.dragController.isDragging &&
        DrawerFilterState.activeFilterId.value == null

    /** The folder's icon once it's laid out, scrolling the list to it if needed. */
    private fun findFolderIcon(folderId: Int): FolderIcon? {
        val appsView = launcher.appsView ?: return null
        val rv = appsView.activeRecyclerView?.takeIf { it.apps === appsView.personalAppList } ?: return null
        val position = rv.apps.adapterItems.indexOfFirst { it.folderInfo?.id == folderId }
        if (position < 0) return null
        val holder = rv.findViewHolderForAdapterPosition(position)
        if (holder == null) {
            rv.scrollToPosition(position)
            return null
        }
        return (holder.itemView as? ViewGroup)?.getChildAt(0) as? FolderIcon
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 2000L
        const val FRAME_MS = 32L
    }
}
