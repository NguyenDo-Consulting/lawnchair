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

package app.lawnchair.data.folder.service

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Relation
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import app.lawnchair.data.folder.FolderInfoEntity
import app.lawnchair.data.folder.FolderItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderInfoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolderItems(items: List<FolderItemEntity>)

    @Query("SELECT id FROM Folders ORDER BY rank ASC")
    suspend fun getAllFolderIds(): List<Int>

    @Query("SELECT * FROM Folders ORDER BY rank ASC")
    @Transaction
    fun getAllFoldersWithItems(): Flow<List<FolderWithItems>>

    @Query("SELECT MAX(rank) FROM Folders")
    suspend fun getMaxFolderRank(): Int?

    @Query("DELETE FROM FolderItems WHERE folderId = :folderId")
    suspend fun deleteFolderItemsByFolderId(folderId: Int)

    @Query("UPDATE Folders SET title = :title, timestamp = :timestamp WHERE id = :id")
    suspend fun updateFolderTitle(id: Int, title: String, timestamp: Long = System.currentTimeMillis())

    // NDShades: per-folder sorting
    @Query("UPDATE Folders SET title = :title, sortMode = :sortMode, timestamp = :timestamp WHERE id = :id")
    suspend fun updateFolderTitleAndSortMode(
        id: Int,
        title: String,
        sortMode: Int,
        timestamp: Long = System.currentTimeMillis(),
    )

    @Transaction
    suspend fun replaceFolderItems(folderId: Int, title: String, items: List<FolderItemEntity>) {
        updateFolderTitle(folderId, title)
        deleteFolderItemsByFolderId(folderId)
        insertFolderItems(items.map { it.copy(folderId = folderId) })
    }

    @Query(
        value = """
                UPDATE Folders
                SET hide = :hide, timestamp = :timestamp
                WHERE id = :folderId
            """,
    )
    suspend fun setFolderHidden(
        folderId: Int,
        hide: Boolean,
        timestamp: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM Folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: Int)

    @Query("UPDATE Folders SET rank = :rank WHERE id = :id")
    suspend fun updateFolderRank(id: Int, rank: Int)

    @Transaction
    suspend fun updateFolderRanks(orderedIds: List<Int>) {
        val currentIds = getAllFolderIds().toSet()
        if (orderedIds.size != currentIds.size || orderedIds.toSet() != currentIds) {
            return
        }
        orderedIds.forEachIndexed { index, id ->
            updateFolderRank(id, index)
        }
    }

    @RawQuery
    suspend fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}

data class FolderWithItems(
    @Embedded val folder: FolderInfoEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "folderId",
    )
    val items: List<FolderItemEntity>,
)
