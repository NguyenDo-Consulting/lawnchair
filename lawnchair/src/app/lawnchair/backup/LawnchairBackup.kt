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

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.lawnchair.LawnchairProto.BackupInfo
import app.lawnchair.shortcuts.ConvertedShortcuts
import app.lawnchair.util.hasFlag
import app.lawnchair.util.scaleDownTo
import app.lawnchair.util.scaleDownToDisplaySize
import app.lawnchair.wallpaper.WallpaperColorsCompat
import app.lawnchair.wallpaper.WallpaperManagerCompat
import com.android.launcher3.BuildConfig
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherFiles
import com.android.launcher3.LauncherSettings
import com.android.launcher3.R
import com.android.launcher3.model.DeviceGridState
import com.android.launcher3.provider.RestoreDbTask
import com.google.protobuf.Timestamp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

class LawnchairBackup(
    private val context: Context,
    private val uri: Uri,
) {
    lateinit var info: BackupInfo
    var screenshot: Bitmap? = null
    var wallpaper: Bitmap? = null

    suspend fun readInfoAndPreview() {
        var tmpScreenshot: Bitmap? = null
        var tmpWallpaper: Bitmap? = null
        readZip(
            mapOf(
                INFO_FILE_NAME to { info = BackupInfo.newBuilder().mergeFrom(it).build() },
                SCREENSHOT_FILE_NAME to { tmpScreenshot = BitmapFactory.decodeStream(it) },
                WALLPAPER_FILE_NAME to { tmpWallpaper = BitmapFactory.decodeStream(it) },
            ),
        )
        val size = max(info.previewWidth, info.previewHeight).coerceAtMost(4000)
        screenshot = tmpScreenshot?.scaleDownTo(size)
        wallpaper = tmpWallpaper?.scaleDownToDisplaySize(context)
    }

    /**
     * Extracts the selected contents into a staging dir, and nothing is applied until the whole
     * backup has been extracted and checked. The wallpaper is then set, and the layout and settings
     * are left for [applyPendingRestore] to move into place on the next start. The caller must
     * restart the process afterwards.
     *
     * The layout and settings can't be written over the live files: this process holds the current
     * preferences in memory, and its next write would put them back on disk.
     *
     * @param recreateShortcuts whether the shortcuts that can't be pinned again are recreated
     * when possible, see [ShortcutRecreator]
     */
    suspend fun restore(selectedContents: Int, recreateShortcuts: Boolean = false) {
        val contents = selectedContents and info.contents
        val restoreLayout = contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)
        val restoreWallpaper = contents.hasFlag(INCLUDE_WALLPAPER)
        val entryNames = buildList {
            if (restoreLayout) addAll(getFiles(context, forRestore = true).keys)
            if (restoreWallpaper) add(WALLPAPER_FILE_NAME)
        }
        val stagingDir = pendingRestoreDir(context)
        try {
            withContext(Dispatchers.IO) {
                stagingDir.deleteRecursively()
                stagingDir.mkdirs()
            }
            readZip(
                entryNames.associateWith<String, suspend (InputStream) -> Unit> { name ->
                    { input -> File(stagingDir, name).outputStream().use { out -> input.copyTo(out) } }
                },
            )
            withContext(Dispatchers.IO) {
                if (restoreLayout) {
                    verifyStagedLayout(stagingDir)
                    restoreShortcuts(stagingDir, recreateShortcuts)
                    // applyPendingRestore only applies a staging dir that has it. Written before
                    // the wallpaper is set, so a failure from here on still changes nothing: the
                    // catch below deletes the staging dir with it.
                    File(stagingDir, INFO_FILE_NAME).outputStream().use { info.writeTo(it) }
                }
                if (restoreWallpaper) {
                    val wallpaperFile = File(stagingDir, WALLPAPER_FILE_NAME)
                    if (wallpaperFile.exists()) {
                        val bitmap = checkNotNull(BitmapFactory.decodeFile(wallpaperFile.path)) {
                            "failed to decode $WALLPAPER_FILE_NAME"
                        }
                        WallpaperManager.getInstance(context).setBitmap(bitmap)
                        wallpaperFile.delete()
                    }
                }
                if (!restoreLayout) stagingDir.deleteRecursively()
            }
        } catch (t: Throwable) {
            stagingDir.deleteRecursively()
            throw t
        }
    }

    private fun verifyStagedLayout(stagingDir: File) {
        val dbFile = File(stagingDir, LAUNCHER_DB_FILE_NAME)
        if (!dbFile.exists()) return
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val result = DatabaseUtils.stringForQuery(db, "PRAGMA quick_check", null)
            check(result == "ok") { "$LAUNCHER_DB_FILE_NAME is corrupted: $result" }
        }
    }

    /**
     * Pinned shortcuts aren't in a backup, and are pinned per launcher package, so the backup's
     * shortcuts are pinned again for this package. Without that, they'd be deleted on a new device
     * or in a launcher with another package. The legacy shortcuts Lawnchair converted to its own
     * pinned shortcuts are published again from the backup's layout, and other apps' shortcuts
     * are pinned by [BackupShortcutPins]. With [recreate], the ones that can't be pinned are
     * recreated by [ShortcutRecreator] when possible. The rest are counted for
     * [consumeUnrestoredShortcutCount].
     */
    private suspend fun restoreShortcuts(stagingDir: File, recreate: Boolean) {
        val dbFile = File(stagingDir, LAUNCHER_DB_FILE_NAME)
        val countFile = File(context.filesDir, UNRESTORED_SHORTCUTS_FILE_NAME)
        val unrestoredConverted = if (dbFile.exists()) {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use {
                ConvertedShortcuts.republish(context, it)
            }
        } else {
            0
        }
        val prefsFile = File(stagingDir, PREFS_DATASTORE_FILE_NAME)
        // One DataStore for the staged file, closed with its scope: a file can only have one.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val unrestoredOther = try {
            val prefs = if (prefsFile.exists()) PreferenceDataStoreFactory.create(scope = scope) { prefsFile } else null
            val unpinned = BackupShortcutPins(context).pin(dbFile, prefs)
            val recreated = if (recreate) ShortcutRecreator(context).recreate(unpinned, dbFile, prefs) else emptySet()
            Log.i(TAG, "Shortcuts: ${unpinned.size} not pinned, ${recreated.size} recreated, ${countOwnShortcuts(dbFile)} of this app's in the staged layout")
            (unpinned - recreated).size
        } finally {
            scope.cancel()
        }
        val unrestored = unrestoredConverted + unrestoredOther
        if (unrestored > 0) countFile.writeText(unrestored.toString()) else countFile.delete()
    }

    /** The staged layout's shortcuts of this app, read back to check that recreated ones were saved. */
    private fun countOwnShortcuts(dbFile: File): Int {
        if (!dbFile.exists()) return 0
        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM favorites WHERE itemType = ? AND intent LIKE ?",
                arrayOf(LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT.toString(), "%package=${context.packageName};%"),
            ).toInt()
        }
    }

    private suspend fun readZip(handlers: Map<String, suspend (InputStream) -> Unit>) {
        withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")!!
            pfd.use {
                FileInputStream(it.fileDescriptor).use { inStream ->
                    ZipInputStream(inStream).use { zipIs ->
                        var entry: ZipEntry?
                        while (true) {
                            entry = zipIs.nextEntry
                            if (entry == null) break
                            handlers[entry.name]?.invoke(zipIs)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val BACKUP_VERSION = 1
        private const val PREFS_FILE_NAME = "${LauncherFiles.SHARED_PREFERENCES_KEY}.xml"
        private const val PREFS_DB_FILE_NAME = "preferences"
        private const val PREFS_DATASTORE_FILE_NAME = "preferences.preferences_pb"

        const val INFO_FILE_NAME = "info.pb"
        const val WALLPAPER_FILE_NAME = "wallpaper.png"
        const val SCREENSHOT_FILE_NAME = "screenshot.png"
        const val LAUNCHER_DB_FILE_NAME = "launcher.db"
        const val RESTORED_DB_FILE_NAME = "restored.db"
        private const val PENDING_RESTORE_DIR_NAME = "pending_restore"
        private const val PENDING_SANITIZE_FILE_NAME = "restored.db.sanitize"
        private const val ROLLBACK_DIR_NAME = "restore_rollback"
        private const val MOVED_ASIDE_FILE_NAME = "moved_aside"
        private const val RESTORE_FAILURE_FILE_NAME = "restore_failure"
        private const val UNRESTORED_SHORTCUTS_FILE_NAME = "restore_unrestored_shortcuts"
        private const val TAG = "LawnchairBackup"

        const val INCLUDE_LAYOUT_AND_SETTINGS = 1 shl 0
        const val INCLUDE_WALLPAPER = 1 shl 1

        const val MIME_TYPE = "application/zip"
        val EXTRA_MIME_TYPES = arrayOf(MIME_TYPE, "application/x-zip", "application/octet-stream")

        val contentOptions = listOf(
            INCLUDE_LAYOUT_AND_SETTINGS to R.string.backup_content_layout_and_settings,
            INCLUDE_WALLPAPER to R.string.backup_content_wallpaper,
        )

        fun generateBackupFileName(): String {
            val fileName = "Lawnchair_Backup ${SimpleDateFormat.getDateTimeInstance().format(Date())}"
            return "$fileName.lawnchairbackup"
        }

        fun getFiles(context: Context, forRestore: Boolean): Map<String, File> {
            return mapOf(
                LAUNCHER_DB_FILE_NAME to launcherDbFile(context, forRestore),
                PREFS_FILE_NAME to prefsFile(context),
                PREFS_DB_FILE_NAME to prefsDbFile(context),
                PREFS_DATASTORE_FILE_NAME to prefsDataStoreFile(context),
            )
        }

        @SuppressLint("MissingPermission")
        suspend fun create(context: Context, contents: Int, screenshotBitmap: Bitmap, fileUri: Uri) {
            val idp = LauncherAppState.getIDP(context)
            val createdAt = Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
            val colorHints = WallpaperManagerCompat.INSTANCE.get(context).wallpaperColors?.colorHints ?: 0
            val wallpaperSupportsDarkText = (colorHints and WallpaperColorsCompat.HINT_SUPPORTS_DARK_TEXT) != 0
            val info = BackupInfo.newBuilder()
                .setLawnchairVersion(BuildConfig.VERSION_CODE)
                .setBackupVersion(BACKUP_VERSION)
                .setCreatedAt(createdAt)
                .setContents(contents)
                .setGridState(DeviceGridState(idp).toProtoMessage())
                .setPreviewWidth(screenshotBitmap.width)
                .setPreviewHeight(screenshotBitmap.height)
                .setPreviewDarkText(wallpaperSupportsDarkText)
                .build()

            val pfd = context.contentResolver.openFileDescriptor(fileUri, "w")!!
            withContext(Dispatchers.IO) {
                pfd.use {
                    ZipOutputStream(FileOutputStream(pfd.fileDescriptor).buffered()).use { out ->
                        out.putNextEntry(ZipEntry(INFO_FILE_NAME))
                        info.writeTo(out)

                        if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                            val wallpaperManager = WallpaperManager.getInstance(context)
                            val wallpaperBitmap = wallpaperManager.drawable?.toBitmap()
                            if (wallpaperBitmap != null) {
                                out.putNextEntry(ZipEntry(WALLPAPER_FILE_NAME))
                                wallpaperBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                        }
                        if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                            out.putNextEntry(ZipEntry(SCREENSHOT_FILE_NAME))
                            screenshotBitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
                        }

                        getFiles(context, forRestore = false).entries.forEach {
                            if (!it.value.exists()) return@forEach
                            out.putNextEntry(ZipEntry(it.key))
                            it.value.inputStream().use { input -> input.copyTo(out) }
                        }
                    }
                }
            }
        }

        /**
         * Moves a backup staged by [restore] into place. Must run before anything in this process
         * loads the preferences, so it is called from [android.app.Application.attachBaseContext].
         *
         * The current files are moved aside first and put back if any step fails, including when
         * the process dies partway: a rollback dir left behind is rolled back on the next start.
         * A failure is recorded for [consumeRestoreFailure].
         */
        fun applyPendingRestore(context: Context) {
            val stagingDir = pendingRestoreDir(context)
            if (rollbackDir(context).exists()) {
                Log.w(TAG, "the last restore was interrupted, rolling it back")
                recordRestoreFailure(context, rollBack(context))
                stagingDir.deleteRecursively()
                return
            }
            if (!stagingDir.exists()) return
            try {
                // No marker means the extraction didn't finish, so the current state is kept.
                val infoFile = File(stagingDir, INFO_FILE_NAME)
                if (!infoFile.exists()) {
                    recordRestoreFailure(context, RestoreFailure.NOT_APPLIED)
                    return
                }
                val info = infoFile.inputStream().use { BackupInfo.parseFrom(it) }
                try {
                    moveStagedFilesIntoPlace(context, stagingDir)
                } catch (t: Throwable) {
                    Log.e(TAG, "failed to apply the restore, rolling it back", t)
                    recordRestoreFailure(context, rollBack(context))
                    return
                }

                // Not DeviceGridState.writeToPrefs: it goes through the application context,
                // which isn't set yet during attachBaseContext.
                val gridState = info.gridState
                context.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                    .edit()
                    .putString(DeviceGridState.KEY_WORKSPACE_SIZE, gridState.gridSize)
                    .putInt(DeviceGridState.KEY_HOTSEAT_COUNT, gridState.hotseatCount)
                    .putInt(DeviceGridState.KEY_DEVICE_TYPE, gridState.deviceType)
                    .commit()

                if (context.getDatabasePath(RESTORED_DB_FILE_NAME).exists()) {
                    File(context.filesDir, PENDING_SANITIZE_FILE_NAME).createNewFile()
                }
            } finally {
                stagingDir.deleteRecursively()
            }
        }

        private fun moveStagedFilesIntoPlace(context: Context, stagingDir: File) {
            val stagedFiles = getFiles(context, forRestore = true)
                .mapKeys { File(stagingDir, it.key) }
                .filterKeys { it.exists() }

            val rollbackDir = rollbackDir(context)
            rollbackDir.mkdirs()
            liveFiles(context).forEach { (name, live) ->
                if (live.exists()) check(live.renameTo(File(rollbackDir, name))) { "failed to move $name aside" }
            }
            // From here on, a rollback has to delete what was moved into place before putting the
            // current files back.
            check(File(rollbackDir, MOVED_ASIDE_FILE_NAME).createNewFile()) { "failed to mark the rollback" }

            stagedFiles.forEach { (staged, target) ->
                target.parentFile?.mkdirs()
                // The staging dir is on the same filesystem, so each file is replaced in one step
                // instead of being left partly written.
                check(staged.renameTo(target)) { "failed to move ${staged.name} into place" }
            }
            check(rollbackDir.deleteRecursively()) { "failed to delete the rollback" }
        }

        /** Puts back the files moved aside by [moveStagedFilesIntoPlace]. */
        private fun rollBack(context: Context): RestoreFailure {
            val rollbackDir = rollbackDir(context)
            val liveFiles = liveFiles(context)
            val movedAsideFile = File(rollbackDir, MOVED_ASIDE_FILE_NAME)
            if (movedAsideFile.exists()) {
                liveFiles.values.forEach { it.deleteRecursively() }
                // A retry on the next start only puts back what is left.
                movedAsideFile.delete()
            }
            var recovered = true
            liveFiles.forEach { (name, live) ->
                val aside = File(rollbackDir, name)
                if (aside.exists() && !aside.renameTo(live)) {
                    Log.e(TAG, "failed to put back $name")
                    recovered = false
                }
            }
            if (recovered) rollbackDir.deleteRecursively()
            return if (recovered) RestoreFailure.ROLLED_BACK else RestoreFailure.ROLLBACK_FAILED
        }

        /**
         * The files and dirs a restore replaces, by their name in the rollback dir. The whole
         * databases dir is included, since a restore clears it.
         */
        private fun liveFiles(context: Context): Map<String, File> {
            val databasesDir = context.getDatabasePath(LAUNCHER_DB_FILE_NAME).parentFile!!
            val otherFiles = getFiles(context, forRestore = true).values
                .filter { it.parentFile != databasesDir }
                // SharedPreferences loads a leftover backup file in place of the real one.
                .flatMap { listOf(it, File("${it.path}.bak")) }
            return mapOf(databasesDir.name to databasesDir) + otherFiles.associateBy { it.name }
        }

        private fun recordRestoreFailure(context: Context, failure: RestoreFailure) {
            File(context.filesDir, RESTORE_FAILURE_FILE_NAME).writeText(failure.name)
        }

        /**
         * Returns the failure recorded by the last [applyPendingRestore], if any, and clears it so
         * it is reported once.
         */
        fun consumeRestoreFailure(context: Context): RestoreFailure? {
            val file = File(context.filesDir, RESTORE_FAILURE_FILE_NAME)
            if (!file.exists()) return null
            val failure = RestoreFailure.entries.firstOrNull { it.name == file.readText() }
            file.delete()
            return failure ?: RestoreFailure.ROLLED_BACK
        }

        /**
         * Returns how many of the backup's shortcuts the last restore couldn't publish again, and
         * clears it so it is reported once.
         */
        fun consumeUnrestoredShortcutCount(context: Context): Int {
            val file = File(context.filesDir, UNRESTORED_SHORTCUTS_FILE_NAME)
            if (!file.exists()) return 0
            val count = file.readText().toIntOrNull() ?: 0
            file.delete()
            return count
        }

        /**
         * Returns whether a restored database is waiting for [RestoreDbTask.performRestore], and
         * clears the flag so a failing sanitize doesn't run on every start.
         */
        @JvmStatic
        fun consumePendingSanitize(context: Context): Boolean = File(context.filesDir, PENDING_SANITIZE_FILE_NAME).delete()

        private fun pendingRestoreDir(context: Context) = File(context.filesDir, PENDING_RESTORE_DIR_NAME)

        private fun rollbackDir(context: Context) = File(context.filesDir, ROLLBACK_DIR_NAME)

        private fun launcherDbFile(context: Context, forRestore: Boolean): File {
            val dbName = if (forRestore) RESTORED_DB_FILE_NAME else LauncherAppState.getIDP(context).dbFile
            return context.getDatabasePath(dbName)
        }

        private fun prefsFile(context: Context): File {
            val dir = context.cacheDir.parent
            return File(dir, "shared_prefs/$PREFS_FILE_NAME")
        }

        private fun prefsDbFile(context: Context): File {
            return context.getDatabasePath(PREFS_DB_FILE_NAME)
        }

        private fun prefsDataStoreFile(context: Context): File {
            return File(context.filesDir, "datastore/${PREFS_DATASTORE_FILE_NAME}")
        }
    }
}

/** How a restore failed on the start after it was staged, for the message shown to the user. */
enum class RestoreFailure(@StringRes val messageRes: Int) {
    /** The process died while the backup was being extracted. */
    NOT_APPLIED(R.string.backup_restore_failed_not_applied),
    ROLLED_BACK(R.string.backup_restore_failed_rolled_back),
    ROLLBACK_FAILED(R.string.backup_restore_failed_rollback_failed),
}
