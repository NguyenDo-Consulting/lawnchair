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

package app.lawnchair.folder

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How often each app and pinned shortcut was opened recently, from the system usage stats. Backs
 * the "Most used" folder sort.
 *
 * Querying usage events is too slow for the main thread, so counts are cached and refreshed in
 * the background (when a folder first asks for them, then whenever the launcher resumes). Listeners
 * are told on the main thread when a refresh changed the counts, so folders can re-sort.
 */
object FolderUsageStats {

    private const val TAG = "FolderUsageStats"
    private val WINDOW_MS = TimeUnit.DAYS.toMillis(14)
    private val MIN_REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1)

    /**
     * @property apps times each app came to the foreground, from anywhere, keyed by package
     * @property shortcuts times each pinned shortcut was opened, keyed by [shortcutKey]
     */
    data class Counts(
        val apps: Map<String, Int>,
        val shortcuts: Map<String, Int>,
    ) {
        companion object {
            val EMPTY = Counts(emptyMap(), emptyMap())
        }
    }

    fun shortcutKey(packageName: String, shortcutId: String) = "$packageName/$shortcutId"

    @Volatile
    private var counts: Counts? = null

    @Volatile
    private var lastRefresh = 0L

    /** Set once something asks for counts, so resuming the launcher doesn't query for nothing. */
    @Volatile
    private var wanted = false

    private val loading = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<Runnable>()

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Recent open counts, or null while the first load is still running. Without usage access,
     * apps all count 0 (so they sort alphabetically) and only shortcut taps are counted.
     */
    fun getCounts(context: Context): Counts? {
        wanted = true
        counts?.let { return it }
        refresh(context)
        return null
    }

    /** Called when the launcher resumes: counts change as apps get opened. */
    fun refreshIfWanted(context: Context) {
        if (wanted) refresh(context)
    }

    /**
     * Called when Lawnchair opens a pinned shortcut. Saved off the main thread, on the same thread
     * as refreshes, so the refresh when the launcher resumes already includes this tap.
     */
    fun onShortcutLaunched(context: Context, packageName: String, shortcutId: String) {
        val appContext = context.applicationContext
        UI_HELPER_EXECUTOR.execute {
            ShortcutLaunchCounter.recordLaunch(appContext, shortcutKey(packageName, shortcutId))
            // Skip the refresh throttle so the folder reflects the tap right away
            lastRefresh = 0L
        }
    }

    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    private fun refresh(context: Context) {
        val now = System.currentTimeMillis()
        if (counts != null && now - lastRefresh < MIN_REFRESH_INTERVAL_MS) return
        if (!loading.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        UI_HELPER_EXECUTOR.execute {
            try {
                val newCounts = queryLaunchCounts(appContext)
                val changed = newCounts != counts
                counts = newCounts
                lastRefresh = System.currentTimeMillis()
                if (changed) {
                    MAIN_EXECUTOR.execute { listeners.forEach { it.run() } }
                }
            } finally {
                loading.set(false)
            }
        }
    }

    /**
     * Counts how many times each app came to the foreground (moving between activities of the
     * same app counts once) and how many times each pinned shortcut was opened.
     */
    private fun queryLaunchCounts(context: Context): Counts {
        val apps = HashMap<String, Int>()
        val shortcuts = HashMap<String, Int>()
        if (hasUsageAccess(context)) {
            queryUsageEvents(context, apps, shortcuts)
        }
        // Apps that report shortcut use (like Messenger) can count a tap from Lawnchair too, so
        // take the larger number rather than adding them up
        ShortcutLaunchCounter.getCounts(context).forEach { (key, taps) ->
            shortcuts[key] = maxOf(shortcuts[key] ?: 0, taps)
        }
        Log.d(TAG, "Loaded open counts for ${apps.size} apps and ${shortcuts.size} shortcuts")
        return Counts(apps, shortcuts)
    }

    private fun queryUsageEvents(
        context: Context,
        apps: MutableMap<String, Int>,
        shortcuts: MutableMap<String, Int>,
    ) {
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        try {
            val events = usageStatsManager.queryEvents(now - WINDOW_MS, now) ?: return
            val event = UsageEvents.Event()
            var lastPackage: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val packageName = event.packageName ?: continue
                when (event.eventType) {
                    // A compile-time constant, so it also works before Q: it has the same value as
                    // the MOVE_TO_FOREGROUND event that older versions report.
                    UsageEvents.Event.ACTIVITY_RESUMED -> if (packageName != lastPackage) {
                        apps[packageName] = (apps[packageName] ?: 0) + 1
                        lastPackage = packageName
                    }

                    // Only logged when the app itself reports that a shortcut was used. Many apps
                    // (like Google Contacts) never do, which is why Lawnchair counts taps too.
                    UsageEvents.Event.SHORTCUT_INVOCATION -> {
                        val shortcutId = event.shortcutId ?: continue
                        val key = shortcutKey(packageName, shortcutId)
                        shortcuts[key] = (shortcuts[key] ?: 0) + 1
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query usage events", e)
        }
    }
}
