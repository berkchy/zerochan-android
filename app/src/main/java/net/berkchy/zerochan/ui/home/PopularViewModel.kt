package net.berkchy.zerochan.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.berkchy.zerochan.core.data.ZerochanRepository
import net.berkchy.zerochan.core.model.PostDetail
import net.berkchy.zerochan.core.net.Feed

data class PopularItem(
    val id: Long,
    val thumbnail: String,
    val preview: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val tag: String = "",
    val favorites: Int = 0,
) {
    val bestUrl: String get() = preview.ifBlank { thumbnail }
    val ratio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f
}

data class PopularState(
    val items: List<PopularItem> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val exhausted: Boolean = false,
    val error: String? = null,
    val currentIndex: Int = 0,
) {
    val current: PopularItem? get() = items.getOrNull(currentIndex)
}

/**
 * Popular is browsed the way the post screen is: one image at a time, swiping left/right (or
 * up/down) through the ranking. There is no page system here, pages are only pulled in behind
 * the scenes so the swipe never has to wait.
 */
class PopularViewModel(
    private val repository: ZerochanRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PopularState())
    val state: StateFlow<PopularState> = _state.asStateFlow()

    private var page = 1
    private var loadJob: Job? = null
    private val detailJobs = HashMap<Long, Job>()

    init {
        loadMore()
    }

    fun loadMore() {
        if (loadJob?.isActive == true) return
        if (_state.value.exhausted && _state.value.items.isNotEmpty()) return
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = _state.value.items.isEmpty(),
                loadingMore = _state.value.items.isNotEmpty(),
                error = null,
            )
            runCatching { repository.loadPage(Feed.Popular, page, null) }
                .onSuccess { posts ->
                    val known = _state.value.items.associateBy { it.id }
                    val merged = (_state.value.items + posts.map { post ->
                        PopularItem(
                            id = post.id,
                            thumbnail = post.thumbnail,
                            width = post.width,
                            height = post.height,
                            tag = post.tag,
                            favorites = post.favorites,
                        )
                    }).distinctBy { it.id }
                    page++
                    _state.value = _state.value.copy(
                        items = merged,
                        loading = false,
                        loadingMore = false,
                        error = null,
                        // the site stops answering with rows long before the pager runs dry
                        exhausted = posts.isEmpty() || (posts.size < PAGE_SIZE && known.isEmpty().not()),
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        loadingMore = false,
                        error = error.message ?: "Could not reach zerochan",
                    )
                }
        }
    }

    fun onPageChanged(index: Int) {
        _state.value = _state.value.copy(currentIndex = index)
        val item = _state.value.items.getOrNull(index) ?: return

        // Keep a couple of pages ahead so the swipe never stalls.
        if (index >= _state.value.items.size - PREFETCH_AHEAD) loadMore()

        // Upgrade the 240px grid preview to the 600px image once this picture settles.
        if (item.preview.isBlank() && detailJobs[item.id]?.isActive != true) {
            detailJobs[item.id] = viewModelScope.launch {
                runCatching { repository.detail(item.id) }.onSuccess { detail ->
                    _state.value = _state.value.copy(
                        items = _state.value.items.map { current ->
                            if (current.id == detail.id) current.upgrade(detail) else current
                        }
                    )
                }
            }
        }
    }

    fun retry() {
        _state.value = _state.value.copy(error = null, exhausted = false)
        loadMore()
    }

    private fun PopularItem.upgrade(detail: PostDetail) = copy(
        preview = detail.previewUrl,
        thumbnail = if (thumbnail.isBlank()) detail.medium else thumbnail,
        width = if (width > 0) width else detail.width,
        height = if (height > 0) height else detail.height,
        tag = tag.ifBlank { detail.displayName },
        favorites = if (favorites > 0) favorites else detail.favorites,
    )

    class Factory(private val repository: ZerochanRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PopularViewModel(repository) as T
    }

    private companion object {
        const val PAGE_SIZE = 21
        const val PREFETCH_AHEAD = 4
    }
}