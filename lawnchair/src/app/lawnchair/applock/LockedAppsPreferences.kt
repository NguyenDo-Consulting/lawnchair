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

package app.lawnchair.applock

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.AppItem
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import app.lawnchair.ui.preferences.destinations.ListSortingOptions
import app.lawnchair.util.appComparator
import app.lawnchair.util.appsState
import com.android.launcher3.R
import java.util.Comparator.comparing

/**
 * Settings → App drawer → Locked apps: how the locked apps unlock, when they lock again, and
 * which apps are locked. Entering asks for the unlock, and so does coming back to it after
 * leaving. The first visit, with nothing set up, starts the setup instead.
 */
@Composable
fun LockedAppsPreferences(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { AppLockManager.getInstance(context) }
    val navController = LocalNavController.current
    val prefs2 = preferenceManager2()

    var verified by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // Bumped after a setup, so the method shown is read again.
    var setupVersion by remember { mutableIntStateOf(0) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val askForNotifications = {
        // The reset notification is how the owner notices a reset they didn't request.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val verify = {
        busy = true
        val onCancel = {
            busy = false
            navController.popBackStack()
            Unit
        }
        if (!manager.isSetUp && !manager.hasLockedApps) {
            manager.requestSetup(context, onSuccess = {
                busy = false
                verified = true
                manager.beginVisit()
                setupVersion++
                askForNotifications()
            }, onCancel = onCancel)
        } else {
            manager.requestVerify(context, onSuccess = {
                busy = false
                verified = true
                manager.beginVisit()
            }, onCancel = onCancel)
        }
    }

    // Asks on entering, and again when coming back after leaving. Not while the unlock itself is
    // showing, since the phone's PIN screen also stops this one.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!verified && !busy) verify()
                // Leaving, or the app or screen going off, ends the visit: the next removal asks
                // for the code again.
                Lifecycle.Event.ON_STOP -> if (!busy) {
                    verified = false
                    manager.endVisit()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            manager.endVisit()
        }
    }

    val lockedAdapter = prefs2.lockedApps.getAdapter()
    val lockedApps by lockedAdapter.state
    val apps by appsState(comparator = remember(verified) { lockedAppsComparator(lockedApps) })

    // Removing a lock needs the code (separate PIN, or the phone's pattern/PIN), even in here.
    // Adding locks doesn't.
    val setLockedApps = { newSet: Set<String> ->
        val apply = {
            lockedAdapter.onChange(newSet)
            manager.onSettingsChanged()
        }
        if ((lockedAdapter.state.value - newSet).isEmpty()) {
            apply()
        } else {
            busy = true
            manager.requestWeaken(context, onSuccess = {
                busy = false
                apply()
            }, onCancel = { busy = false })
        }
    }

    PreferenceScaffold(
        label = stringResource(R.string.locked_apps_label),
        modifier = modifier,
        actions = {
            // Select all, deselect all, inverse selection, reset: the Hidden apps menu
            if (verified && apps.isNotEmpty()) {
                ListSortingOptions(
                    originalList = apps,
                    filteredList = lockedApps,
                    onUpdateList = setLockedApps,
                )
            }
        },
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) { padding ->
        if (!verified) {
            Box(modifier = Modifier.fillMaxSize())
            return@PreferenceScaffold
        }
        PreferenceLazyColumn(padding) {
            item {
                // Read again after a setup.
                val method = remember(setupVersion) { manager.method }
                PreferenceGroup(heading = stringResource(R.string.app_lock_group_lock)) {
                    ClickablePreference(
                        label = stringResource(R.string.app_lock_method_label),
                        subtitle = method?.let { stringResource(it.labelRes) },
                        onClick = {
                            busy = true
                            manager.requestSetup(context, onSuccess = {
                                busy = false
                                setupVersion++
                                askForNotifications()
                            }, onCancel = { busy = false })
                        },
                    )
                    if (method == LockMethod.PIN) {
                        ClickablePreference(
                            label = stringResource(R.string.app_lock_change_pin),
                            onClick = {
                                busy = true
                                manager.requestSetup(context, LockMethod.PIN, onSuccess = {
                                    busy = false
                                    setupVersion++
                                }, onCancel = { busy = false })
                            },
                        )
                    }
                    val relockAdapter = prefs2.appLockRelock.getAdapter()
                    ListPreference(
                        entries = RelockMode.entries.map { mode ->
                            ListPreferenceEntry(mode.id) { stringResource(mode.labelRes) }
                        },
                        value = relockAdapter.state.value,
                        onValueChange = { newId ->
                            val apply = {
                                relockAdapter.onChange(newId)
                                manager.onSettingsChanged()
                            }
                            // Relocking less often weakens the lock, so it needs the code. The
                            // modes go from most to least often.
                            val weaker = RelockMode.fromId(newId).ordinal > RelockMode.fromId(relockAdapter.state.value).ordinal
                            if (weaker) {
                                busy = true
                                manager.requestWeaken(context, onSuccess = {
                                    busy = false
                                    apply()
                                }, onCancel = { busy = false })
                            } else {
                                apply()
                            }
                        },
                        label = stringResource(R.string.app_lock_relock_label),
                    )
                }
            }
            preferenceGroupItems(
                items = apps,
                isFirstChild = false,
                heading = { context.getString(R.string.locked_apps_label) },
            ) { _, app ->
                val key = app.key.toString()
                AppItem(
                    app = app,
                    onClick = {
                        val newSet = lockedApps.toMutableSet()
                        if (key in lockedApps) newSet.remove(key) else newSet.add(key)
                        setLockedApps(newSet)
                    },
                ) {
                    Checkbox(checked = key in lockedApps, onCheckedChange = null)
                }
            }
            item {
                Text(
                    text = stringResource(R.string.app_lock_limits_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/** Locked apps first, then the rest by name. */
private fun lockedAppsComparator(lockedApps: Set<String>): Comparator<app.lawnchair.util.App> =
    comparing<app.lawnchair.util.App, Int> { if (it.key.toString() in lockedApps) 0 else 1 }.then(appComparator)
