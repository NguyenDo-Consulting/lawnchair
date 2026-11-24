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

package app.lawnchair.qsb.providers

import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.View
import androidx.core.view.descendants
import app.lawnchair.BlankActivity
import app.lawnchair.HeadlessWidgetsManager
import app.lawnchair.qsb.GoogleQsbContainerView
import app.lawnchair.qsb.ThemingMethod
import app.lawnchair.util.pendingIntent
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.qsb.QsbContainerView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

data object Google : QsbSearchProvider(
    id = "google",
    name = R.string.search_provider_google,
    icon = R.drawable.ic_super_g_color,
    themingMethod = ThemingMethod.THEME_BY_LAYER_ID,
    packageName = "com.google.android.googlequicksearchbox",
    action = "android.search.action.GLOBAL_SEARCH",
    supportVoiceIntent = true,
    website = "https://www.google.com/",
) {
    override suspend fun launch(launcher: Launcher, forceWebsite: Boolean) {
        if (!forceWebsite) {
            val subscription = getSearchIntent(launcher)
            val pendingIntent = subscription.firstOrNull()
            val options = ActivityOptions.makeBasic()
            if (Utilities.ATLEAST_U) {
                options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
            }
            if (pendingIntent != null) {
                launcher.startIntentSender(
                    pendingIntent.intentSender,
                    null,
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    0,
                    options.toBundle(),
                )
                return
            }
        }
        super.launch(launcher, forceWebsite)
    }

    // NDShades: voice web search. The default VOICE_COMMAND now opens the Google app's hands-free
    // (driving) screen, which closes again at once, so the mic seemed to do nothing.
    override fun handleCreateVoiceIntent(): Intent = Intent(RecognizerIntent.ACTION_WEB_SEARCH)
        .addFlags(INTENT_FLAGS)
        .setPackage(packageName)

    fun getSearchIntent(context: Context): Flow<PendingIntent?> = searchWidgetUpdates(context).map(::findSearchIntent)

    // NDShades: the regular search widget by name. The default lookup gave the "Premium" one,
    // which only draws for Pixel Launcher, so no link was ever found in it.
    private fun searchWidgetUpdates(context: Context): Flow<AppWidgetHostView> {
        val info = searchWidgetInfo(context) ?: return emptyFlow()
        val headlessWidgetsManager = HeadlessWidgetsManager.INSTANCE.get(context)
        return headlessWidgetsManager.subscribeUpdates(info, WIDGET_PREF_KEY)
    }

    private fun searchWidgetInfo(context: Context) = GoogleQsbContainerView.findGoogleSearchWidget(context)
        ?: QsbContainerView.getSearchWidgetProviderInfo(context, Google.packageName)

    /** NDShades: The Google search bar widget's buttons that ND Shades' search bars borrow. */
    enum class WidgetButton(vararg val labels: String) {
        // Matched in English: "Voice search", "Camera search" (Lens) and "AI Mode". In other
        // languages the mic and Lens fall back to their intents, and AI Mode doesn't show.
        VOICE_SEARCH("voice"),
        LENS("camera", "lens"),
        AI_MODE("AI Mode"),
    }

    /** NDShades: Which of the widget's Lens and AI Mode buttons a search bar shows. */
    data class WidgetButtons(val lens: Boolean, val aiMode: Boolean) {
        companion object {
            val NONE = WidgetButtons(lens = false, aiMode = false)
        }
    }

    private val widgetPermissionChangesFlow = MutableStateFlow(0)

    /** NDShades: Goes up each time the widget permission is asked for, so the search bars look again. */
    val widgetPermissionChanges: StateFlow<Int> = widgetPermissionChangesFlow.asStateFlow()

    /**
     * NDShades: Whether ND Shades still needs Android's "Allow widgets" permission to use the
     * Google search bar widget. False without the Google app.
     */
    fun needsWidgetPermission(context: Context): Boolean {
        val info = searchWidgetInfo(context) ?: return false
        return !HeadlessWidgetsManager.INSTANCE.get(context).getWidget(info, WIDGET_PREF_KEY).isBound
    }

    /**
     * NDShades: The Lens and AI Mode buttons to show. Until the widget permission is granted, the
     * widget can't be read, so both show and a tap asks for the permission.
     */
    suspend fun widgetButtons(context: Context): WidgetButtons {
        if (searchWidgetInfo(context) == null) return WidgetButtons.NONE
        if (needsWidgetPermission(context)) return WidgetButtons(lens = true, aiMode = true)
        return WidgetButtons(
            lens = hasWidgetButton(context, WidgetButton.LENS),
            aiMode = hasWidgetButton(context, WidgetButton.AI_MODE),
        )
    }

    /**
     * NDShades: Asks for Android's "Allow widgets" permission when the Google search bar widget
     * isn't attached yet. True when the widget is attached.
     */
    suspend fun requestWidgetPermission(launcher: Launcher): Boolean {
        val info = searchWidgetInfo(launcher) ?: return false
        val widget = HeadlessWidgetsManager.INSTANCE.get(launcher).getWidget(info, WIDGET_PREF_KEY)
        if (widget.isBound) return true
        // Attaches it silently if the permission was granted meanwhile, else asks
        widget.bind()
        if (!widget.isBound) {
            BlankActivity.startBlankActivityForResult(launcher, widget.getBindIntent())
        }
        widgetPermissionChangesFlow.update { it + 1 }
        return widget.isBound
    }

    /** NDShades: Whether the Google search bar widget has [button]. */
    private suspend fun hasWidgetButton(context: Context, button: WidgetButton): Boolean =
        widgetButtonIntent(context, button) != null

    /**
     * NDShades: Opens [button]'s link from the Google search bar widget, the way the widget does.
     * The mic's link opens Google's own voice search; the public voice search intents open the
     * assistant overlay instead. Asks for the widget permission first if needed. False when the
     * permission is declined or the button can't be found.
     */
    suspend fun launchWidgetButton(launcher: Launcher, button: WidgetButton): Boolean {
        if (!requestWidgetPermission(launcher)) return false
        val pendingIntent = widgetButtonIntent(launcher, button) ?: return false
        val options = ActivityOptions.makeBasic()
        if (Utilities.ATLEAST_U) {
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
        }
        return runCatching {
            launcher.startIntentSender(pendingIntent.intentSender, null, 0, 0, 0, options.toBundle())
        }.isSuccess
    }

    // A widget that was just attached starts out empty, so this waits for content with the button
    private suspend fun widgetButtonIntent(context: Context, button: WidgetButton): PendingIntent? =
        withTimeoutOrNull(WIDGET_LOOKUP_TIMEOUT_MS) {
            searchWidgetUpdates(context)
                .mapNotNull { view -> button.labels.firstNotNullOfOrNull { findButtonIntent(view, it) } }
                .firstOrNull()
        }

    /** NDShades: The link of the widget button whose label contains [label], from the button or the frame around it. */
    private fun findButtonIntent(widget: AppWidgetHostView, label: String): PendingIntent? {
        val button = widget.descendants.firstOrNull {
            it.contentDescription?.contains(label, ignoreCase = true) == true
        } ?: return null
        var view: View? = button
        while (view != null && view !== widget) {
            view.pendingIntent?.let { return it }
            view = view.parent as? View
        }
        return null
    }

    private const val WIDGET_PREF_KEY = "hotseatWidgetId"
    private const val WIDGET_LOOKUP_TIMEOUT_MS = 3000L

    private fun findSearchIntent(view: AppWidgetHostView): PendingIntent? {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        return view.descendants
            .filter { it.pendingIntent != null }
            .sortedByDescending { it.measuredWidth * it.measuredHeight }
            .firstOrNull()
            ?.pendingIntent
    }
}
