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

package app.lawnchair.allapps

import androidx.annotation.StringRes
import com.android.launcher3.R

/**
 * How the app drawer orders its apps. Drawer folders stay on top in the user's order, and the apps
 * inside them follow each folder's own sort.
 */
enum class DrawerSortMode(val id: String, @StringRes val labelRes: Int) {
    ALPHABETICAL("alphabetical", R.string.drawer_sort_alphabetical),

    /** Newest first. */
    INSTALL_DATE("install_date", R.string.drawer_sort_install_date),

    /** Most opened in the last 14 days first, from [app.lawnchair.folder.FolderUsageStats]. */
    MOST_USED("most_used", R.string.drawer_sort_most_used),

    /** By the hue of each icon's main color, with white, gray and black icons last. */
    COLOR("color", R.string.drawer_sort_color),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: ALPHABETICAL
    }
}
