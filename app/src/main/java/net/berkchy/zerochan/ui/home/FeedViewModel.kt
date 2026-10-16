package net.berkchy.zerochan.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.berkchy.zerochan.core.data.ZerochanRepository
import net.berkchy.zerochan.core.model.Post
import net.berkchy.zerochan.core.net.Feed

/**
 * Explicit paging, mirroring the site: a page is 21 posts, the feed stops there and the buttons
 * at the bottom move to the next one. Past zerochan's page-500 limit the client switches to the
 * `?o=<id>` id-offset, so scrolling all the way back stays possible.
 */
data class FeedState(
    val posts: List<Post> = emptyList(),
    val page: Int = 1,
    val loading: Boolean = true,
    val prefetching: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
) {
    val rangeStart: Int get() = if (posts.isEmpty()) 0 else (page - 1) * PAGE_SIZE + 1
    val rangeEnd: Int get() = (page - 1) * PAGE_SIZE + posts.size

    companion object {
        const val PAGE_SIZE = 21
    }
}

class FeedViewModel(
    private val repository: ZerochanRepository,
    val initialPath: String? = null,
    val title: String = initialPath?.replace("%20", " ").orEmpty(),
) : ViewModel() {

    private val _state = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private var job: Job? = null
    private var prefetchJob: Job? = null

    /** Lowest id seen on the current page; becomes the `?o=` cursor for everything after it. */
    private var offsetCursor: Long? = null
    private val prefetched = HashMap<Int, List<Post>>()

    private val feed: Feed
        get() = initialPath?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
            if (path.equals("popular", ignoreCase = true)) Feed.Popular else Feed.Tag(path)
        } ?: Feed.Latest

    init {
        load(1)
    }

    fun load(page: Int) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, page = page)
            val cached = prefetched.remove(page)
            if (cached != null) {
                applyPage(page, cached)
                return@launch
            }
            runCatching { repository.loadPage(feed, page, offsetFor(page)) }
                .onSuccess { applyPage(page, it) }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = error.message ?: "Could not reach zerochan",
                    )
                }
        }
    }

    private suspend fun applyPage(page: Int, posts: List<Post>) {
        if (page == 1) offsetCursor = null
        posts.minOfOrNull { it.id }?.let { lowest ->
            if (page >= 1) offsetCursor = lowest
        }
        _state.value = _state.value.copy(
            posts = posts,
            page = page,
            loading = false,
            error = null,
            hasMore = posts.size >= FeedState.PAGE_SIZE,
        )
    }

    private fun offsetFor(page: Int): Long? =
        if (page > MAX_PAGE_NUMBER) offsetCursor else null

    fun nextPage() {
        if (_state.value.loading || !_state.value.hasMore) return
        load(_state.value.page + 1)
    }

    fun previousPage() {
        if (_state.value.loading || _state.value.page <= 1) return
        load(_state.value.page - 1)
    }

    fun goToPage(page: Int) {
        if (page < 1 || page == _state.value.page) return
        load(page)
    }

    /** Warms up the next page so the button feels instant. */
    fun prefetchNext() {
        val next = _state.value.page + 1
        if (prefetched.containsKey(next) || !_state.value.hasMore) return
        if (prefetchJob?.isActive == true) return
        prefetchJob = viewModelScope.launch {
            _state.value = _state.value.copy(prefetching = true)
            val cursor = offsetFor(next)
            runCatching { repository.loadPage(feed, next, cursor) }
                .onSuccess {
                    prefetched[next] = it
                    // remember the cursor the prefetch used, in case we navigate straight to it
                    if (next > MAX_PAGE_NUMBER) {
                        it.minOfOrNull { post -> post.id }?.let { lowest -> offsetCursor = lowest }
                    }
                }
            _state.value = _state.value.copy(prefetching = false)
        }
    }

    fun retry() = load(_state.value.page)

    class Factory(
        private val repository: ZerochanRepository,
        private val initialPath: String?,
        private val title: String = initialPath?.replace("%20", " ").orEmpty(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FeedViewModel(repository, initialPath, title) as T
    }

    private companion object {
        const val MAX_PAGE_NUMBER = 100
    }
}