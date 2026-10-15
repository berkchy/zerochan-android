package net.berkchy.zerochan.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val id: Long,
    val thumbnail: String,
    val tag: String,
    val width: Int,
    val height: Int,
    val savedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: Long,
    val postUrl: String,
    val filePath: String,
    val localUri: String,
    val fileName: String,
    val thumbnail: String,
    val tag: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM favorites ORDER BY savedAt DESC")
    fun favorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id)")
    fun isFavorite(id: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id)")
    suspend fun isFavoriteBlocking(id: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun removeFavorite(id: Long)

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun downloads(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(entity: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun download(id: Long): DownloadEntity?

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun removeDownload(id: Long)
}

@Database(
    entities = [FavoriteEntity::class, DownloadEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ZerochanDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}