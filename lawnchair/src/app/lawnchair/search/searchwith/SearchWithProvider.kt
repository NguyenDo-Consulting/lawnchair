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

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.net.toUri
import com.android.launcher3.R
import java.util.Locale

/**
 * NDShades: The search providers: the apps and sites the drawer search can hand a query to, shown
 * as the "Search with" row under the app results. Each searches in its app when the app is
 * installed and takes the search, else on its website. The list is Nova's plus the search engines
 * of Lawnchair's single-provider list ([app.lawnchair.qsb.providers.QsbSearchProvider]), which this
 * replaces. Their order here is the default order; the user picks and orders them in settings,
 * saved in [app.lawnchair.preferences2.PreferenceManager2.searchWithProviders].
 */
enum class SearchWithProvider(
    val id: String,
    @StringRes val label: Int,
    /** The app that searches, or null for a website with no app. */
    val packageName: String?,
    val defaultEnabled: Boolean,
    /** Icon for when the website opens instead of the app: the logo where we have one, else a globe. */
    @DrawableRes val webIcon: Int,
    /** The website search address, which the query is added to the end of. */
    private val searchUrl: String,
) {
    GOOGLE("google", R.string.search_with_google, "com.google.android.googlequicksearchbox", true, R.drawable.ic_super_g_color, "https://www.google.com/search?q=") {
        override fun appIntent(query: String) = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
    },
    PLAY_STORE("play_store", R.string.search_with_play_store, "com.android.vending", true, R.drawable.ic_website, "https://play.google.com/store/search?c=apps&q=") {
        override fun appIntent(query: String) = Intent(Intent.ACTION_VIEW, "market://search?c=apps&q=${Uri.encode(query)}".toUri())
    },
    WIKIPEDIA("wikipedia", R.string.search_with_wikipedia, "org.wikipedia", true, R.drawable.ic_wikipedia, "") {
        // The Wikipedia app has no search intent, but searches text handed to it like "Search Wikipedia" on selected text
        override fun appIntent(query: String) = processText(query)

        // In the phone's language
        override fun webUrl(query: String) =
            "https://${Locale.getDefault().language}.wikipedia.org/w/index.php?search=${Uri.encode(query)}"
    },
    MAPS("maps", R.string.search_with_maps, "com.google.android.apps.maps", true, R.drawable.ic_website, "https://www.google.com/maps/search/?api=1&query=") {
        override fun appIntent(query: String) = Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(query)}".toUri())
    },
    SPOTIFY("spotify", R.string.search_with_spotify, "com.spotify.music", true, R.drawable.ic_website, "https://open.spotify.com/search/") {
        override fun appIntent(query: String) = Intent(Intent.ACTION_VIEW, "spotify:search:${Uri.encode(query)}".toUri())
    },
    YOUTUBE("youtube", R.string.search_with_youtube, "com.google.android.youtube", true, R.drawable.ic_youtube, "https://www.youtube.com/results?search_query=") {
        override fun appIntent(query: String) = Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, query)
    },
    DUCKDUCKGO("duckduckgo", R.string.search_with_duckduckgo, "com.duckduckgo.mobile.android", false, R.drawable.ic_duckduckgo, "https://duckduckgo.com/?q="),
    YOUTUBE_MUSIC("youtube_music", R.string.search_with_youtube_music, "com.google.android.apps.youtube.music", false, R.drawable.ic_website, "https://music.youtube.com/search?q="),
    TRANSLATE("translate", R.string.search_with_translate, "com.google.android.apps.translate", false, R.drawable.ic_translate, "https://translate.google.com/?sl=auto&op=translate&text=") {
        // Same as "Translate" on selected text
        override fun appIntent(query: String) = processText(query)
    },

    // From Lawnchair's search bar list
    GOOGLE_GO("google_go", R.string.search_provider_google_go, "com.google.android.apps.searchlite", false, R.drawable.ic_super_g_color, "https://www.google.com/search?q=") {
        override fun appIntent(query: String) = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
    },
    GITHUB("github", R.string.search_provider_github, null, false, R.drawable.ic_github, "https://github.com/search?q="),
    ECOSIA("ecosia", R.string.search_provider_ecosia, "com.ecosia.android", false, R.drawable.ic_ecosia, "https://www.ecosia.org/search?q="),
    PRESEARCH("presearch", R.string.search_provider_presearch, "com.presearch", false, R.drawable.ic_presearch, "https://presearch.com/search?q="),
    BING("bing", R.string.search_provider_bing, "com.microsoft.bing", false, R.drawable.ic_bing, "https://www.bing.com/search?q="),
    BRAVE("brave", R.string.search_provider_brave, "com.brave.browser", false, R.drawable.ic_brave, "https://search.brave.com/search?q="),
    YANDEX("yandex", R.string.search_provider_yandex, "com.yandex.searchapp", false, R.drawable.ic_yandex, "https://yandex.com/search/?text="),
    STARTPAGE("startpage", R.string.search_provider_startpage, "com.startpage.app", false, R.drawable.ic_startpage, "https://www.startpage.com/do/search?q="),
    STARTPAGE_EU("startpage_eu", R.string.search_provider_startpage_eu, null, false, R.drawable.ic_startpage, "https://eu.startpage.com/do/search?q="),
    KAGI("kagi", R.string.search_provider_kagi, "com.kagi.search", false, R.drawable.ic_kagi, "https://kagi.com/search?q="),
    ;

    /**
     * The intent that opens this provider's app on its search for [query], without the package
     * set. By default the website search, for apps that are browsers or open their own links.
     */
    open fun appIntent(query: String): Intent = Intent(Intent.ACTION_VIEW, webUrl(query).toUri())

    /** The website search for [query], used when the app isn't installed or doesn't take the search. */
    open fun webUrl(query: String): String = searchUrl + Uri.encode(query)

    /** Whether this provider is only a website, with no app. */
    val isWebOnly: Boolean get() = packageName == null

    fun intent(query: String, mode: Mode): Intent = when (mode) {
        Mode.APP -> appIntent(query).setPackage(packageName)
        Mode.WEB -> Intent(Intent.ACTION_VIEW, webUrl(query).toUri())
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Whether this provider searches in its app, when the app is installed and takes the search,
     * or on its website. Asks the package manager, so not on the main thread.
     */
    fun resolveMode(context: Context): Mode {
        val packageName = packageName ?: return Mode.WEB
        val app = appIntent("").setPackage(packageName).resolveActivity(context.packageManager)
        return if (app != null) Mode.APP else Mode.WEB
    }

    enum class Mode { APP, WEB }

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id }

        /**
         * The enabled providers in the user's order, each with how it searches on this phone, as
         * [toEntry] strings for a search result's extras. Not on the main thread, see [resolveMode].
         */
        fun resolveEnabled(context: Context, prefValue: String): List<String> =
            SearchWithOrder.parse(prefValue)
                .filter { it.enabled }
                .map { toEntry(it.provider, it.provider.resolveMode(context)) }

        fun toEntry(provider: SearchWithProvider, mode: Mode) = "${provider.id}:${mode.name}"

        fun fromEntry(entry: String): Pair<SearchWithProvider, Mode>? {
            val provider = fromId(entry.substringBefore(':')) ?: return null
            val mode = Mode.entries.firstOrNull { it.name == entry.substringAfter(':') } ?: return null
            return provider to mode
        }

        private fun processText(query: String) = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, query)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }
}

/** NDShades: One provider in the user's "Search with" list, checked or not. */
data class SearchWithEntry(val provider: SearchWithProvider, val enabled: Boolean)

/**
 * NDShades: The saved "Search with" list, like the home screen pop-up menu's: providers in order,
 * each `+id` when checked or `-id` when not, joined with `|`.
 */
object SearchWithOrder {
    val DEFAULT: List<SearchWithEntry> = SearchWithProvider.entries.map { SearchWithEntry(it, it.defaultEnabled) }

    /** Unknown ids are dropped, and providers added since the list was saved go at the end with their default. */
    fun parse(value: String): List<SearchWithEntry> {
        val saved = value.split("|").mapNotNull { item ->
            SearchWithProvider.fromId(item.removePrefix("+").removePrefix("-"))
                ?.let { SearchWithEntry(it, item.startsWith("+")) }
        }.distinctBy { it.provider }
        val missing = DEFAULT.filter { default -> saved.none { it.provider == default.provider } }
        return saved + missing
    }

    fun List<SearchWithEntry>.toPrefString(): String =
        joinToString("|") { (if (it.enabled) "+" else "-") + it.provider.id }
}
