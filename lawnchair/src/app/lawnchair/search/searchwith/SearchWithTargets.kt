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

package app.lawnchair.search.searchwith

import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.lawnchair.search.adapter.SPACE
import app.lawnchair.search.adapter.SearchActionCompat
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.adapter.SearchTargetFactory
import com.android.launcher3.R

/** NDShades: Layout type of the "Search with" icon row, see [app.lawnchair.allapps.views.SearchResultSearchWith]. */
const val SEARCH_WITH_LAYOUT = "ndshades_search_with"

/** NDShades: `pkg` key of the "Search with" row, like the keys at the end of SearchTargetFactory.kt. */
const val SEARCH_WITH = "search_with"

const val EXTRA_SEARCH_WITH_QUERY = "search_with_query"
const val EXTRA_SEARCH_WITH_PROVIDERS = "search_with_providers"

/**
 * NDShades: The "Search with" header, the icon row for [providers] (from
 * [SearchWithProvider.resolveEnabled]) and a space after them; nothing when no provider is left.
 */
fun SearchTargetFactory.createSearchWithTargets(
    context: Context,
    query: String,
    providers: List<String>,
): List<SearchTargetCompat> {
    if (providers.isEmpty()) return emptyList()

    val label = context.getString(R.string.search_with_label)
    val id = "search_with:$query"
    // The row launches each icon itself, so the action is only there because a target needs one
    val action = SearchActionCompat.Builder(id, label)
        .setIntent(Intent())
        .build()
    val extras = Bundle().apply {
        putString(EXTRA_SEARCH_WITH_QUERY, query)
        putStringArrayList(EXTRA_SEARCH_WITH_PROVIDERS, ArrayList(providers))
    }
    val row = SearchTargetFactory.createSearchTarget(
        id,
        action,
        SEARCH_WITH_LAYOUT,
        SearchTargetCompat.RESULT_TYPE_REDIRECTION,
        SEARCH_WITH,
        extras,
    )
    return listOf(createHeaderTarget(label), row, createHeaderTarget(SPACE))
}
