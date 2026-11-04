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

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Process
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.lawnchair.gestures.IconGestureKeys
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.shortcuts.ConvertedShortcuts
import app.lawnchair.util.kotlinxJson
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.shortcuts.ShortcutKey
import java.io.File

/**
 * Recreates the shortcuts of a backup that [BackupShortcutPins] couldn't pin as this launcher's
 * own shortcuts, when what they open can be found. A shortcut that an app only made through a pin
 * request can only be pinned by the launcher that accepted it, so a backup restored into another
 * launcher package loses it.
 *
 * Only the call and message shortcuts of a contacts app can be found: their id is the action, the
 * contact's lookup key and the hash of the number's URI. A call shortcut opens the dialer with
 * the number, since placing the call needs the CALL_PHONE permission. Needs READ_CONTACTS.
 *
 * In the layout, a recreated shortcut keeps its title and icon from the backup, and its icon
 * gestures. A gesture that opened one opens its intent instead.
 */
internal class ShortcutRecreator(private val context: Context) {
    private val user = Process.myUserHandle()

    /**
     * Recreates [shortcuts] in the staged layout in [dbFile] and the staged gestures in [prefs].
     * Returns the ones that were recreated.
     */
    suspend fun recreate(shortcuts: Set<ShortcutKey>, dbFile: File, prefs: DataStore<Preferences>?): Set<ShortcutKey> {
        if (shortcuts.isEmpty()) return emptySet()
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptySet()
        }
        val intents = findContactIntents(shortcuts)
        Log.i(TAG, "Found ${intents.size} of ${shortcuts.size} shortcuts in contacts")
        if (intents.isEmpty()) return emptySet()

        val recreated = mutableSetOf<ShortcutKey>()
        if (dbFile.exists()) {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                recreated += recreateInLayout(db, intents)
            }
        }
        if (prefs != null) recreated += recreateInGestures(prefs, intents)
        return recreated
    }

    /** Finds the intents of the contacts app shortcuts in [shortcuts], by shortcut. */
    private fun findContactIntents(shortcuts: Set<ShortcutKey>): Map<ShortcutKey, Intent> {
        data class Wanted(val key: ShortcutKey, val scheme: String, val rest: String)
        val wanted = shortcuts.mapNotNull { key ->
            CONTACT_ACTIONS.entries.firstOrNull { key.id.startsWith(it.key) }
                ?.let { (action, scheme) -> Wanted(key, scheme, key.id.removePrefix(action)) }
        }
        if (wanted.isEmpty()) return emptyMap()

        val intents = mutableMapOf<ShortcutKey, Intent>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.NUMBER, Phone.LOOKUP_KEY),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(0) ?: continue
                val lookupKey = cursor.getString(1) ?: continue
                wanted.forEach { (key, scheme, rest) ->
                    if (key in intents || !rest.startsWith(lookupKey)) return@forEach
                    val uri = Uri.fromParts(scheme, number, null)
                    if (rest.substring(lookupKey.length) != uri.toString().hashCode().toString()) return@forEach
                    val action = if (scheme == SCHEME_TEL) Intent.ACTION_DIAL else Intent.ACTION_SENDTO
                    intents[key] = Intent(action, uri)
                }
            }
        }
        return intents
    }

    /** Publishes the layout's shortcuts in [intents] as this launcher's and points the layout at them. */
    private fun recreateInLayout(db: SQLiteDatabase, intents: Map<ShortcutKey, Intent>): Set<ShortcutKey> {
        data class Row(val id: Long, val key: ShortcutKey, val spec: ConvertedShortcuts.Spec)
        val rows = db.rawQuery(
            "SELECT ${Favorites._ID}, ${Favorites.INTENT}, ${Favorites.TITLE}, ${Favorites.ICON} " +
                "FROM ${Favorites.TABLE_NAME} WHERE ${Favorites.ITEM_TYPE} = ?",
            arrayOf(Favorites.ITEM_TYPE_DEEP_SHORTCUT.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val launcherIntent = cursor.getString(1)
                        ?.let { runCatching { Intent.parseUri(it, 0) }.getOrNull() }
                        ?: continue
                    if (launcherIntent.`package` == null) continue
                    val key = ShortcutKey.fromIntent(launcherIntent, user)
                    val intent = intents[key] ?: continue
                    val icon = if (cursor.isNull(3)) {
                        null
                    } else {
                        cursor.getBlob(3).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                    add(Row(cursor.getLong(0), key, ConvertedShortcuts.Spec(cursor.getString(2).orEmpty(), intent, icon)))
                }
            }
        }
        if (rows.isEmpty()) return emptySet()

        val pinned = ConvertedShortcuts.publishAndPin(context, rows.map { it.spec })
        val recreated = rows.filter { it.spec.id in pinned }
        var updatedRows = 0
        db.beginTransaction()
        try {
            recreated.forEach { row ->
                updatedRows += db.update(
                    Favorites.TABLE_NAME,
                    ContentValues().apply { put(Favorites.INTENT, ConvertedShortcuts.makeLauncherIntent(context, row.spec)) },
                    "${Favorites._ID} = ?",
                    arrayOf(row.id.toString()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(TAG, "Layout: ${rows.size} rows matched, ${pinned.size} pinned, $updatedRows rows updated")
        return recreated.mapTo(mutableSetOf()) { it.key }
    }

    /**
     * Moves the icon gestures of the shortcuts in [intents] to the recreated shortcuts, and makes
     * the gestures that open them open their intents. Returns the shortcuts gestures opened.
     */
    private suspend fun recreateInGestures(
        prefs: DataStore<Preferences>,
        intents: Map<ShortcutKey, Intent>,
    ): Set<ShortcutKey> {
        val iconKeys = intents.map { (key, intent) ->
            IconGestureKeys.forShortcut(key) + ":" to
                IconGestureKeys.forShortcut(ShortcutKey(context.packageName, user, ConvertedShortcuts.idFor(intent))) + ":"
        }
        val opened = mutableSetOf<ShortcutKey>()
        prefs.updateData { current ->
            val updated = current.toMutablePreferences()
            current.asMap().forEach { (prefKey, value) ->
                if (value !is String) return@forEach
                val movedName = iconKeys.firstOrNull { prefKey.name.startsWith(it.first) }
                    ?.let { (from, to) -> to + prefKey.name.removePrefix(from) }
                val movedValue = openIntentInstead(value, intents)
                if (movedName == null && movedValue == null) return@forEach
                movedValue?.let { opened += it.first }
                updated.remove(stringPreferencesKey(prefKey.name))
                updated[stringPreferencesKey(movedName ?: prefKey.name)] = movedValue?.second ?: value
            }
            updated
        }
        return opened
    }

    /** Returns the shortcut a gesture [value] opens and the gesture that opens its intent instead. */
    private fun openIntentInstead(value: String, intents: Map<ShortcutKey, Intent>): Pair<ShortcutKey, String>? {
        if (!value.contains(GestureHandlerConfig.OpenShortcut.SERIAL_NAME)) return null
        val config = runCatching { kotlinxJson.decodeFromString<GestureHandlerConfig>(value) }.getOrNull()
            as? GestureHandlerConfig.OpenShortcut ?: return null
        val key = ShortcutKey(config.target.packageName, config.target.user, config.target.id)
        val intent = intents[key] ?: return null
        val openIntent: GestureHandlerConfig = GestureHandlerConfig.OpenIntent(config.label, intent)
        return key to kotlinxJson.encodeToString(openIntent)
    }

    companion object {
        private const val TAG = "ShortcutRecreator"
        private const val SCHEME_TEL = "tel"

        /** The actions of a contacts app's shortcut ids, and the scheme of their number URIs. */
        private val CONTACT_ACTIONS = mapOf(
            Intent.ACTION_CALL to SCHEME_TEL,
            Intent.ACTION_SENDTO to "smsto",
        )
    }
}
