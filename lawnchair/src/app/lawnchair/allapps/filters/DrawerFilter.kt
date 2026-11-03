package app.lawnchair.allapps.filters

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

/**
 * A user-made filter of the app drawer, shown as a chip above the app list. Apps are kept as
 * [com.android.launcher3.util.ComponentKey] strings. An app can be in several filters.
 */
@Serializable
data class DrawerFilter(
    val id: String,
    val title: String,
    val componentKeys: List<String> = emptyList(),
)

/** The filter chip currently selected in the app drawer. */
object DrawerFilterState {
    /** Id of the selected [DrawerFilter], or null for the "All" chip. */
    val activeFilterId = MutableStateFlow<String?>(null)
}
