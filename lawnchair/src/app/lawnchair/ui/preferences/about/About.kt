/*
 * Copyright 2022, Lawnchair
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
 *
 * Modifications copyright 2026, Nguyen-Do Consulting
 */

package app.lawnchair.ui.preferences.about

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.NavigationActionPreference
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceDivider
import app.lawnchair.ui.preferences.components.layout.PreferenceGroupHeading
import app.lawnchair.ui.preferences.components.layout.PreferenceGroupItem
import app.lawnchair.ui.preferences.components.layout.PreferenceLayoutLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.navigation.AboutLicenses
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun About(
    modifier: Modifier = Modifier,
    viewModel: AboutViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val sheetState = rememberModalBottomSheetState(true)
    var openBottomSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val prefs: PreferenceManager = PreferenceManager.getInstance(context)

    if (openBottomSheet) {
        val updateState = uiState.updateState
        if (updateState is UpdateState.Available) {
            ChangesDialog(
                changelogState = updateState.changelogState,
                onDismiss = {
                    scope.launch {
                        sheetState.hide()
                    }.invokeOnCompletion {
                        openBottomSheet = false
                    }
                },
                onDownload = {
                    viewModel.downloadUpdate()
                },
                sheetState = sheetState,
            )
        }
    }

    PreferenceLayoutLazyColumn(
        label = stringResource(id = R.string.about_label),
        modifier = modifier,
        backArrowVisible = !LocalIsExpandedScreen.current,
    ) {
        item {
            Spacer(Modifier.padding(top = 8.dp))
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_home_comp),
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape),
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
        item {
            Text(
                text = stringResource(id = R.string.derived_app_name),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                // Lawnchair: no long press to open the commit, ND Shades' repository is private
                Text(
                    text = if (prefs.hideVersionInfo.get()) {
                        prefs.pseudonymVersion.get() + " (pseudonym)"
                    } else {
                        BuildConfig.VERSION_DISPLAY_NAME
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
        item {
            UpdateSection(
                updateState = uiState.updateState,
                onInstall = {
                    viewModel.installUpdate(it)
                },
                onForceInstall = {
                    viewModel.installUpdate(it, forceInstall = true)
                },
                onViewChanges = {
                    openBottomSheet = true
                    scope.launch {
                        sheetState.show()
                    }
                },
                onDismissMajorUpdate = {
                    viewModel.resetToDownloaded(it)
                },
            )
        }
        item {
            Spacer(modifier = Modifier.requiredHeight(16.dp))
        }
        // Lawnchair: ND Shades' developer in place of Lawnchair's links, team and community.
        // Lawnchair is credited under Legal.
        item {
            PreferenceGroupHeading(
                stringResource(R.string.about_developer),
            )
        }
        item {
            PreferenceGroupItem(
                cutTop = false,
                cutBottom = false,
            ) {
                PreferenceTemplate(
                    title = { Text(text = stringResource(R.string.about_developer_name)) },
                )
            }
        }
        item {
            PreferenceGroupHeading(
                stringResource(R.string.legal),
            )
        }
        item {
            PreferenceGroupItem(
                cutTop = false,
                cutBottom = true,
            ) {
                NavigationActionPreference(
                    label = stringResource(id = R.string.acknowledgements),
                    destination = AboutLicenses,
                )
            }
        }
        item {
            Spacer(Modifier.height(3.dp))
        }
        item {
            PreferenceGroupItem(
                cutTop = true,
                cutBottom = false,
            ) {
                // Lawnchair: credit to the Lawnchair project, in place of its privacy policy
                ClickablePreference(
                    label = stringResource(id = R.string.about_based_on_lawnchair),
                    subtitle = stringResource(id = R.string.about_based_on_lawnchair_description),
                    onClick = {
                        val webpage = LAWNCHAIR_PROJECT.toUri()
                        val intent = Intent(Intent.ACTION_VIEW, webpage)
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        }
                    },
                )
            }
        }
    }
}

private const val LAWNCHAIR_PROJECT = "https://github.com/LawnchairLauncher/lawnchair"
