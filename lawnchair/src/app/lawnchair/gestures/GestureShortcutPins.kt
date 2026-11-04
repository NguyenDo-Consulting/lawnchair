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

package app.lawnchair.gestures

import android.content.Context
import android.os.UserHandle
import androidx.datastore.preferences.core.Preferences
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.kotlinxJson
import com.android.launcher3.shortcuts.ShortcutKey
import java.util.Collections

/**
 * Shortcuts that gestures open with [GestureHandlerConfig.OpenShortcut] must stay pinned in the
 * system, although they aren't on the workspace. The launcher model unpins every shortcut that
 * isn't in its model, so it asks this object for the ones to keep.
 */
object GestureShortcutPins {

    /**
     * Shortcuts that were accepted for a gesture whose config may not be saved yet. Kept until
     * the process ends, which is harmless when the config was never saved.
     */
    private val pendingKeys: MutableSet<ShortcutKey> = Collections.synchronizedSet(mutableSetOf())

    fun addPending(key: ShortcutKey) {
        pendingKeys.add(key)
    }

    @JvmStatic
    fun getShortcutKeys(context: Context, user: UserHandle): List<ShortcutKey> {
        val savedKeys = getSavedShortcutKeys(PreferenceManager2.getInstance(context).getCachedPreferences(), user)
        val pending = synchronized(pendingKeys) { pendingKeys.filter { it.user == user } }
        return (savedKeys + pending).distinct()
    }

    /** Returns the shortcuts that the gestures saved in [prefs] open, e.g. a backup's. */
    fun getSavedShortcutKeys(prefs: Preferences, user: UserHandle): List<ShortcutKey> = prefs.asMap().values
        .asSequence()
        .filterIsInstance<String>()
        .filter { it.contains(GestureHandlerConfig.OpenShortcut.SERIAL_NAME) }
        .mapNotNull { runCatching { kotlinxJson.decodeFromString<GestureHandlerConfig>(it) }.getOrNull() }
        .filterIsInstance<GestureHandlerConfig.OpenShortcut>()
        .map { it.target }
        .filter { it.user == user }
        .map { ShortcutKey(it.packageName, it.user, it.id) }
        .toList()
}
