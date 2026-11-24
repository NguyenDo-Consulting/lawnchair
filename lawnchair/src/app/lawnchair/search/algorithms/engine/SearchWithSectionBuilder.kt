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

package app.lawnchair.search.algorithms.engine

import android.content.Context
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.adapter.SearchTargetFactory
import app.lawnchair.search.searchwith.createSearchWithTargets

/**
 * NDShades: The "Search with" row. In its own file because [SectionBuilder] is sealed, so its
 * builders must be in this package; the upstream ones stay in SectionBuilder.kt.
 */
data object SearchWithSectionBuilder : SectionBuilder {
    override fun build(
        context: Context,
        factory: SearchTargetFactory,
        results: List<SearchResult>,
    ): List<SearchTargetCompat> {
        val searchWith = results.filterIsInstance<SearchResult.Action.SearchWith>().firstOrNull()
            ?: return emptyList()
        return factory.createSearchWithTargets(context, searchWith.query, searchWith.providers)
    }
}
