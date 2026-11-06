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

package app.lawnchair.search.algorithms

import android.content.Context
import android.content.pm.ShortcutInfo
import app.lawnchair.launcher
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.popup.PopupPopulator
import com.android.launcher3.search.StringMatcherUtility
import com.android.launcher3.shortcuts.ShortcutRequest
import java.util.Locale
import me.xdrop.fuzzywuzzy.FuzzySearch
import me.xdrop.fuzzywuzzy.algorithms.WeightedRatio

object SearchUtils {
    // NDShades: locked apps replace the "show hidden apps in search" setting, see filterHiddenApps
    fun normalSearch(apps: List<AppInfo>, query: String, maxResultsCount: Int, hiddenApps: Set<String>, lockedApps: Set<String>): List<AppInfo> {
        // Do an intersection of the words in the query and each title, and filter out all the
        // apps that don't match all of the words in the query.
        val queryTextLower = query.lowercase(Locale.getDefault())
        val matcher = StringMatcherUtility.StringMatcher.getInstance()
        return apps.asSequence()
            .filter { StringMatcherUtility.matches(queryTextLower, it.title.toString(), matcher) }
            .filterHiddenApps(queryTextLower, hiddenApps, lockedApps)
            .take(maxResultsCount)
            .toList()
    }

    fun fuzzySearch(apps: List<AppInfo>, query: String, maxResultsCount: Int, hiddenApps: Set<String>, lockedApps: Set<String>): List<AppInfo> {
        val queryTextLower = query.lowercase(Locale.getDefault())
        val filteredApps = apps.asSequence()
            .filterHiddenApps(queryTextLower, hiddenApps, lockedApps)
            .toList()
        val matches = FuzzySearch.extractSorted(
            queryTextLower,
            filteredApps,
            { it.sectionName + it.title },
            WeightedRatio(),
            65,
        )

        return matches.take(maxResultsCount)
            .map { it.referent }
    }

    fun getShortcuts(app: AppInfo, context: Context): List<ShortcutInfo> {
        val shortcuts = ShortcutRequest(context.launcher, app.user)
            .withContainer(app.targetComponent)
            .query(ShortcutRequest.PUBLISHED)
        return PopupPopulator.sortAndFilterShortcuts(shortcuts)
    }
}

/**
 * NDShades: Hidden apps are always found. Hidden and locked apps are only found when [query] is
 * their full name, upper/lower case and surrounding spaces ignored, so no app is ever out of reach.
 * [query] is lowercase. [normalizeTitle] applies the same normalization the query had.
 * (Upstream: a setting chose never, always, or by full name for all hidden apps.)
 */
fun Sequence<AppInfo>.filterHiddenApps(
    query: String,
    hiddenApps: Set<String>,
    lockedApps: Set<String>,
    normalizeTitle: (String) -> String = { it },
): Sequence<AppInfo> {
    if (hiddenApps.isEmpty() || lockedApps.isEmpty()) return this
    val fullName = query.trim()
    return filter {
        val key = it.toComponentKey().toString()
        key !in hiddenApps ||
            key !in lockedApps ||
            normalizeTitle(it.title.toString()).trim().lowercase(Locale.getDefault()) == fullName
    }
}

/** NDShades: Whether [query] is the full name of a hidden and locked app, see [filterHiddenApps]. */
fun isHiddenLockedAppName(apps: Iterable<AppInfo>, query: String, hiddenApps: Set<String>, lockedApps: Set<String>): Boolean {
    val fullName = query.trim().lowercase(Locale.getDefault())
    if (fullName.isEmpty()) return false
    return apps.any {
        val key = it.toComponentKey().toString()
        key in hiddenApps && key in lockedApps && it.title.toString().trim().lowercase(Locale.getDefault()) == fullName
    }
}
