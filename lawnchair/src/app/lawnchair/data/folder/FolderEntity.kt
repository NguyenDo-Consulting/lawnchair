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

package app.lawnchair.data.folder

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.lawnchair.folder.FolderSortMode

@Entity(tableName = "Folders")
data class FolderInfoEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val hide: Boolean = false,
    val rank: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /** NDShades: [FolderSortMode.id] */
    @ColumnInfo(defaultValue = "0") val sortMode: Int = 0,
)

@Entity(
    tableName = "FolderItems",
    foreignKeys = [
        ForeignKey(
            entity = FolderInfoEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["folderId"])],
)
data class FolderItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val folderId: Int,
    val rank: Int = 0,
    @ColumnInfo(name = "item_info") val componentKey: String?,
    val timestamp: Long = System.currentTimeMillis(),
)

@Immutable
data class FolderEntry(
    val id: Int,
    val title: String,
    val hide: Boolean = false,
    val rank: Int = 0,
    val itemComponentKeys: List<String> = emptyList(),
    val sortMode: FolderSortMode = FolderSortMode.FREE, // NDShades: per-folder sorting
)
