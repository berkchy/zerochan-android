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

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: Long,
    val postUrl: String,
    val downloadUrl: String,
    val filePath: String = "",
    val localUri: String = "",
    val fileName: String = "",
    val thumbnail: String = "",
    val tag: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val status: String = DownloadStatus.QUEUED.name,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0L,
) {
    val state: DownloadStatus
        get() = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED)

    val isFinished: Boolean
        get() = state == DownloadStatus.DONE ||
            state == DownloadStatus.FAILED ||
            state == DownloadStatus.CANCELLED

    /** 0f..1f, unknown total collapses to an indeterminate reading. */
    val fraction: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val percent: Int get() = (fraction * 100).toInt()

    val etaMillis: Long
        get() {
            val speed = bytesPerSecond
            if (speed <= 0 || totalBytes <= 0) return 0
            return ((totalBytes - downloadedBytes) * 1000L / speed).coerceAtLeast(0L)
        }
}

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownloads(entities: List<DownloadEntity>)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun download(id: Long): DownloadEntity?

    @Query("UPDATE downloads SET status = :status, downloadedBytes = :bytes WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, status: String)

    @Query("UPDATE downloads SET status = :status, downloadedBytes = :bytes, " +
        "totalBytes = :total, bytesPerSecond = :speed WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, total: Long, speed: Long, status: String)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun removeDownload(id: Long)

    @Query("DELETE FROM downloads WHERE status IN ('DONE', 'FAILED', 'CANCELLED')")
    suspend fun clearFinished(): Int

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'RUNNING') ORDER BY createdAt ASC")
    suspend fun pending(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'RUNNING'")
    fun runningCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('QUEUED', 'RUNNING')")
    fun pendingCount(): Flow<Int>
}

@Database(
    entities = [FavoriteEntity::class, DownloadEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ZerochanDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}