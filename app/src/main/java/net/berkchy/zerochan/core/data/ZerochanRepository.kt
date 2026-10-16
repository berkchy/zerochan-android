package net.berkchy.zerochan.core.data

import androidx.paging.PagingConfig
import androidx.paging.Pager
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import net.berkchy.zerochan.core.download.DownloadCenter
import net.berkchy.zerochan.core.download.QueueSnapshot
import net.berkchy.zerochan.core.download.SettingsStore
import net.berkchy.zerochan.core.model.Post
import net.berkchy.zerochan.core.model.PostDetail
import net.berkchy.zerochan.core.net.Feed
import net.berkchy.zerochan.core.net.ZerochanClient

class ZerochanRepository(
    private val client: ZerochanClient,
    private val downloader: DownloadCenter,
    private val dao: LibraryDao,
    private val settings: SettingsStore,
) {
    fun posts(feed: Feed): Flow<PagingData<Post>> = Pager(
        config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE * 2, enablePlaceholders = false),
        pagingSourceFactory = { PostPagingSource(client, feed) },
    ).flow

    suspend fun detail(id: Long): PostDetail = client.loadDetail(id)

    suspend fun similar(id: Long): List<Post> = client.loadSimilar(id)

    suspend fun children(id: Long): List<Long> = client.loadChildren(id)

    suspend fun resolveSearch(query: String): String = client.resolveSearch(query)
        ?: throw IllegalArgumentException("No results for \"$query\"")

    fun favorites(): Flow<List<FavoriteEntity>> = dao.favorites()

    fun isFavorite(id: Long): Flow<Boolean> = dao.isFavorite(id)

    suspend fun toggleFavorite(post: Post) {
        if (dao.isFavoriteBlocking(post.id)) dao.removeFavorite(post.id)
        else dao.addFavorite(
            FavoriteEntity(
                id = post.id,
                thumbnail = post.thumbnail,
                tag = post.tag,
                width = post.width,
                height = post.height,
            )
        )
    }

    // ---------------------------------------------------------------- downloads

    fun downloads(): Flow<List<DownloadEntity>> = dao.downloads()

    fun queue(): Flow<QueueSnapshot> = downloader.state

    val settingsFlow = settings.settings

    suspend fun setMaxConcurrent(value: Int) = settings.setConcurrency(value)
    suspend fun setWifiOnly(value: Boolean) = settings.setWifiOnly(value)
    suspend fun setAutoStart(value: Boolean) = settings.setAutoStart(value)
    suspend fun setNotifyOnComplete(value: Boolean) = settings.setNotifyOnComplete(value)

    suspend fun queueDownload(detail: PostDetail, thumbnail: String, tag: String) =
        downloader.enqueue(detail, thumbnail, tag)

    suspend fun cancelDownload(id: Long) = downloader.cancel(id)
    suspend fun retryDownload(id: Long) = downloader.retry(id)
    suspend fun removeDownload(id: Long) = downloader.remove(id)

    suspend fun clearFinishedDownloads(): Int = dao.clearFinished()

    companion object {
        const val PAGE_SIZE = 48
    }
}