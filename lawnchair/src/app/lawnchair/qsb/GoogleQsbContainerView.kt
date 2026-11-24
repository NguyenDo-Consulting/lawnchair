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

package app.lawnchair.qsb

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Process
import android.util.AttributeSet
import com.android.launcher3.qsb.QsbContainerView

class GoogleQsbContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : QsbContainerView(context, attrs, defStyleAttr) {

    class QsbFragment : QsbContainerView.QsbFragment() {
        override fun isQsbEnabled(): Boolean = true

        /**
         * NDShades: The Google app's regular search bar widget, by name. The default lookup skips
         * widgets that have a setup screen, which left the "Premium" one; that one only draws for
         * Pixel Launcher, so the dock showed an empty bar.
         */
        override fun getSearchWidgetProvider(): AppWidgetProviderInfo? =
            findGoogleSearchWidget(context) ?: super.getSearchWidgetProvider()
    }

    companion object {
        private const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        private val SEARCH_WIDGET_CLASSES = listOf(
            "com.google.android.apps.gsa.staticplugins.searchwidget.GoogleSearchWidgetProvider",
            "com.google.android.googlequicksearchbox.SearchWidgetProvider",
        )

        /**
         * NDShades: The Google app's regular search bar widget, or null without the Google app.
         * Also used by [app.lawnchair.qsb.providers.Google] to borrow the widget's links.
         */
        fun findGoogleSearchWidget(context: Context): AppWidgetProviderInfo? {
            val providers = AppWidgetManager.getInstance(context)
                .getInstalledProvidersForPackage(GOOGLE_PACKAGE, Process.myUserHandle())
            return SEARCH_WIDGET_CLASSES.firstNotNullOfOrNull { className ->
                providers.firstOrNull { it.provider.className == className }
            }
        }
    }
}
