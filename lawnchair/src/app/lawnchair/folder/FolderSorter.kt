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

package app.lawnchair.folder

import android.content.Context
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import java.text.Collator

object FolderSorter {

    /**
     * The folder's contents in the order its sort mode wants, or null when nothing should move:
     * the folder is free-sorted, it is already in order, or usage counts are still loading.
     */
    @JvmStatic
    fun sortedContents(context: Context, info: FolderInfo): List<ItemInfo>? {
        val contents = info.getContents()
        val sorted = sort(context, contents, FolderSortMode.of(info)) ?: return null
        return sorted.takeIf { it != contents }
    }

    /**
     * Returns [items] ordered by [mode], or null to keep their current order.
     */
    fun <T : ItemInfo> sort(context: Context, items: List<T>, mode: FolderSortMode): List<T>? {
        val byLabel = labelComparator<T>()
        return when (mode) {
            FolderSortMode.FREE -> null

            FolderSortMode.ALPHABETICAL -> items.sortedWith(byLabel)

            FolderSortMode.MOST_USED -> {
                val counts = FolderUsageStats.getCounts(context) ?: return null
                items.sortedWith(compareByDescending<T> { openCount(it, counts) }.then(byLabel))
            }
        }
    }

    /**
     * A pinned shortcut (like a chat with one person) counts its own opens. Anything else counts
     * every time its app came to the foreground.
     */
    private fun openCount(item: ItemInfo, counts: FolderUsageStats.Counts): Int {
        val packageName = item.targetPackage ?: return 0
        val shortcutId = (item as? WorkspaceItemInfo)?.deepShortcutId
        return if (shortcutId != null) {
            counts.shortcuts[FolderUsageStats.shortcutKey(packageName, shortcutId)] ?: 0
        } else {
            counts.apps[packageName] ?: 0
        }
    }

    private fun <T : ItemInfo> labelComparator(): Comparator<T> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        return compareBy(collator) { it.title?.toString()?.trim().orEmpty() }
    }
}
