package net.berkchy.zerochan.ui.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.berkchy.zerochan.core.data.ZerochanRepository
import net.berkchy.zerochan.core.download.DownloadState
import net.berkchy.zerochan.core.model.Post
import net.berkchy.zerochan.core.model.PostDetail

/** One swipeable page: either a picture of this post, or a related post. */
data class PagerItem(
    val id: Long,
    val thumbnail: String,
    val preview: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val tag: String = "",
    val partOfPost: Boolean = true,
) {
    val bestUrl: String get() = preview.ifBlank { thumbnail }
    val ratio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f
}

data class PostUiState(
    val loading: Boolean = true,
    val detail: PostDetail? = null,
    val error: String? = null,
    val pager: List<PagerItem> = emptyList(),
    val currentId: Long = 0,
    val isFavorite: Boolean = false,
    val downloadState: DownloadState? = null,
    val toast: String? = null,
) {
    val currentIndex: Int get() = pager.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val current: PagerItem? get() = pager.getOrNull(currentIndex)
}

class PostViewModel(
    private val repository: ZerochanRepository,
    private val postId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(PostUiState(currentId = postId))
    val state: StateFlow<PostUiState> = _state.asStateFlow()

    private var detailJob: Job? = null

    init {
        load(postId)
    }

    fun load(id: Long) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, detail = null, currentId = id)
            runCatching { repository.detail(id) }
                .onSuccess { detail ->
                    _state.value = _state.value.copy(loading = false, detail = detail)
                    seedPager(detail)
                    observeFavorite()
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = error.message ?: "Could not load this post",
                    )
                }
        }
        loadNeighbours(id)
    }

    private fun seedPager(detail: PostDetail) {
        val self = PagerItem(
            id = detail.id,
            thumbnail = detail.medium.ifBlank { detail.small },
            preview = detail.previewUrl,
            width = detail.width,
            height = detail.height,
            tag = detail.displayName,
        )
        // keep whatever is already known about this post (e.g. a richer thumbnail from the grid)
        _state.value = _state.value.copy(pager = listOf(self))
    }

    /**
     * The images that belong to this post come first, then zerochan's "similar" strip, so a
     * single horizontal swipe walks from the post into the images related to it.
     */
    private fun loadNeighbours(id: Long) {
        viewModelScope.launch {
            val childrenDeferred = async { runCatching { repository.children(id) }.getOrDefault(listOf(id)) }
            val relatedDeferred = async { runCatching { repository.similar(id) }.getOrDefault(emptyList()) }

            val children = childrenDeferred.await()
            val related = relatedDeferred.await()

            val pages = LinkedHashMap<Long, PagerItem>()
            pages[id] = pages[id] ?: PagerItem(id = id, thumbnail = "", tag = "")
            children.forEach { child ->
                pages[child] = pages[child] ?: PagerItem(id = child, thumbnail = "", partOfPost = true)
            }
            related.forEach { post -> pages.putIfAbsent(post.id, post.toPagerItem(partOfPost = false)) }

            val ordered = pages.values.toList().sortedBy { if (it.partOfPost) 0 else 1 }
            val known = _state.value.detail
            val merged = ordered.map { item ->
                if (item.id == known?.id) {
                    item.copy(
                        thumbnail = item.thumbnail.ifBlank { known.medium },
                        preview = item.preview.ifBlank { known.previewUrl },
                        width = if (item.width > 0) item.width else known.width,
                        height = if (item.height > 0) item.height else known.height,
                        tag = item.tag.ifBlank { known.displayName },
                    )
                } else {
                    item
                }
            }
            _state.value = _state.value.copy(pager = merged, currentId = id)
        }
    }

    fun onPageChanged(index: Int) {
        val item = _state.value.pager.getOrNull(index) ?: return
        if (item.id == _state.value.currentId) return
        // Show the cached preview right away, then upgrade it with the 600px image.
        _state.value = _state.value.copy(currentId = item.id)
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            runCatching { repository.detail(item.id) }.onSuccess { detail ->
                _state.value = _state.value.copy(
                    detail = detail,
                    pager = _state.value.pager.map {
                        if (it.id == detail.id) {
                            it.copy(
                                preview = detail.previewUrl,
                                thumbnail = it.thumbnail.ifBlank { detail.medium },
                                width = detail.width,
                                height = detail.height,
                                tag = it.tag.ifBlank { detail.displayName },
                            )
                        } else {
                            it
                        }
                    },
                )
            }
        }
    }

    private fun observeFavorite() {
        viewModelScope.launch {
            repository.isFavorite(_state.value.currentId).collect { value ->
                _state.value = _state.value.copy(isFavorite = value)
            }
        }
    }

    fun toggleFavorite() {
        val current = _state.value.current ?: return
        val detail = _state.value.detail?.takeIf { it.id == current.id }
        viewModelScope.launch {
            repository.toggleFavorite(
                Post(
                    id = current.id,
                    width = current.width,
                    height = current.height,
                    thumbnail = current.thumbnail,
                    tag = current.tag.ifBlank { detail?.displayName.orEmpty() },
                )
            )
        }
    }

    /** Queues the file; progress lives in the notification and the Library downloads tab. */
    fun download() {
        val detail = _state.value.detail ?: return
        val item = _state.value.current
        viewModelScope.launch {
            runCatching {
                repository.queueDownload(
                    detail = detail,
                    thumbnail = item?.thumbnail.orEmpty(),
                    tag = item?.tag.orEmpty(),
                )
            }.onSuccess {
                _state.value = _state.value.copy(
                    downloadState = DownloadState.Queued,
                    toast = "Queued · see Library > Downloads",
                )
            }.onFailure {
                _state.value = _state.value.copy(toast = it.message ?: "Could not queue the download")
            }
        }
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = null)
    }

    fun retry() = load(_state.value.currentId.takeIf { it != 0L } ?: postId)

    private fun Post.toPagerItem(partOfPost: Boolean) = PagerItem(
        id = id,
        thumbnail = thumbnail,
        preview = "",
        width = width,
        height = height,
        tag = tag,
        partOfPost = partOfPost,
    )

    class Factory(
        private val repository: ZerochanRepository,
        private val postId: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PostViewModel(repository, postId) as T
    }
}