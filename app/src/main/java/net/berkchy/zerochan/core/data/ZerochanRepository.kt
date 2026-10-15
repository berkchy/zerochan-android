package net.berkchy.zerochan.core.data

import androidx.room.Room
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.berkchy.zerochan.core.download.ImageDownloader
import net.berkchy.zerochan.core.model.Post
import net.berkchy.zerochan.core.model.PostDetail
import net.berkchy.zerochan.core.net.Feed
import net.berkchy.zerochan.core.net.ZerochanClient

class ZerochanRepository(
    private val client: ZerochanClient,
    private val downloader: ImageDownloader,
    private val dao: LibraryDao,
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

    fun downloads(): Flow<List<DownloadEntity>> = dao.downloads()

    suspend fun startDownload(detail: PostDetail, onProgress: (net.berkchy.zerochan.core.download.DownloadState) -> Unit) {
        downloader.download(detail, onProgress)
    }

    companion object {
        const val PAGE_SIZE = 48

        fun buildDatabase(context: android.content.Context): ZerochanDatabase =
            Room.databaseBuilder(context, ZerochanDatabase::class.java, "zerochan.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}