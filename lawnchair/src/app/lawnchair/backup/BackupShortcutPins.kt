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

package app.lawnchair.backup

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.os.UserManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.lawnchair.gestures.GestureShortcutPins
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.shortcuts.ShortcutKey
import com.android.launcher3.shortcuts.ShortcutRequest
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * Pins the other apps' shortcuts that a backup uses, e.g. the conversation shortcuts in a dock
 * folder. Android keeps pinned shortcuts per launcher package and outside the launcher's files, so
 * when a backup is restored into a launcher with another package, e.g. a Lawnchair backup into
 * ND Shades, or on another device, none of them are pinned for this package. The launcher model
 * deletes every shortcut that isn't pinned, and with it a folder left with fewer than two items.
 *
 * Only the default home app can pin shortcuts, and only the ones their app still publishes. The
 * ones an app only made through a pin request, e.g. Contacts' direct dial shortcuts, can't be
 * pinned again; [ShortcutRecreator] can recreate some of them.
 * This package's own shortcuts are left to [app.lawnchair.shortcuts.ConvertedShortcuts].
 */
internal class BackupShortcutPins(private val context: Context) {
    private val user = Process.myUserHandle()

    /**
     * Pins the shortcuts in the layout in [dbFile] and the ones that the gestures in [prefs]
     * open. The file may be missing. Returns the shortcuts that couldn't be pinned. Those of apps
     * that aren't installed are left out, since the loader removes their items anyway.
     */
    suspend fun pin(dbFile: File, prefs: DataStore<Preferences>?): Set<ShortcutKey> {
        val shortcuts = (readLayoutShortcuts(dbFile) + readGestureShortcuts(prefs))
            .filter { it.packageName != context.packageName }
            .groupBy({ it.packageName }, { it.id })
            .mapValues { it.value.toSet() }
        if (shortcuts.isEmpty()) return emptySet()

        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val unpinned = mutableSetOf<ShortcutKey>()
        shortcuts.forEach { (packageName, ids) ->
            if (launcherApps != null && !launcherApps.isPackageEnabled(packageName, user)) return@forEach
            try {
                // Pinning replaces the package's pinned shortcuts, so the current ones are kept.
                launcherApps?.pinShortcuts(packageName, (queryPinnedIds(packageName) + ids).toList(), user)
            } catch (e: SecurityException) {
                Log.w(TAG, "Can't pin shortcuts of $packageName, not the default home app", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Can't pin shortcuts of $packageName", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Can't pin shortcuts of $packageName", e)
            }
            (ids - queryPinnedIds(packageName)).mapTo(unpinned) { ShortcutKey(packageName, user, it) }
        }
        return unpinned
    }

    private fun readLayoutShortcuts(dbFile: File): List<ShortcutKey> {
        if (!dbFile.exists()) return emptyList()
        // A backup's main user has the same serial number as this one, other profiles' are
        // remapped later by RestoreDbTask, so only the main user's shortcuts are pinned.
        val serial = context.getSystemService(UserManager::class.java)?.getSerialNumberForUser(user)
            ?: return emptyList()
        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT ${Favorites.INTENT} FROM ${Favorites.TABLE_NAME} " +
                    "WHERE ${Favorites.ITEM_TYPE} = ? AND ${Favorites.PROFILE_ID} = ?",
                arrayOf(Favorites.ITEM_TYPE_DEEP_SHORTCUT.toString(), serial.toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val intent = cursor.getString(0)
                            ?.let { runCatching { Intent.parseUri(it, 0) }.getOrNull() }
                            ?: continue
                        if (intent.`package` == null || intent.getStringExtra(ShortcutKey.EXTRA_SHORTCUT_ID) == null) continue
                        add(ShortcutKey.fromIntent(intent, user))
                    }
                }
            }
        }
    }

    private suspend fun readGestureShortcuts(prefs: DataStore<Preferences>?): List<ShortcutKey> {
        if (prefs == null) return emptyList()
        return GestureShortcutPins.getSavedShortcutKeys(prefs.data.first(), user)
    }

    private fun queryPinnedIds(packageName: String): Set<String> {
        val result = ShortcutRequest(context, user)
            .forPackage(packageName)
            .query(ShortcutRequest.PINNED or LauncherApps.ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY)
        return if (result.wasSuccess()) result.mapTo(mutableSetOf()) { it.id } else emptySet()
    }

    companion object {
        private const val TAG = "BackupShortcutPins"
    }
}
