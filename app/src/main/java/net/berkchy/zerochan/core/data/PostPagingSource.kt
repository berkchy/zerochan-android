package net.berkchy.zerochan.core.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import net.berkchy.zerochan.core.model.Post
import net.berkchy.zerochan.core.net.AppLog
import net.berkchy.zerochan.core.net.Feed
import net.berkchy.zerochan.core.net.ZerochanClient

/**
 * Mirrors zerochan's own pagination: page numbers up to 500, after which the site asks for the
 * `o=<id>` id-offset, which keeps working for arbitrarily deep history.
 */
data class PageKey(val page: Int, val offsetId: Long? = null) {
    val next: PageKey
        get() = PageKey(page + 1)

    val previous: PageKey
        get() = if (page <= 1) this else PageKey(page - 1)

    companion object {
        const val MAX_PAGE_NUMBER = 100
        val FIRST = PageKey(1)
    }
}

class PostPagingSource(
    private val client: ZerochanClient,
    private val feed: Feed,
) : PagingSource<PageKey, Post>() {

    override suspend fun load(params: LoadParams<PageKey>): LoadResult<PageKey, Post> {
        val key = params.key ?: PageKey.FIRST
        AppLog.d(TAG, "load ${feed.key} key=${key.page}/${key.offsetId}")
        return runCatching { client.loadListing(feed, key.page, key.offsetId) }
            .fold(
                onSuccess = { posts ->
                    AppLog.d(TAG, "load ${feed.key} key=${key.page} -> ${posts.size}")
                    LoadResult.Page(
                        data = posts,
                        prevKey = if (key.page == 1) null else key.previous,
                        nextKey = if (posts.isEmpty()) {
                            null
                        } else {
                            key.next.copy(
                                offsetId = posts.last().id.takeIf { key.page >= PageKey.MAX_PAGE_NUMBER }
                            )
                        },
                    )
                },
                onFailure = { error ->
                    AppLog.e(TAG, "load ${feed.key} key=${key.page} failed", error)
                    LoadResult.Error(error)
                },
            )
    }

    override fun getRefreshKey(state: PagingState<PageKey, Post>): PageKey? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        return page.prevKey ?: page.nextKey
    }

    private companion object {
        const val TAG = "paging"
    }
}