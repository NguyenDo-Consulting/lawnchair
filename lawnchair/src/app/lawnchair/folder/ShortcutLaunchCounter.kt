package app.lawnchair.folder

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.TimeUnit

/**
 * Records when pinned shortcuts (like a contact's "Message" shortcut) are opened from Lawnchair.
 *
 * Android only logs a shortcut as used when its app reports it (Messenger does, Google Contacts
 * doesn't), so for most shortcuts these taps are the only usage data there is.
 */
object ShortcutLaunchCounter {

    private const val PREFS_NAME = "folder_shortcut_launches"
    val WINDOW_MS = TimeUnit.DAYS.toMillis(14)

    /** Keeps a shortcut someone taps constantly from growing its entry without bound. */
    private const val MAX_TAPS_PER_SHORTCUT = 500

    fun recordLaunch(context: Context, key: String) {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taps = parse(prefs.getString(key, null), now) + now
        prefs.edit { putString(key, taps.takeLast(MAX_TAPS_PER_SHORTCUT).joinToString(",")) }
    }

    /** Taps in the last [WINDOW_MS], keyed by [FolderUsageStats.shortcutKey]. Reads from disk. */
    fun getCounts(context: Context): Map<String, Int> {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (key, value) ->
            val count = parse(value as? String, now).size
            if (count > 0) key to count else null
        }.toMap()
    }

    private fun parse(stored: String?, now: Long): List<Long> {
        if (stored.isNullOrEmpty()) return emptyList()
        return stored.split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { now - it < WINDOW_MS }
    }
}
