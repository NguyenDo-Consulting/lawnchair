package app.lawnchair.gestures

import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.shortcuts.ShortcutKey

/**
 * Keys that icon swipe gestures are stored under in
 * [app.lawnchair.preferences2.PreferenceManager2.setGestureForIcon].
 */
object IconGestureKeys {
    private const val SHORTCUT_PREFIX = "shortcut:"

    /**
     * Returns the gesture key for an icon. A pinned shortcut has its own gestures, so that e.g.
     * every conversation shortcut of a messaging app can do something different. Anything
     * else uses the app's component key.
     */
    @JvmStatic
    fun forItem(info: ItemInfo): String? {
        if (info.itemType == ITEM_TYPE_DEEP_SHORTCUT) {
            return forShortcut(ShortcutKey.fromItemInfo(info))
        }
        return info.componentKey?.toString()
    }

    @JvmStatic
    fun forShortcut(key: ShortcutKey): String = SHORTCUT_PREFIX + key
}
