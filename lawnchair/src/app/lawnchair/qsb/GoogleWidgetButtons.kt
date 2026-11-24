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

package app.lawnchair.qsb

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import app.lawnchair.launcher
import app.lawnchair.qsb.providers.AppSearch
import app.lawnchair.qsb.providers.Google
import app.lawnchair.qsb.providers.QsbSearchProvider
import kotlinx.coroutines.launch

/**
 * NDShades: The Lens and AI Mode buttons that ND Shades' search bars borrow from the Google search
 * bar widget, for [searchProvider]. Looks again after the widget permission is asked for.
 */
@Composable
fun rememberGoogleWidgetButtons(searchProvider: QsbSearchProvider): Google.WidgetButtons {
    val context = LocalContext.current
    val permissionChanges by Google.widgetPermissionChanges.collectAsState()
    val buttons by produceState(Google.WidgetButtons.NONE, searchProvider, permissionChanges) {
        value = if (searchProvider == AppSearch) Google.widgetButtons(context) else Google.WidgetButtons.NONE
    }
    return buttons
}

/**
 * NDShades: Opens [button] the way the Google search bar widget does, asking for the widget
 * permission first if needed. Falls back to [fallback] when that's declined or the button isn't found.
 */
fun launchGoogleWidgetButton(
    context: Context,
    searchProvider: QsbSearchProvider,
    button: Google.WidgetButton,
    fallback: Intent?,
) {
    val launcher = context.launcher
    launcher.lifecycleScope.launch {
        val launched = searchProvider == AppSearch && Google.launchWidgetButton(launcher, button)
        if (!launched) fallback?.let { runCatching { context.startActivity(it) } }
    }
}
