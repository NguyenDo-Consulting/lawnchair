/*
 * Modifications copyright 2026, Nguyen-Do Consulting
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

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lawnchair.allapps.filters.DrawerFilter
import app.lawnchair.allapps.filters.DrawerFilterState
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.model.FolderViewModel
import app.lawnchair.folder.FolderSortMode
import app.lawnchair.folder.FolderSorter
import app.lawnchair.folder.FolderUsageStats
import app.lawnchair.launcher
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.categorizeAppsWithSystemAndGoogle
import app.lawnchair.util.observeOnce
import com.android.launcher3.InvariantDeviceProfile.OnIDPChangeListener
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.allapps.PrivateProfileManager
import com.android.launcher3.allapps.WorkProfileManager
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.views.ActivityContext
import com.patrykmichalik.opto.core.onEach
import java.util.function.Predicate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach as onEachFlow

@Suppress("SYNTHETIC_PROPERTY_WITHOUT_JAVA_ORIGIN")
class LawnchairAlphabeticalAppsList<T> @JvmOverloads constructor(
    private val context: T,
    private val appsStore: AllAppsStore<T>,
    workProfileManager: WorkProfileManager?,
    privateProfileManager: PrivateProfileManager?,
    // NDShades: false for the off-screen drawer tab lists (DrawerTabNeighbors), which show a
    // fixed tab set with showTab instead of the selected one
    private val followsSelectedTab: Boolean = true,
) : AlphabeticalAppsList<T>(context, appsStore, workProfileManager, privateProfileManager),
    OnIDPChangeListener,
    DefaultLifecycleObserver
    where T : Context, T : ActivityContext {

    private var hiddenApps: Set<String> = setOf()
    private val prefs2 = PreferenceManager2.getInstance(context)
    private val prefs = PreferenceManager.getInstance(context)

    // NDShades: Drawer filter tabs, see [DrawerFilter]
    private var drawerFilters: List<DrawerFilter> = emptyList()
    private var hideFilteredAppsFromAll = false
    private var activeFilterId: String? = null

    // NDShades: drawer sort order, see [DrawerSortMode]
    private var sortMode = DrawerSortMode.ALPHABETICAL

    private val viewModel = FolderViewModel(
        (context as? ComponentActivity)?.application ?: context.launcher.application,
    )
    private val folderList = mutableListOf<FolderEntry>()
    private val filteredList = mutableListOf<AppInfo>()

    /** NDShades: Rebuilds the list when new usage counts would reorder a "Most used" folder or drawer. */
    private val folderUsageListener = Runnable {
        if (sortMode == DrawerSortMode.MOST_USED ||
            folderList.any { it.sortMode == FolderSortMode.MOST_USED }
        ) {
            onAppsUpdated()
        }
    }

    init {
        FolderUsageStats.addListener(folderUsageListener) // NDShades: per-folder sorting
        context.launcher.deviceProfile.inv.addOnChangeListener(this)
        (context as? LifecycleOwner)?.lifecycle?.addObserver(this)
        try {
            prefs2.hiddenApps.onEach(launchIn = context.launcher.lifecycleScope) {
                hiddenApps = it
                onAppsUpdated()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize hidden apps", t)
        }
        observeFolders()
        observeDrawerFilters() // NDShades: app drawer filters
    }

    // NDShades: app drawer filters
    private fun observeDrawerFilters() {
        val scope = context.launcher.lifecycleScope
        prefs2.drawerFilters.onEach(launchIn = scope) {
            drawerFilters = it
            onAppsUpdated()
        }
        prefs2.hideFilteredAppsFromAll.onEach(launchIn = scope) {
            hideFilteredAppsFromAll = it
            onAppsUpdated()
        }
        DrawerFilterState.activeFilterId
            .onEachFlow {
                // Already shown when the switch went through showTab.
                if (followsSelectedTab && activeFilterId != it) {
                    activeFilterId = it
                    onAppsUpdated()
                }
            }
            .launchIn(scope)
        // NDShades: drawer sort order
        prefs2.drawerSortMode.onEach(launchIn = scope) {
            sortMode = DrawerSortMode.fromId(it)
            onAppsUpdated()
        }
    }

    // NDShades: drawer sort order. Letters only make sense when the drawer is sorted by name.
    override fun useLetterSections() = sortMode == DrawerSortMode.ALPHABETICAL

    /**
     * NDShades: Shows the apps of a drawer tab (a [DrawerFilter] id, or null for "All") right away,
     * so the list is up to date for the next frame. [DrawerTabSwitcher] uses it on the main list
     * before setting [DrawerFilterState.activeFilterId], [DrawerTabNeighbors] on its own lists.
     */
    fun showTab(filterId: String?) {
        if (activeFilterId == filterId) return
        activeFilterId = filterId
        onAppsUpdated()
    }

    /** NDShades: The filter last passed to [updateItemFilter], e.g. personal apps only. */
    var baseItemFilter: Predicate<ItemInfo>? = null
        private set

    // NDShades: the selected drawer filter tab, if any
    private val activeFilter: DrawerFilter?
        get() = activeFilterId?.let { id -> drawerFilters.firstOrNull { it.id == id } }

    /**
     * NDShades: Whether an app is shown under the selected filter tab. Filters only apply to the
     * personal profile, so work and private space apps are always shown.
     */
    private fun matchesDrawerFilter(info: AppInfo, componentKey: String): Boolean {
        if (info.user != Process.myUserHandle()) return true
        val filter = activeFilter
        return when {
            filter != null -> componentKey in filter.componentKeys
            hideFilteredAppsFromAll -> drawerFilters.none { componentKey in it.componentKeys }
            else -> true
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.launcher.deviceProfile.inv.removeOnChangeListener(this)
        FolderUsageStats.removeListener(folderUsageListener) // NDShades: per-folder sorting
    }

    private fun observeFolders() {
        viewModel.folders.observeOnce(context as LifecycleOwner) { folders ->
            if (folders != null) {
                folderList.clear()
                folderList.addAll(folders)
                updateAdapterItems()
            }
        }
    }

    override fun updateItemFilter(itemFilter: Predicate<ItemInfo>?) {
        baseItemFilter = itemFilter // NDShades: reused by DrawerTabNeighbors
        mItemFilter = Predicate { info ->
            require(info is AppInfo) { "`info` must be an instance of `AppInfo`." }
            val componentKey = info.toComponentKey().toString()
            (itemFilter?.test(info) != false) &&
                !hiddenApps.contains(componentKey) &&
                matchesDrawerFilter(info, componentKey) // NDShades: app drawer filters
        }
        onAppsUpdated()
    }

    override fun addAppsWithSections(unsortedApps: List<AppInfo?>?, startPosition: Int): Int {
        if (unsortedApps.isNullOrEmpty()) return startPosition
        // NDShades: drawer sort order. Upstream hands the apps over in alphabetical order.
        val appList = DrawerSorter.sort(context, unsortedApps.filterNotNull(), sortMode, appsStore.apps)
        val drawerListDefault = prefs.drawerList.get()
        filteredList.clear()
        var position = startPosition

        // Show app drawer folders only on main profile, to prevent state complexity
        if (isWorkOrPrivateSpace(appList)) return super.addAppsWithSections(appList, position)

        // NDShades: A filter tab shows its apps as a plain list, without drawer folders or
        // categories
        if (activeFilter != null) return super.addAppsWithSections(appList, position)

        if (!drawerListDefault) {
            val validApps = appList.mapNotNull { it }
            val finalCategorizedApps = categorizeAppsWithSystemAndGoogle(validApps, context)

            finalCategorizedApps.forEach { (category, apps) ->
                if (apps.size == 1) {
                    mAdapterItems.add(AdapterItem.asApp(apps.first()))
                } else {
                    val folderInfo = FolderInfo().apply {
                        title = category
                        apps.forEach { add(it) }
                    }
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                }
                position++
            }
        } else {
            folderList.forEach { folderEntry ->
                // NDShades: sorted by the folder's sort mode (upstream: resolvedApps directly)
                val storedOrder = folderEntry.itemComponentKeys.mapNotNull { keyString ->
                    val componentKey = ComponentKey.fromString(keyString) ?: return@mapNotNull null
                    appsStore.getApp(componentKey) as? AppInfo
                }
                val resolvedApps = FolderSorter.sort(context, storedOrder, folderEntry.sortMode)
                    ?: storedOrder

                if (resolvedApps.size > 1) {
                    val folderInfo = FolderInfo().apply {
                        id = folderEntry.id
                        title = folderEntry.title
                        resolvedApps.forEach { add(it) }
                    }
                    // NDShades: Lets the long-press edit sheet show the current sort mode
                    FolderSortMode.set(folderInfo, folderEntry.sortMode, null)
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                    position++

                    if (prefs.folderApps.get()) {
                        filteredList.addAll(resolvedApps)
                    }
                }
            }
            val remainingApps = appList.filterNot { app -> filteredList.contains(app) && prefs.folderApps.get() }
            position = super.addAppsWithSections(remainingApps, position)
        }

        return position
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }
}
