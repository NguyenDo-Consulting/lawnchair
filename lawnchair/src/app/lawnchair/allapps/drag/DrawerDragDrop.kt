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

import androidx.lifecycle.lifecycleScope
import app.lawnchair.LawnchairLauncher
import app.lawnchair.data.folder.service.FolderService
import app.lawnchair.launcherNullable
import com.android.launcher3.DropTarget.DragObject
import com.android.launcher3.Launcher
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.folder.Folder
import com.android.launcher3.model.data.ItemInfo
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.launch

/**
 * Drag and drop inside the app drawer: a drag that starts in the drawer keeps it open, so an app
 * can be dropped into a drawer folder, on another app to make a folder, or into another tab. The
 * work happens in [DrawerDropTarget]. These are the entry points for the Launcher3 hooks.
 */
object DrawerDragDrop {

    /** Workspace.onDragStart: whether this drag stays in the drawer instead of going home. */
    @JvmStatic
    fun keepsDrawerOpen(launcher: Launcher, dragObject: DragObject, options: DragOptions): Boolean =
        (launcher as? LawnchairLauncher)?.drawerDropTarget?.canKeepDrawerOpen(dragObject, options) == true

    /** LauncherDragController.exitDrag: whether a drag is still in the open drawer. */
    @JvmStatic
    fun isDrawerDragActive(launcher: Launcher): Boolean =
        (launcher as? LawnchairLauncher)?.drawerDropTarget?.isDrawerDragActive == true

    /**
     * Folder.onDropInAppDrawer: an app was dropped into an open drawer folder. Its new place is
     * saved when the folder closes.
     */
    @JvmStatic
    fun onDroppedInDrawerFolder(folder: Folder, item: ItemInfo) {
        (folder.context.launcherNullable)?.drawerDropTarget?.onDroppedInFolder(folder, item)
    }

    /**
     * Folder.onBackKey: saves the name typed for a drawer folder once the folder has closed.
     * Saving while it's open redraws its row in the drawer, and the folder would close toward an
     * icon that's no longer there.
     */
    @JvmStatic
    fun onDrawerFolderRenamed(folder: Folder) {
        val folderId = folder.info.id
        if (folderId == ItemInfo.NO_ID) return
        val launcher = folder.context.launcherNullable ?: return
        // One save per close, with the last name typed.
        if (!foldersAwaitingClose.add(folder)) return
        folder.addOnFolderStateChangedListener(object : Folder.OnFolderStateChangedListener {
            override fun onFolderStateChanged(newState: Int) {
                if (newState != Folder.STATE_CLOSED) return
                folder.removeOnFolderStateChangedListener(this)
                foldersAwaitingClose.remove(folder)
                val title = folder.info.title?.toString().orEmpty()
                launcher.lifecycleScope.launch {
                    FolderService.INSTANCE.get(launcher).renameFolderInfo(folderId, title)
                }
            }
        })
    }

    private val foldersAwaitingClose: MutableSet<Folder> = Collections.newSetFromMap(WeakHashMap())
}
