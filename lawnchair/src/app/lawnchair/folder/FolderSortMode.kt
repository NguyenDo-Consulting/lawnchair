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

import androidx.annotation.StringRes
import com.android.launcher3.R
import com.android.launcher3.model.ModelWriter
import com.android.launcher3.model.data.FolderInfo

/**
 * How the apps inside a single folder are ordered.
 */
enum class FolderSortMode(val id: Int, @StringRes val labelRes: Int) {
    /** The order the user arranged by dragging. */
    FREE(0, R.string.folder_sort_free),
    ALPHABETICAL(1, R.string.folder_sort_alphabetical),
    MOST_USED(2, R.string.folder_sort_most_used),
    ;

    companion object {
        // Home screen folders keep their sort mode in FolderInfo.options, which is saved with the
        // folder in the launcher database. Upstream flags use the low bits, so this uses bits 16-17.
        private const val OPTIONS_SHIFT = 16
        private const val OPTIONS_MASK = 0b11 shl OPTIONS_SHIFT

        fun fromId(id: Int): FolderSortMode = entries.firstOrNull { it.id == id } ?: FREE

        @JvmStatic
        fun of(info: FolderInfo): FolderSortMode = fromId((info.options and OPTIONS_MASK) shr OPTIONS_SHIFT)

        /**
         * Stores [mode] on [info], saving it through [writer] when it changed.
         */
        @JvmStatic
        fun set(info: FolderInfo, mode: FolderSortMode, writer: ModelWriter?) {
            val newOptions = (info.options and OPTIONS_MASK.inv()) or (mode.id shl OPTIONS_SHIFT)
            if (newOptions == info.options) return
            info.options = newOptions
            writer?.updateItemInDatabase(info)
        }
    }
}
