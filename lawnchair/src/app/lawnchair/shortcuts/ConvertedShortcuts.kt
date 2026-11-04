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

package app.lawnchair.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Process
import android.util.Log
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.shortcuts.ShortcutKey
import com.android.launcher3.shortcuts.ShortcutRequest
import java.net.URISyntaxException

/**
 * Turns legacy shortcuts, which are only a name, an icon and an intent, into pinned shortcuts that
 * Lawnchair publishes itself. Everything newer in the launcher, like icon swipe gestures and the
 * long-press menu, is built for pinned shortcuts, so legacy ones are better off converted.
 *
 * Android keeps pinned shortcuts outside the launcher's own files, so they're not in a Lawnchair
 * backup. The shortcut id holds the intent, which lets [republish] publish them again from a
 * backup's launcher database, where the name and icon are kept.
 */
object ConvertedShortcuts {
    private const val TAG = "ConvertedShortcuts"
    private const val ID_PREFIX = "legacy:"

    /** A shortcut to publish. Its id comes from [intent], so the same shortcut keeps its id. */
    data class Spec(val title: String, val intent: Intent, val icon: Bitmap?) {
        val id: String = idFor(intent)
    }

    /** Launch flags are left out of the id, since the launcher can change them. */
    fun idFor(intent: Intent): String = ID_PREFIX + Intent(intent).setFlags(0).toUri(0)

    fun isConverted(context: Context, shortcut: ShortcutInfo): Boolean = shortcut.`package` == context.packageName && shortcut.id.startsWith(ID_PREFIX)

    /** Returns whether [info] is the home screen item of a converted shortcut. */
    fun isConverted(context: Context, info: ItemInfo): Boolean {
        if (info.itemType != Favorites.ITEM_TYPE_DEEP_SHORTCUT) return false
        val intent = info.intent ?: return false
        return intent.`package` == context.packageName &&
            intent.getStringExtra(ShortcutKey.EXTRA_SHORTCUT_ID)?.startsWith(ID_PREFIX) == true
    }

    /** Returns the intent a converted shortcut's id holds, or null for any other id. */
    private fun intentFromId(id: String): Intent? {
        if (!id.startsWith(ID_PREFIX)) return null
        return try {
            Intent.parseUri(id.removePrefix(ID_PREFIX), 0)
        } catch (_: URISyntaxException) {
            null
        }
    }

    /** The launcher database intent of the pinned shortcut for [spec]. */
    fun makeLauncherIntent(context: Context, spec: Spec): String = ShortcutKey.makeIntent(spec.id, context.packageName).toUri(0)

    /**
     * Publishes [specs] as Lawnchair's shortcuts and pins them, keeping the shortcuts Lawnchair
     * has pinned already. Only the default home app can pin shortcuts. Returns the ids of the
     * shortcuts that ended up pinned.
     */
    fun publishAndPin(context: Context, specs: List<Spec>): Set<String> {
        val unique = specs.distinctBy { it.id }
        if (unique.isEmpty()) return emptySet()
        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return emptySet()
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptySet()
        val user = Process.myUserHandle()
        val pinnedIds = queryPinnedIds(context).toMutableSet()

        // Shortcuts are published as dynamic ones, which have a per-app limit, then pinned, then
        // removed from the dynamic list. Pinned shortcuts stay.
        val batchSize = shortcutManager.maxShortcutCountPerActivity.coerceAtLeast(1)
        unique.chunked(batchSize).forEach { batch ->
            val ids = batch.map { it.id }
            try {
                if (shortcutManager.addDynamicShortcuts(batch.map { toShortcutInfo(context, it) })) {
                    pinnedIds.addAll(ids)
                    launcherApps.pinShortcuts(context.packageName, pinnedIds.toList(), user)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Can't pin shortcuts, Lawnchair isn't the default home app", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Can't publish shortcuts", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Can't publish shortcuts", e)
            } finally {
                runCatching { shortcutManager.removeDynamicShortcuts(ids) }
            }
        }
        return queryPinnedIds(context).intersect(unique.map { it.id }.toSet())
    }

    /**
     * Publishes and pins the converted shortcuts in a launcher database again, e.g. one restored
     * from a backup on another device. Returns how many couldn't be pinned.
     */
    fun republish(context: Context, db: SQLiteDatabase): Int {
        val specs = db.rawQuery(
            "SELECT title, intent, icon FROM favorites WHERE itemType = ?",
            arrayOf(Favorites.ITEM_TYPE_DEEP_SHORTCUT.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val launcherIntent = cursor.getString(1)
                        ?.let { runCatching { Intent.parseUri(it, 0) }.getOrNull() }
                        ?: continue
                    if (launcherIntent.`package` != context.packageName) continue
                    val id = launcherIntent.getStringExtra(ShortcutKey.EXTRA_SHORTCUT_ID) ?: continue
                    val intent = intentFromId(id) ?: continue
                    val icon = if (cursor.isNull(2)) {
                        null
                    } else {
                        cursor.getBlob(2).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                    add(Spec(cursor.getString(0).orEmpty(), intent, icon))
                }
            }
        }
        val pinned = publishAndPin(context, specs)
        return specs.distinctBy { it.id }.count { it.id !in pinned }
    }

    private fun queryPinnedIds(context: Context): Set<String> {
        val result = ShortcutRequest(context, Process.myUserHandle())
            .forPackage(context.packageName)
            .query(ShortcutRequest.PINNED or LauncherApps.ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY)
        return if (result.wasSuccess()) result.mapTo(mutableSetOf()) { it.id } else emptySet()
    }

    private fun toShortcutInfo(context: Context, spec: Spec): ShortcutInfo {
        // A shortcut's intent needs an action.
        val intent = Intent(spec.intent).apply { if (action == null) action = Intent.ACTION_VIEW }
        val icon = spec.icon?.let { Icon.createWithBitmap(it) }
            ?: Icon.createWithResource(context, R.drawable.ic_launcher_home)
        return ShortcutInfo.Builder(context, spec.id)
            .setShortLabel(spec.title.ifBlank { intent.dataString ?: context.packageName })
            .setIntent(intent)
            .setIcon(icon)
            .build()
    }
}
