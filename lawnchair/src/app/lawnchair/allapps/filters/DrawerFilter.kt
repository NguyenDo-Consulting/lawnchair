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

package app.lawnchair.allapps.filters

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

/**
 * A user-made filter of the app drawer, shown as a tab above the app list. Apps are kept as
 * [com.android.launcher3.util.ComponentKey] strings. An app can be in several filters.
 */
@Serializable
data class DrawerFilter(
    val id: String,
    val title: String,
    val componentKeys: List<String> = emptyList(),
)

/** The filter tab currently selected in the app drawer. */
object DrawerFilterState {
    /** Id of the selected [DrawerFilter], or null for the "All" tab. */
    val activeFilterId = MutableStateFlow<String?>(null)

    /**
     * With "Remember position", how far each tab's list was scrolled when it was left, in pixels,
     * by [DrawerFilter] id (null for "All"). Tabs that aren't in here open at the top.
     */
    val scrollOffsets = HashMap<String?, Int>()
}
