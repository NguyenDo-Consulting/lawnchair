package app.lawnchair.gestures

import android.content.Context
import android.os.UserHandle
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
        val savedKeys = PreferenceManager2.getInstance(context).getCachedPreferences().asMap().values
            .asSequence()
            .filterIsInstance<String>()
            .filter { it.contains(GestureHandlerConfig.OpenShortcut.SERIAL_NAME) }
            .mapNotNull { runCatching { kotlinxJson.decodeFromString<GestureHandlerConfig>(it) }.getOrNull() }
            .filterIsInstance<GestureHandlerConfig.OpenShortcut>()
            .map { it.target }
            .filter { it.user == user }
            .map { ShortcutKey(it.packageName, it.user, it.id) }
        val pending = synchronized(pendingKeys) { pendingKeys.filter { it.user == user } }
        return (savedKeys + pending).distinct().toList()
    }
}
