/*
 * Modifications copyright 2026, Nguyen-Do Consulting
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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.util.Log
import app.lawnchair.DeviceProfileOverrides
import app.lawnchair.allapps.filters.DrawerFilter
import app.lawnchair.data.folder.service.FolderService
import app.lawnchair.gestures.IconGestureKeys
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.gestures.handlers.OpenAppTarget
import app.lawnchair.gestures.type.GestureType
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.shortcuts.ConvertedShortcuts
import com.android.launcher3.GridType
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.DatabaseHelper
import com.android.launcher3.model.DeviceGridState
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.pm.UserCache
import com.android.launcher3.provider.RestoreDbTask
import com.android.launcher3.shortcuts.ShortcutKey
import com.android.launcher3.shortcuts.ShortcutRequest
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.GridOccupancy
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URISyntaxException
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class NovaBackupConverter(
    private val context: Context,
    private val uri: Uri,
) {

    companion object {
        private const val TAG = "NovaBackupConverter"
        private const val NOVA_XML = "nova.xml"
        private const val NOVA_DB = "nova.db"
        private const val NOVA_CONTAINER_DESKTOP = -100
        private const val NOVA_CONTAINER_HOTSEAT = -101
        private const val FOLDER_PAGE_RANK_OFFSET = 1_000
        private const val FOLDER_ROW_RANK_OFFSET = 100
        private const val NOVA_TEMP_DIR_PREFIX = "nova_"
        private const val NOVA_WORKSPACE_DB = "nova_workspace.db"
        private const val NOVA_TABLE_FAVORITES = "favorites"
        private const val NOVA_XML_TAG_STRING = "string"
        private const val NOVA_XML_TAG_INT = "int"
        private const val NOVA_XML_TAG_BOOLEAN = "boolean"
        private const val NOVA_XML_ATTR_NAME = "name"
        private const val NOVA_XML_ATTR_VALUE = "value"
        private const val NOVA_XML_KEY_DESKTOP_GRID = "desktop_grid"
        private const val NOVA_XML_KEY_ICON_PACK = "theme_icon_pack"
        private const val NOVA_XML_KEY_DOCK_COLS = "dock_grid_cols"
        private const val NOVA_XML_KEY_DRAWER_COLS = "drawer_app_grid_cols"
        private const val NOVA_XML_KEY_DRAWER_SMART_FOLDERS = "drawer_smart_folders"
        private const val NOVA_XML_KEY_DESKTOP_CELLSPECS = "desktop_cellspecs"
        private const val NOVA_XML_KEY_DRAWER_CELLSPECS = "drawer_cellspecs"

        /**
         * Lawnchair's home screen icon size that looks like Nova's 100%, compared side by side on
         * a Pixel 3 XL. Nova's drawer icon size needs no conversion.
         */
        private const val NOVA_HOME_ICON_SIZE_FACTOR = 0.8f

        private const val NOVA_PACKAGE = "com.teslacoilsw.launcher"
        private const val NOVA_EXTRA_LAUNCHER_ACTION = "LAUNCHER_ACTION"
        private const val NOVA_GESTURE_LABEL_SEPARATOR = '$'
        private const val DEEP_SHORTCUT_CATEGORY = "com.android.launcher3.DEEP_SHORTCUT"

        /** Nova's home screen gesture settings that Lawnchair has a gesture for. */
        private val NOVA_GESTURE_KEYS = setOf(
            "gesture_swipe_up",
            "gesture_swipe_down",
            "gesture_two_finger_scroll_up",
            "gesture_two_finger_scroll_down",
            "gesture_double_tap",
        )

        /** Nova's own gesture actions that Lawnchair has a match for. */
        private val NOVA_LAUNCHER_ACTIONS = mapOf(
            "APP_DRAWER" to GestureHandlerConfig.OpenAppDrawer,
            "APP_SEARCH" to GestureHandlerConfig.OpenAppSearch,
            "EXPAND_STATUS_BAR" to GestureHandlerConfig.OpenNotifications,
            "TEXT_SEARCH" to GestureHandlerConfig.OpenSearch,
        )
        private const val MIN_ICON_SIZE_FACTOR = 0.5f
        private const val MAX_ICON_SIZE_FACTOR = 1.5f
        private const val NOVA_TABLE_DRAWER_GROUPS = "drawer_groups"
        private const val NOVA_TABLE_APP_GROUPS = "appgroups"
        private const val NOVA_GROUP_TYPE_FOLDER = "FOLDER_APP_GROUP"
        private const val NOVA_GROUP_TYPE_TAB = "TAB_APP_GROUP"
        private const val NOVA_ICON_PACK_MIN_PARTS = 3
        private const val NOVA_ICON_PACK_PACKAGE_INDEX = 2
        private const val NOVA_COL_ID = "_id"
        private const val NOVA_COL_CONTAINER = "container"
        private const val NOVA_COL_ITEM_TYPE = "itemType"
        private const val NOVA_COL_TITLE = "title"
        private const val NOVA_COL_INTENT = "intent"
        private const val NOVA_COL_CELL_X = "cellX"
        private const val NOVA_COL_CELL_Y = "cellY"
        private const val NOVA_COL_SCREEN = "screen"
        private const val NOVA_COL_SPAN_X = "spanX"
        private const val NOVA_COL_SPAN_Y = "spanY"
        private const val NOVA_COL_ICON = "icon"
        private const val NOVA_COL_APP_WIDGET_PROVIDER = "appWidgetProvider"
        private const val NOVA_COL_FLING_UP_INTENT = "flingUpIntent"
        private const val NOVA_COL_FLING_DOWN_INTENT = "flingDownIntent"
        private const val NOVA_SMART_FOLDER_MARKER = "FOLDER%3A-"
    }

    private val novaGridRegex = Regex("(\\d+)x(\\d+)")

    data class NovaBackupInfo(
        val columns: Int?,
        val rows: Int?,
        val hotseatCount: Int?,
        val appCount: Int,
        val widgetCount: Int,
        val folderCount: Int,
        val shortcutCount: Int,
        val iconPackPackage: String?,
        val iconPackLabel: String?,
        val drawerColumns: Int? = null,
        val drawerSmartFolders: Boolean = false,
        /** Nova's icon size, where 1.0 is its default size. */
        val homeIconScale: Float? = null,
        val drawerIconScale: Float? = null,
        /** Nova's gesture settings by key, e.g. "gesture_swipe_up" to "App drawer$#Intent;...". */
        val gestures: Map<String, String> = emptyMap(),
    )

    private data class NovaConfig(
        val columns: Int?,
        val rows: Int?,
        val dockCols: Int?,
        val iconPackPackage: String?,
        val drawerColumns: Int?,
        val drawerSmartFolders: Boolean,
        val homeIconScale: Float?,
        val drawerIconScale: Float?,
        val gestures: Map<String, String>,
    )

    private data class RestoredDb(
        /** Other apps' pinned shortcuts to pin, by package. */
        val deepShortcuts: Map<String, Set<String>>,
        /** Ids of the legacy shortcuts converted to Lawnchair's pinned shortcuts, by Nova item id. */
        val convertedShortcutIds: Map<Int, String>,
    )

    /** A swipe action on an icon, with the Nova gesture setting it came from. */
    private data class NovaIconGesture(
        val iconKey: String,
        val gestureType: GestureType,
        val novaGesture: String,
    )

    /** A Nova app drawer folder or tab, with the apps in it as [ComponentKey] strings. */
    private data class NovaDrawerGroup(
        val title: String,
        val isTab: Boolean,
        val hidesApps: Boolean,
        val componentKeys: List<String>,
    )

    private data class ItemCounts(
        val apps: Int,
        val widgets: Int,
        val folders: Int,
        val shortcuts: Int,
    )

    private data class ImportedDeepShortcut(
        val packageName: String,
        val shortcutId: String,
    ) {
        fun toLauncherIntentUri(): String = ShortcutKey.makeIntent(shortcutId, packageName).toUri(0)
    }

    suspend fun parseInfo(): NovaBackupInfo = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "$NOVA_TEMP_DIR_PREFIX${UUID.randomUUID()}")
        tempDir.mkdirs()

        try {
            extractFromZip(uri, tempDir, setOf(NOVA_XML, NOVA_DB))

            val xmlFile = File(tempDir, NOVA_XML)
            val dbFile = File(tempDir, NOVA_DB)
            require(xmlFile.exists() && dbFile.exists()) { "Missing $NOVA_XML or $NOVA_DB" }

            val novaConfig = parseNovaConfig(xmlFile)
            val (apps, widgets, folders, shortcuts) = countItems(dbFile)

            NovaBackupInfo(
                columns = novaConfig.columns,
                rows = novaConfig.rows,
                hotseatCount = novaConfig.dockCols,
                appCount = apps,
                widgetCount = widgets,
                folderCount = folders,
                shortcutCount = shortcuts,
                iconPackPackage = novaConfig.iconPackPackage,
                iconPackLabel = novaConfig.iconPackPackage?.let { packageName ->
                    resolveIconPackLabel(packageName)
                },
                drawerColumns = novaConfig.drawerColumns,
                drawerSmartFolders = novaConfig.drawerSmartFolders,
                homeIconScale = novaConfig.homeIconScale,
                drawerIconScale = novaConfig.drawerIconScale,
                gestures = novaConfig.gestures,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    suspend fun convertAndRestore(info: NovaBackupInfo) = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "$NOVA_TEMP_DIR_PREFIX${UUID.randomUUID()}")
        tempDir.mkdirs()

        try {
            extractFromZip(uri, tempDir, setOf(NOVA_DB))

            val novaDbFile = File(tempDir, NOVA_DB)
            require(novaDbFile.exists()) { "Missing $NOVA_DB" }

            val prefs2 = PreferenceManager2.getInstance(context)
            val stagedDbFile = File(tempDir, NOVA_WORKSPACE_DB)
            val restoredDb = createRestoredDb(
                novaDbFile = novaDbFile,
                targetDbFile = stagedDbFile,
                info = info,
                keepFirstRowFree = prefs2.enableSmartspace.get().first(),
            )
            val drawerGroups = readDrawerGroups(novaDbFile, info.drawerSmartFolders)
            val iconGestures = readIconGestures(novaDbFile, restoredDb.convertedShortcutIds)

            val columns = info.columns
            val rows = info.rows
            val hotseatCount = info.hotseatCount
            if (columns != null && rows != null && hotseatCount != null) {
                val gridInfo = DeviceProfileOverrides.DBGridInfo(
                    numHotseatColumns = hotseatCount,
                    numRows = rows,
                    numColumns = columns,
                )
                val gridState = DeviceGridState(
                    columns,
                    rows,
                    hotseatCount,
                    InvariantDeviceProfile.TYPE_PHONE,
                    gridInfo.dbFile,
                    GridType.GRID_TYPE_ANY,
                )
                gridState.writeToPrefs(context, true)
                gridState.writeToPrefs(context)
                InvariantDeviceProfile.INSTANCE.get(context).dbFile = gridInfo.dbFile
            }
            writeGridToLawnchairPrefs(info)

            val restoredDbFile = context.getDatabasePath(LawnchairBackup.RESTORED_DB_FILE_NAME)
            restoredDbFile.parentFile?.mkdirs()
            stagedDbFile.copyTo(restoredDbFile, overwrite = true)

            val dbController = ModelDbController(context)
            RestoreDbTask.performRestore(context, dbController)

            pinImportedDeepShortcuts(restoredDb.deepShortcuts)
            importDrawerGroups(drawerGroups, prefs2)
            val idp = InvariantDeviceProfile.INSTANCE.get(context)
            info.drawerColumns?.let { columns ->
                prefs2.drawerColumns.set(columns, idp.closestProfile)
            }
            info.homeIconScale?.let { scale ->
                val factor = scale * NOVA_HOME_ICON_SIZE_FACTOR
                prefs2.homeIconSizeFactor.set(factor.coerceIn(MIN_ICON_SIZE_FACTOR, MAX_ICON_SIZE_FACTOR))
            }
            importGestures(info.gestures, prefs2)
            importIconGestures(iconGestures, prefs2)
            info.drawerIconScale?.let { scale ->
                prefs2.drawerIconSizeFactor.set(scale.coerceIn(MIN_ICON_SIZE_FACTOR, MAX_ICON_SIZE_FACTOR))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun resolveIconPackLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun writeGridToLawnchairPrefs(info: NovaBackupInfo) {
        val prefs = PreferenceManager.getInstance(context)
        prefs.sp.edit().apply {
            info.columns?.let { putInt(prefs.workspaceColumns.key, it) }
            info.rows?.let { putInt(prefs.workspaceRows.key, it) }
            info.hotseatCount?.let { putInt(prefs.hotseatColumns.key, it) }
            info.iconPackPackage?.let { putString(prefs.iconPackPackage.key, it) }
        }.commit()
    }

    private fun parseNovaConfig(xmlFile: File): NovaConfig {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
        val root = doc.documentElement
        var columns: Int? = null
        var rows: Int? = null
        var dockCols: Int? = null
        var iconPackPackage: String? = null
        var homeIconScale: Float? = null
        var drawerIconScale: Float? = null
        val gestures = mutableMapOf<String, String>()

        val stringNodes = root.getElementsByTagName(NOVA_XML_TAG_STRING)
        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i)
            val name = node.attributes.getNamedItem(NOVA_XML_ATTR_NAME)?.nodeValue ?: continue
            val text = node.textContent ?: continue
            when (name) {
                NOVA_XML_KEY_DESKTOP_GRID -> {
                    val match = novaGridRegex.find(text) ?: continue
                    rows = match.groupValues[1].toIntOrNull() ?: continue
                    columns = match.groupValues[2].toIntOrNull() ?: continue
                }

                NOVA_XML_KEY_ICON_PACK -> {
                    val parts = text.split(":")
                    if (parts.size >= NOVA_ICON_PACK_MIN_PARTS) iconPackPackage = parts[NOVA_ICON_PACK_PACKAGE_INDEX]
                }

                // e.g. "1.0:false:12.0:#ffffffff:...", which starts with the icon size.
                NOVA_XML_KEY_DESKTOP_CELLSPECS -> homeIconScale = parseCellSpecsIconScale(text)

                NOVA_XML_KEY_DRAWER_CELLSPECS -> drawerIconScale = parseCellSpecsIconScale(text)

                in NOVA_GESTURE_KEYS -> gestures[name] = text
            }
        }

        var drawerColumns: Int? = null
        val intNodes = root.getElementsByTagName(NOVA_XML_TAG_INT)
        for (i in 0 until intNodes.length) {
            val node = intNodes.item(i)
            val name = node.attributes.getNamedItem(NOVA_XML_ATTR_NAME)?.nodeValue ?: continue
            val value = node.attributes.getNamedItem(NOVA_XML_ATTR_VALUE)?.nodeValue?.toIntOrNull() ?: continue
            when (name) {
                NOVA_XML_KEY_DOCK_COLS -> dockCols = value
                NOVA_XML_KEY_DRAWER_COLS -> drawerColumns = value
            }
        }

        var drawerSmartFolders = false
        val booleanNodes = root.getElementsByTagName(NOVA_XML_TAG_BOOLEAN)
        for (i in 0 until booleanNodes.length) {
            val node = booleanNodes.item(i)
            val name = node.attributes.getNamedItem(NOVA_XML_ATTR_NAME)?.nodeValue ?: continue
            if (name == NOVA_XML_KEY_DRAWER_SMART_FOLDERS) {
                drawerSmartFolders = node.attributes.getNamedItem(NOVA_XML_ATTR_VALUE)?.nodeValue == "true"
            }
        }

        return NovaConfig(
            columns = columns,
            rows = rows,
            dockCols = dockCols,
            iconPackPackage = iconPackPackage,
            drawerColumns = drawerColumns,
            drawerSmartFolders = drawerSmartFolders,
            homeIconScale = homeIconScale,
            drawerIconScale = drawerIconScale,
            gestures = gestures,
        )
    }

    private fun parseCellSpecsIconScale(cellSpecs: String): Float? = cellSpecs.substringBefore(':').toFloatOrNull()?.takeIf { it > 0f }

    private fun countItems(dbFile: File): ItemCounts {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            it.rawQuery(
                """SELECT $NOVA_COL_ITEM_TYPE, COUNT(*) FROM $NOVA_TABLE_FAVORITES
                    WHERE $NOVA_COL_ITEM_TYPE != -1
                    AND ($NOVA_COL_CONTAINER = $NOVA_CONTAINER_DESKTOP
                        OR $NOVA_COL_CONTAINER = $NOVA_CONTAINER_HOTSEAT
                        OR $NOVA_COL_CONTAINER >= 0
                        OR $NOVA_COL_CONTAINER <= -200)
                    GROUP BY $NOVA_COL_ITEM_TYPE""",
                null,
            ).use { cursor ->
                var apps = 0
                var widgets = 0
                var folders = 0
                var shortcuts = 0
                while (cursor.moveToNext()) {
                    val type = cursor.getInt(0)
                    val count = cursor.getInt(1)
                    when (type) {
                        Favorites.ITEM_TYPE_APPLICATION -> apps += count
                        Favorites.ITEM_TYPE_APPWIDGET, Favorites.ITEM_TYPE_CUSTOM_APPWIDGET -> widgets += count
                        Favorites.ITEM_TYPE_FOLDER -> folders += count
                        Favorites.ITEM_TYPE_SHORTCUT, Favorites.ITEM_TYPE_DEEP_SHORTCUT -> shortcuts += count
                    }
                }
                ItemCounts(apps, widgets, folders, shortcuts)
            }
        }
    }

    private fun createRestoredDb(
        novaDbFile: File,
        targetDbFile: File,
        info: NovaBackupInfo,
        keepFirstRowFree: Boolean,
    ): RestoredDb {
        val profileId = UserCache.INSTANCE.get(context)
            .getSerialNumberForUser(Process.myUserHandle())
        val importedDeepShortcuts = mutableMapOf<String, MutableSet<String>>()
        val legacyShortcuts = mutableListOf<Pair<Int, ConvertedShortcuts.Spec>>()
        var convertedShortcutIds = emptyMap<Int, String>()

        val targetDb = SQLiteDatabase.openOrCreateDatabase(targetDbFile, null)
        targetDb.use { db ->
            db.version = DatabaseHelper.SCHEMA_VERSION
            Favorites.addTableToDb(db, profileId, false)

            val novaDb = SQLiteDatabase.openDatabase(
                novaDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            novaDb.use { src ->
                db.beginTransaction()
                try {
                    insertNovaItems(src, db, profileId, info.hotseatCount, importedDeepShortcuts, legacyShortcuts)
                    convertedShortcutIds = convertLegacyShortcuts(db, legacyShortcuts)
                    val columns = info.columns
                    val rows = info.rows
                    if (keepFirstRowFree && columns != null && rows != null) {
                        moveItemsOutOfFirstRow(db, columns, rows)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
        return RestoredDb(
            deepShortcuts = importedDeepShortcuts.mapValues { (_, shortcutIds) -> shortcutIds.toSet() },
            convertedShortcutIds = convertedShortcutIds,
        )
    }

    /**
     * Converts the legacy shortcuts, like Nova's direct dial contacts, to pinned shortcuts that
     * Lawnchair publishes, so they get gestures and the long-press menu. The ones that can't be
     * pinned, e.g. when Lawnchair isn't the default home app, stay legacy shortcuts.
     *
     * @return the converted shortcut ids, by Nova item id
     */
    private fun convertLegacyShortcuts(
        db: SQLiteDatabase,
        legacyShortcuts: List<Pair<Int, ConvertedShortcuts.Spec>>,
    ): Map<Int, String> {
        if (legacyShortcuts.isEmpty()) return emptyMap()
        val pinnedIds = ConvertedShortcuts.publishAndPin(context, legacyShortcuts.map { it.second })
        return buildMap {
            legacyShortcuts.forEach { (novaId, spec) ->
                if (spec.id !in pinnedIds) return@forEach
                db.execSQL(
                    "UPDATE favorites SET itemType = ?, intent = ? WHERE _id = ?",
                    arrayOf<Any>(Favorites.ITEM_TYPE_DEEP_SHORTCUT, ConvertedShortcuts.makeLauncherIntent(context, spec), novaId),
                )
                put(novaId, spec.id)
            }
        }
    }

    private fun insertNovaItems(
        src: SQLiteDatabase,
        db: SQLiteDatabase,
        profileId: Long,
        hotseatCount: Int?,
        importedDeepShortcuts: MutableMap<String, MutableSet<String>>,
        legacyShortcuts: MutableList<Pair<Int, ConvertedShortcuts.Spec>>,
    ) {
        val smartFolderMap = buildSmartFolderMap(src)
        // Nova can keep stale dock entries past the last dock column, which it doesn't show.
        val hiddenDockIds = hotseatCount?.let { findDockItemsPastColumn(src, it) }.orEmpty()

        src.rawQuery(
            "SELECT * FROM $NOVA_TABLE_FAVORITES WHERE $NOVA_COL_ITEM_TYPE != -1",
            null,
        ).use { cursor ->
            val now = System.currentTimeMillis()

            while (cursor.moveToNext()) {
                val novaId = cursor.getInt(cursor.getColumnIndexOrThrow(NOVA_COL_ID))
                val novaContainer = cursor.getInt(cursor.getColumnIndexOrThrow(NOVA_COL_CONTAINER))
                val itemType = cursor.getInt(cursor.getColumnIndexOrThrow(NOVA_COL_ITEM_TYPE))

                val isDesktop = novaContainer == NOVA_CONTAINER_DESKTOP
                val isHotseat = novaContainer == NOVA_CONTAINER_HOTSEAT
                val isFolderChild = novaContainer >= 0
                val isSmartFolderChild = novaContainer in smartFolderMap

                if (!isDesktop && !isHotseat && !isFolderChild && !isSmartFolderChild) continue
                if (novaId in hiddenDockIds || novaContainer in hiddenDockIds) continue
                if (isSmartFolderChild && smartFolderMap.getValue(novaContainer) in hiddenDockIds) continue

                val container = when {
                    isDesktop -> Favorites.CONTAINER_DESKTOP
                    isHotseat -> Favorites.CONTAINER_HOTSEAT
                    isSmartFolderChild -> smartFolderMap.getValue(novaContainer)
                    else -> novaContainer
                }

                val title = getStringOrNull(cursor, NOVA_COL_TITLE)
                /* TODO: Lawnchair folder synchronization support
                 *        For nova parity:
                 *           Sync folder contents from allapps to workspace, or workspace to allapps
                 *        ImplNote:
                 *           Check folder with intent: #Intent;component=com.teslacoilsw.launcher/FOLDER%3A-20X;end
                 *           X being category ID that are defined in drawer_group table
                 */
                val rawIntent = getStringOrNull(cursor, NOVA_COL_INTENT)
                val importedDeepShortcut = if (itemType == Favorites.ITEM_TYPE_DEEP_SHORTCUT) {
                    parseNovaDeepShortcut(rawIntent)?.also { shortcut ->
                        importedDeepShortcuts.getOrPut(shortcut.packageName) { mutableSetOf() }
                            .add(shortcut.shortcutId)
                    }
                } else {
                    null
                }
                val intent = when {
                    importedDeepShortcut != null -> importedDeepShortcut.toLauncherIntentUri()
                    itemType == Favorites.ITEM_TYPE_SHORTCUT -> dialInsteadOfCall(rawIntent)
                    else -> rawIntent
                }
                val cellX = cursor.getDouble(cursor.getColumnIndexOrThrow(NOVA_COL_CELL_X)).toInt()
                val cellY = cursor.getDouble(cursor.getColumnIndexOrThrow(NOVA_COL_CELL_Y)).toInt()
                val screen = if (isHotseat) cellX else cursor.getInt(cursor.getColumnIndexOrThrow(NOVA_COL_SCREEN))
                val spanX = cursor.getDouble(cursor.getColumnIndexOrThrow(NOVA_COL_SPAN_X)).toInt().coerceAtLeast(1)
                val spanY = cursor.getDouble(cursor.getColumnIndexOrThrow(NOVA_COL_SPAN_Y)).toInt().coerceAtLeast(1)
                val icon = getBlobOrNull(cursor, NOVA_COL_ICON)
                if (itemType == Favorites.ITEM_TYPE_SHORTCUT && intent != null) {
                    runCatching { Intent.parseUri(intent, 0) }.getOrNull()?.let { parsedIntent ->
                        val bitmap = icon?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        legacyShortcuts.add(novaId to ConvertedShortcuts.Spec(title.orEmpty(), parsedIntent, bitmap))
                    }
                }
                val appWidgetProvider = getStringOrNull(cursor, NOVA_COL_APP_WIDGET_PROVIDER)
                val rank = when {
                    isFolderChild || isSmartFolderChild -> calculateFolderRank(screen, cellX, cellY)
                    isHotseat -> screen
                    else -> 0
                }

                db.execSQL(
                    """INSERT INTO favorites (
                        _id, title, intent, container, screen, cellX, cellY,
                        spanX, spanY, itemType, appWidgetId, icon,
                        appWidgetProvider, modified, restored, profileId,
                        rank, options, appWidgetSource
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, -1, ?, ?, ?, 0, ?, ?, 0, -1)""",
                    arrayOf(
                        novaId,
                        title,
                        intent,
                        container,
                        screen,
                        cellX,
                        if (isHotseat) 0 else cellY,
                        spanX,
                        spanY,
                        itemType,
                        icon,
                        appWidgetProvider,
                        now,
                        profileId,
                        rank,
                    ),
                )
            }
        }
    }

    private fun findDockItemsPastColumn(src: SQLiteDatabase, hotseatCount: Int): Set<Int> {
        val cursor = src.rawQuery(
            """SELECT $NOVA_COL_ID FROM $NOVA_TABLE_FAVORITES
                WHERE $NOVA_COL_CONTAINER = $NOVA_CONTAINER_HOTSEAT
                AND $NOVA_COL_CELL_X >= ?""",
            arrayOf(hotseatCount.toString()),
        )
        return cursor.use { buildSet { while (it.moveToNext()) add(it.getInt(0)) } }
    }

    /**
     * Nova's direct dial shortcuts place the call themselves, which needs the CALL_PHONE
     * permission that Lawnchair doesn't have. Opens the dialer with the number filled in instead.
     */
    private fun dialInsteadOfCall(intentUri: String?): String? {
        if (intentUri.isNullOrEmpty()) return intentUri
        val intent = try {
            Intent.parseUri(intentUri, 0)
        } catch (_: URISyntaxException) {
            return intentUri
        }
        if (intent.action != Intent.ACTION_CALL) return intentUri
        return intent.setAction(Intent.ACTION_DIAL).toUri(0)
    }

    /**
     * Lawnchair keeps the top row of the first home screen page for At a Glance, while Nova's
     * search bar doesn't take a grid row. Moves items that start in that row to the first free
     * spot on the page, or onto new pages after the last one when it's full, so the loader
     * doesn't delete them as overlapping. Items bigger than the grid are shrunk to fit it. The
     * user can move and resize them later.
     */
    private fun moveItemsOutOfFirstRow(db: SQLiteDatabase, columns: Int, rows: Int) {
        val desktop = Favorites.CONTAINER_DESKTOP.toString()
        val screens = db.rawQuery(
            "SELECT MIN(screen), MAX(screen) FROM favorites WHERE container = ?",
            arrayOf(desktop),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return
            cursor.getInt(0) to cursor.getInt(1)
        }
        val (firstScreen, lastScreen) = screens

        data class Item(val id: Int, val cellX: Int, val cellY: Int, val spanX: Int, val spanY: Int)
        val items = db.rawQuery(
            "SELECT _id, cellX, cellY, spanX, spanY FROM favorites WHERE container = ? AND screen = ?",
            arrayOf(desktop, firstScreen.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Item(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3), cursor.getInt(4)))
                }
            }
        }
        val (blocked, kept) = items.partition { it.cellY < 1 }
        if (blocked.isEmpty()) return

        val firstPage = GridOccupancy(columns, rows)
        firstPage.markCells(0, 0, columns, 1, true)
        kept.forEach { firstPage.markCells(it.cellX, it.cellY, it.spanX, it.spanY, true) }
        // Pages added after the last one, in order, for items that don't fit on the first page.
        val newPages = mutableListOf<GridOccupancy>()
        val cell = IntArray(2)

        blocked.forEach { item ->
            val spanX = item.spanX.coerceIn(1, columns)
            val spanY = item.spanY.coerceIn(1, rows)
            var screen = firstScreen
            var page = firstPage
            if (!page.findVacantCell(cell, spanX, spanY)) {
                val index = newPages.indexOfFirst { it.findVacantCell(cell, spanX, spanY) }
                    .takeIf { it >= 0 }
                    ?: newPages.size.also {
                        newPages.add(GridOccupancy(columns, rows))
                        cell[0] = 0
                        cell[1] = 0
                    }
                screen = lastScreen + 1 + index
                page = newPages[index]
            }
            page.markCells(cell[0], cell[1], spanX, spanY, true)
            db.execSQL(
                "UPDATE favorites SET screen = ?, cellX = ?, cellY = ?, spanX = ?, spanY = ? WHERE _id = ?",
                arrayOf(screen, cell[0], cell[1], spanX, spanY, item.id),
            )
        }
    }

    /**
     * Reads Nova's app drawer folders and tabs, with their apps that are installed here. Nova's
     * automatic category folders are only read when its drawer smart folders setting is on.
     */
    private fun readDrawerGroups(novaDbFile: File, includeCategoryFolders: Boolean): List<NovaDrawerGroup> {
        val user = Process.myUserHandle()
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val src = SQLiteDatabase.openDatabase(novaDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return src.use { db ->
            if (!db.hasTable(NOVA_TABLE_DRAWER_GROUPS) || !db.hasTable(NOVA_TABLE_APP_GROUPS)) {
                return@use emptyList()
            }
            db.rawQuery(
                """SELECT _id, title, groupType, hideApps, category_id FROM $NOVA_TABLE_DRAWER_GROUPS
                    ORDER BY tabOrder, _id""",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val isTab = when (cursor.getString(2)) {
                            NOVA_GROUP_TYPE_TAB -> true
                            NOVA_GROUP_TYPE_FOLDER -> false
                            else -> continue
                        }
                        val isCategoryFolder = !isTab && !cursor.isNull(4)
                        if (isCategoryFolder && !includeCategoryFolders) continue
                        val title = cursor.getString(1)?.trim().orEmpty()
                        if (title.isEmpty()) continue
                        val componentKeys = readGroupApps(db, cursor.getInt(0), user, launcherApps)
                        if (componentKeys.isEmpty()) continue
                        add(NovaDrawerGroup(title, isTab, cursor.getInt(3) != 0, componentKeys))
                    }
                }
            }
        }
    }

    private fun readGroupApps(
        db: SQLiteDatabase,
        groupId: Int,
        user: UserHandle,
        launcherApps: LauncherApps,
    ): List<String> = db.rawQuery(
        "SELECT component FROM $NOVA_TABLE_APP_GROUPS WHERE groupId = ? ORDER BY _id",
        arrayOf(groupId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                // Nova adds the user after a '#', e.g. "com.whatsapp/.Main#-1".
                val flattened = cursor.getString(0)?.substringBefore('#') ?: continue
                val component = ComponentName.unflattenFromString(flattened) ?: continue
                val isInstalled = try {
                    launcherApps.isActivityEnabled(component, user)
                } catch (_: Exception) {
                    false
                }
                if (isInstalled) add(ComponentKey(component, user).toString())
            }
        }.distinct()
    }

    /**
     * Adds Nova's drawer folders as Lawnchair drawer folders, and its drawer tabs as drawer
     * filters. Skips any whose title is already used, so importing twice doesn't add copies.
     */
    private suspend fun importDrawerGroups(groups: List<NovaDrawerGroup>, prefs2: PreferenceManager2) {
        val (tabs, folders) = groups.partition { it.isTab }

        if (folders.isNotEmpty()) {
            val folderService = FolderService.INSTANCE.get(context)
            val usedTitles = folderService.getFoldersFlow().first()
                .mapTo(mutableSetOf()) { it.title.trim().lowercase() }
            folders.filter { usedTitles.add(it.title.lowercase()) }.forEach { folder ->
                val folderId = folderService.saveFolderInfo(folder.title).toInt()
                folderService.updateFolderWithItems(folderId, folder.title, folder.componentKeys)
            }
        }

        if (tabs.isNotEmpty()) {
            val filters = prefs2.drawerFilters.get().first()
            val usedTitles = filters.mapTo(mutableSetOf()) { it.title.trim().lowercase() }
            val newTabs = tabs.filter { usedTitles.add(it.title.lowercase()) }
            if (newTabs.isEmpty()) return
            prefs2.drawerFilters.set(
                filters + newTabs.map { tab ->
                    DrawerFilter(id = UUID.randomUUID().toString(), title = tab.title, componentKeys = tab.componentKeys)
                },
            )
            // Nova's tabs can leave their apps out of the main list, like this setting does.
            if (newTabs.any { it.hidesApps }) prefs2.hideFilteredAppsFromAll.set(true)
        }
    }

    /**
     * Sets Lawnchair's home screen gestures from Nova's. Gestures whose Nova action has no match
     * here keep their Lawnchair setting.
     */
    private suspend fun importGestures(gestures: Map<String, String>, prefs2: PreferenceManager2) {
        gestures.forEach { (key, value) ->
            val preference = when (key) {
                "gesture_swipe_up" -> prefs2.swipeUpGestureHandler
                "gesture_swipe_down" -> prefs2.swipeDownGestureHandler
                "gesture_two_finger_scroll_up" -> prefs2.twoFingerSwipeUpGestureHandler
                "gesture_two_finger_scroll_down" -> prefs2.twoFingerSwipeDownGestureHandler
                "gesture_double_tap" -> prefs2.doubleTapGestureHandler
                else -> return@forEach
            }
            val config = toGestureHandlerConfig(value)
            if (config == null) {
                Log.d(TAG, "No Lawnchair match for Nova gesture $key: $value")
                return@forEach
            }
            preference.set(config)
        }
    }

    /**
     * Converts a Nova gesture setting, a label and an intent such as
     * "App drawer$#Intent;...;S.LAUNCHER_ACTION=APP_DRAWER;end", to a Lawnchair gesture action.
     * Returns null for Nova actions Lawnchair doesn't have and apps that aren't installed.
     */
    private fun toGestureHandlerConfig(novaGesture: String): GestureHandlerConfig? {
        val label = novaGesture.substringBefore(NOVA_GESTURE_LABEL_SEPARATOR, missingDelimiterValue = "")
        val intentUri = novaGesture.substringAfter(NOVA_GESTURE_LABEL_SEPARATOR, missingDelimiterValue = "")
        if (intentUri.isEmpty()) return null
        val intent = try {
            Intent.parseUri(intentUri, 0)
        } catch (_: URISyntaxException) {
            return null
        }

        intent.getStringExtra(NOVA_EXTRA_LAUNCHER_ACTION)?.let { return NOVA_LAUNCHER_ACTIONS[it] }
        // A pinned shortcut names its app's activity too, but opening that would be wrong.
        if (intent.hasCategory(DEEP_SHORTCUT_CATEGORY)) return null

        val component = intent.component
        if (component == null) {
            // E.g. texting a contact: "smsto:..." with the SENDTO action.
            if (intent.action == null || intent.data == null) return null
            if (intent.action == Intent.ACTION_CALL) intent.action = Intent.ACTION_DIAL
            return GestureHandlerConfig.OpenIntent(
                label = label.ifEmpty { intent.dataString.orEmpty() },
                intent = intent,
            )
        }
        if (component.packageName.startsWith(NOVA_PACKAGE)) return null
        val user = Process.myUserHandle()
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        val isInstalled = try {
            launcherApps.isActivityEnabled(component, user)
        } catch (_: Exception) {
            false
        }
        if (!isInstalled) return null
        return GestureHandlerConfig.OpenApp(
            appName = label.ifEmpty { component.packageName },
            target = OpenAppTarget.App(ComponentKey(component, user)),
        )
    }

    /**
     * Reads the swipe up and down actions on app icons and on the legacy shortcuts converted to
     * Lawnchair's pinned shortcuts. Lawnchair keeps app icon gestures per app, so an app's actions
     * apply to all its icons. Swipe actions on folders and other kinds of icons aren't read, since
     * Lawnchair can't do those.
     *
     * TODO: When Lawnchair gets folder swipe gestures, import Nova's too. Nova keeps them in the
     *  same flingUpIntent / flingDownIntent columns on the folder's row (itemType 2), e.g. a dock
     *  folder that opens Phone on swipe up.
     *
     * @param convertedShortcutIds the converted shortcut ids, by Nova item id
     */
    private fun readIconGestures(novaDbFile: File, convertedShortcutIds: Map<Int, String>): List<NovaIconGesture> {
        val user = Process.myUserHandle()
        val src = SQLiteDatabase.openDatabase(novaDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return src.use { db ->
            db.rawQuery(
                """SELECT * FROM $NOVA_TABLE_FAVORITES WHERE $NOVA_COL_ITEM_TYPE IN
                    (${Favorites.ITEM_TYPE_APPLICATION}, ${Favorites.ITEM_TYPE_SHORTCUT})""",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val novaId = cursor.getInt(cursor.getColumnIndexOrThrow(NOVA_COL_ID))
                        val convertedId = convertedShortcutIds[novaId]
                        val iconKey = if (convertedId != null) {
                            IconGestureKeys.forShortcut(ShortcutKey(context.packageName, user, convertedId))
                        } else {
                            getStringOrNull(cursor, NOVA_COL_INTENT)
                                ?.let { runCatching { Intent.parseUri(it, 0).component }.getOrNull() }
                                ?.takeIf { cursor.getInt(cursor.getColumnIndexOrThrow(NOVA_COL_ITEM_TYPE)) == Favorites.ITEM_TYPE_APPLICATION }
                                ?.let { ComponentKey(it, user).toString() }
                                ?: continue
                        }
                        getStringOrNull(cursor, NOVA_COL_FLING_UP_INTENT)
                            ?.let { add(NovaIconGesture(iconKey, GestureType.SWIPE_UP, it)) }
                        getStringOrNull(cursor, NOVA_COL_FLING_DOWN_INTENT)
                            ?.let { add(NovaIconGesture(iconKey, GestureType.SWIPE_DOWN, it)) }
                    }
                }
            }
        }
    }

    private suspend fun importIconGestures(gestures: List<NovaIconGesture>, prefs2: PreferenceManager2) {
        gestures.distinctBy { it.iconKey to it.gestureType }.forEach { gesture ->
            val config = toGestureHandlerConfig(gesture.novaGesture)
            if (config == null) {
                Log.d(TAG, "No Lawnchair match for Nova icon gesture: ${gesture.novaGesture}")
                return@forEach
            }
            prefs2.setGestureForIcon(gesture.iconKey, gesture.gestureType, config)
        }
    }

    private fun SQLiteDatabase.hasTable(name: String): Boolean = rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    private fun pinImportedDeepShortcuts(importedDeepShortcuts: Map<String, Set<String>>) {
        if (importedDeepShortcuts.isEmpty()) return

        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        val user = Process.myUserHandle()

        importedDeepShortcuts.forEach { (packageName, shortcutIds) ->
            val pinnedShortcutIds = linkedSetOf<String>()
            val currentPinnedShortcuts = ShortcutRequest(context, user)
                .forPackage(packageName)
                .query(ShortcutRequest.PINNED or LauncherApps.ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY)
            if (currentPinnedShortcuts.wasSuccess()) {
                currentPinnedShortcuts.mapTo(pinnedShortcutIds) { it.id }
            }
            pinnedShortcutIds.addAll(shortcutIds)

            try {
                launcherApps.pinShortcuts(packageName, pinnedShortcutIds.toList(), user)
            } catch (e: SecurityException) {
                Log.w(TAG, "Failed to pin imported deep shortcuts for $packageName", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Failed to pin imported deep shortcuts for $packageName", e)
            }
        }
    }

    private fun getStringOrNull(cursor: android.database.Cursor, column: String): String? {
        val idx = cursor.getColumnIndex(column)
        return if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
    }

    private fun getBlobOrNull(cursor: android.database.Cursor, column: String): ByteArray? {
        val idx = cursor.getColumnIndex(column)
        return if (idx >= 0 && !cursor.isNull(idx)) cursor.getBlob(idx) else null
    }

    private fun extractFromZip(uri: Uri, destDir: File, fileNames: Set<String>) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Unable to open backup URI")
        val destCanonical = destDir.canonicalPath
        pfd.use {
            FileInputStream(it.fileDescriptor).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name in fileNames) {
                            val outFile = File(destDir, entry.name)
                            require(outFile.canonicalPath.startsWith(destCanonical + File.separator)) {
                                "Zip entry outside target dir: ${entry.name}"
                            }
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        }
    }

    private fun calculateFolderRank(screen: Int, cellX: Int, cellY: Int): Int {
        return (screen * FOLDER_PAGE_RANK_OFFSET) + (cellY * FOLDER_ROW_RANK_OFFSET) + cellX
    }

    /**
     * Get smart folder header rows from favorites table and builds a mapping from the smart folder
     * container ID (-20X) to the folder row's _id. Smart folders can be on the home screen or in
     * the dock.
     *
     * X being category ID that are defined in drawer_group table
     */
    private fun buildSmartFolderMap(src: SQLiteDatabase): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        src.rawQuery(
            """SELECT $NOVA_COL_ID, $NOVA_COL_INTENT FROM $NOVA_TABLE_FAVORITES
                WHERE $NOVA_COL_ITEM_TYPE = ${Favorites.ITEM_TYPE_FOLDER}
                AND $NOVA_COL_CONTAINER IN ($NOVA_CONTAINER_DESKTOP, $NOVA_CONTAINER_HOTSEAT)
                AND $NOVA_COL_INTENT LIKE '%$NOVA_SMART_FOLDER_MARKER%'""",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val folderId = cursor.getInt(0)
                val intentUri = cursor.getString(1) ?: continue
                val smartContainerId = extractSmartFolderContainerId(intentUri)
                if (smartContainerId != null) {
                    map[smartContainerId] = folderId
                }
            }
        }
        return map
    }

    /**
     * Get smart folder container ID from a Nova folder intent URI.
     * `#Intent;component=com.teslacoilsw.launcher/FOLDER%3A-208;end` -> -208
     */
    private fun extractSmartFolderContainerId(intentUri: String): Int? {
        val marker = "FOLDER%3A"
        val idx = intentUri.indexOf(marker)
        if (idx < 0) return null
        val afterMarker = intentUri.substring(idx + marker.length)
        val numStr = afterMarker.takeWhile { it == '-' || it.isDigit() }
        return numStr.toIntOrNull()
    }

    private fun parseNovaDeepShortcut(intentUri: String?): ImportedDeepShortcut? {
        if (intentUri.isNullOrEmpty()) return null

        val intent = try {
            Intent.parseUri(intentUri, 0)
        } catch (_: URISyntaxException) {
            return null
        }

        val packageName = intent.`package` ?: intent.component?.packageName ?: return null
        val shortcutId = intent.getStringExtra(ShortcutKey.EXTRA_SHORTCUT_ID) ?: return null
        return ImportedDeepShortcut(packageName, shortcutId)
    }

    /*
     * Glossary: drawer_group
     *
     * The drawer_group table define app drawer tabs (groups) in Nova.
     * This table is the following category from category_id in drawer_group table:
     * 1. null (known as: "Apps", for items that belong to main profile)
     * 2. null (known as: "Work", for items that belong to work profile)
     * 3. ENTERTAINMENT
     * 4. FINANCE
     * 5. FOOD
     * 6. GAMES
     * 7. SHOPPING
     * 8. SOCIAL
     * 9. TRAVEL
     *
     * Note: This table is related to the appgroups table which is a table that already assigns apps
     *       to these categories.
     *
     * Note: Nova assigns items to these categories by crowdsourcing,
     *       unlike our Flowerpot (v1) Azalea system that needs to be constantly updated manually.
     */
}
