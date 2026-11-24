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

package app.lawnchair.allapps.views

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import app.lawnchair.launcher
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.searchwith.EXTRA_SEARCH_WITH_PROVIDERS
import app.lawnchair.search.searchwith.EXTRA_SEARCH_WITH_QUERY
import app.lawnchair.search.searchwith.SearchWithProvider
import com.android.launcher3.R
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.LauncherIcons
import kotlin.math.roundToInt

/**
 * NDShades: The drawer search's "Search with" row: one small icon per provider, in the user's
 * order, that opens the provider on what was typed. A long-press shows the provider's name.
 * In this package because [SearchResultView] is sealed.
 */
class SearchResultSearchWith(context: Context, attrs: AttributeSet?) :
    HorizontalScrollView(context, attrs),
    SearchResultView {

    private val launcher = context.launcher
    private lateinit var iconsRow: LinearLayout
    private var flags = 0
    private var firstLabel: CharSequence? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        iconsRow = ViewCompat.requireViewById(this, R.id.search_with_icons)
    }

    // When nothing else matched, Enter on the keyboard searches with the first provider
    override val isQuickLaunch: Boolean
        get() = hasFlag(flags, SearchResultView.FLAG_QUICK_LAUNCH) && iconsRow.childCount > 0
    override val titleText: CharSequence? get() = firstLabel

    override fun launch(): Boolean = iconsRow.getChildAt(0)?.performClick() ?: false

    override fun bind(target: SearchTargetCompat, shortcuts: List<SearchTargetCompat>) {
        flags = getFlags(target.extras)
        val query = target.extras.getString(EXTRA_SEARCH_WITH_QUERY).orEmpty()
        val providers = target.extras.getStringArrayList(EXTRA_SEARCH_WITH_PROVIDERS).orEmpty()
            .mapNotNull(SearchWithProvider::fromEntry)

        iconsRow.removeAllViews()
        scrollTo(0, 0)
        firstLabel = providers.firstOrNull()?.let { (provider, _) -> context.getString(provider.label) }

        val iconSize = (
            launcher.deviceProfile.allAppsProfile.iconSizePx *
                resources.getFraction(R.fraction.search_with_icon_scale, 1, 1)
            ).roundToInt()
        val spacing = resources.getDimensionPixelSize(R.dimen.search_with_icon_spacing)

        providers.forEachIndexed { index, (provider, mode) ->
            val label = context.getString(provider.label)
            iconsRow.addView(
                ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        if (index > 0) marginStart = spacing
                    }
                    setImageDrawable(icon(provider, mode))
                    contentDescription = label
                    tooltipText = label
                    setBackgroundResource(borderlessRipple())
                    setOnClickListener { v ->
                        launcher.startActivitySafely(v, provider.intent(query, mode), null)
                    }
                },
            )
        }
    }

    private fun icon(provider: SearchWithProvider, mode: SearchWithProvider.Mode): Drawable? = when (mode) {
        SearchWithProvider.Mode.APP -> provider.packageName?.let(::appIcon)
        SearchWithProvider.Mode.WEB -> webIcon(provider)
    }

    /** The app's drawer icon, so it follows the icon pack and shape. */
    private fun appIcon(packageName: String): Drawable? {
        val user = Process.myUserHandle()
        return launcher.appsView.appsStore.apps
            .firstOrNull { it.componentName?.packageName == packageName && it.user == user }
            ?.newIcon(context, 0)
            ?: runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    /** The provider's logo in the icon shape, like the app icons next to it. */
    private fun webIcon(provider: SearchWithProvider): Drawable? {
        val bitmap = webIconCache.getOrPut(provider) {
            val logo = ContextCompat.getDrawable(context, provider.webIcon) ?: return null
            LauncherIcons.obtain(context).use { li ->
                li.createBadgedIconBitmap(logo, BaseIconFactory.IconOptions())
            }
        }
        return bitmap.newIcon(context)
    }

    private fun borderlessRipple(): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        return value.resourceId
    }

    companion object {
        // Icon shape changes restart the launcher, so the shaped logos never go stale
        private val webIconCache = mutableMapOf<SearchWithProvider, com.android.launcher3.icons.BitmapInfo>()
    }
}
