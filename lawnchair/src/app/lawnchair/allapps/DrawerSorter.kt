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

package app.lawnchair.allapps

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Color
import android.util.Log
import app.lawnchair.folder.FolderUsageStats
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.ComponentKey

/**
 * Orders the app drawer by a [DrawerSortMode]. The apps come in alphabetical order and every sort
 * here is stable, so apps that tie (same open count, same color) stay alphabetical.
 *
 * Only called on the main thread.
 */
object DrawerSorter {

    private const val TAG = "DrawerSorter"

    /** Below this saturation or brightness an icon counts as white, gray or black. */
    private const val MIN_COLORFULNESS = 0.2f

    /** First install time of each app, so the system is only asked when an app is new. */
    private val installTimes = HashMap<ComponentKey, Long>()

    /**
     * Returns [apps] ordered by [mode]. [allApps] is every app in the drawer, before any filter,
     * and is used to forget the install times of apps that were uninstalled.
     */
    fun sort(context: Context, apps: List<AppInfo>, mode: DrawerSortMode, allApps: Array<AppInfo>): List<AppInfo> =
        when (mode) {
            DrawerSortMode.ALPHABETICAL -> apps

            DrawerSortMode.INSTALL_DATE -> {
                loadInstallTimes(context, apps, allApps)
                apps.sortedByDescending { installTimes[it.toComponentKey()] ?: 0L }
            }

            DrawerSortMode.MOST_USED -> {
                // Null while the first load runs. The list is rebuilt when the counts arrive.
                val counts = FolderUsageStats.getCounts(context) ?: return apps
                apps.sortedByDescending { counts.apps[it.targetPackage] ?: 0 }
            }

            DrawerSortMode.COLOR -> apps.sortedBy { colorOrder(it) }
        }

    /**
     * Where an icon goes in the color order: its hue (0 to 360, red through purple to pink), or
     * after every colorful icon when it's white, gray or black, lightest first.
     */
    private fun colorOrder(info: AppInfo): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(info.bitmap.color, hsv)
        val (hue, saturation, brightness) = hsv
        return if (saturation < MIN_COLORFULNESS || brightness < MIN_COLORFULNESS) {
            360f + (1f - brightness)
        } else {
            hue
        }
    }

    /**
     * Makes sure [installTimes] has every app in [apps]. When one is missing, every app of its
     * profile is read in one call to the system.
     */
    private fun loadInstallTimes(context: Context, apps: List<AppInfo>, allApps: Array<AppInfo>) {
        // A reinstalled app gets a new install time, so forget apps that are gone
        val installed = allApps.mapTo(HashSet()) { it.toComponentKey() }
        installTimes.keys.retainAll(installed)

        val missingUsers = apps.filter { it.toComponentKey() !in installTimes }.mapTo(HashSet()) { it.user }
        if (missingUsers.isEmpty()) return
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        missingUsers.forEach { user ->
            try {
                launcherApps.getActivityList(null, user).forEach {
                    installTimes[ComponentKey(it.componentName, user)] = it.firstInstallTime
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read install times", e)
            }
        }
        // Apps the system didn't list sort as oldest, without asking again on every rebuild
        apps.forEach { installTimes.putIfAbsent(it.toComponentKey(), 0L) }
    }
}
