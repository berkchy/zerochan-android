package net.berkchy.zerochan.ui.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.berkchy.zerochan.core.data.ZerochanRepository
import net.berkchy.zerochan.core.download.DownloadState
import net.berkchy.zerochan.core.model.Post
import net.berkchy.zerochan.core.model.PostDetail

data class PostUiState(
    val loading: Boolean = true,
    val detail: PostDetail? = null,
    val error: String? = null,
    val pagerItems: List<Long> = emptyList(),
    val pagerIndex: Int = 0,
    val related: List<Post> = emptyList(),
    val isFavorite: Boolean = false,
    val downloadState: DownloadState? = null,
    val toast: String? = null,
)

class PostViewModel(
    private val repository: ZerochanRepository,
    private val postId: Long,
    initialPagerItems: List<Long>,
) : ViewModel() {

    private val _state = MutableStateFlow(PostUiState())
    val state: StateFlow<PostUiState> = _state.asStateFlow()

    private var pagerJob: Job? = null

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                pagerItems = if (initialPagerItems.isEmpty()) listOf(postId) else initialPagerItems,
                pagerIndex = initialPagerItems.indexOf(postId).coerceAtLeast(0),
            )
        }
        load(postId)
        observeFavorite()
    }

    fun load(id: Long) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, detail = null)
            runCatching { repository.detail(id) }
                .onSuccess { detail ->
                    _state.value = _state.value.copy(loading = false, detail = detail)
                    val children = runCatching { repository.children(id) }.getOrDefault(listOf(id))
                    mergePager(children)
                    viewModelScope.launch {
                        val related = runCatching { repository.similar(id) }.getOrDefault(emptyList())
                        _state.value = _state.value.copy(related = related)
                    }
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = error.message ?: "Could not load this post",
                    )
                }
        }
    }

    /** Siblings of a grouped post are appended so the pager keeps its ordering. */
    private fun mergePager(children: List<Long>) {
        val current = _state.value.pagerItems
        val merged = LinkedHashSet<Long>()
        merged += current
        merged += children
        merged += postId
        val list = merged.toList()
        _state.value = _state.value.copy(
            pagerItems = list,
            pagerIndex = list.indexOf(_state.value.detail?.id ?: postId).coerceAtLeast(0),
        )
    }

    fun onPageChanged(index: Int) {
        val items = _state.value.pagerItems
        val id = items.getOrNull(index) ?: return
        if (id == _state.value.detail?.id) return
        _state.value = _state.value.copy(pagerIndex = index)
        pagerJob?.cancel()
        pagerJob = viewModelScope.launch {
            val cached = _state.value.pagerItems
            runCatching { repository.detail(id) }.onSuccess { detail ->
                if (cached == _state.value.pagerItems) {
                    _state.value = _state.value.copy(detail = detail, pagerIndex = index)
                }
            }
        }
    }

    fun openChild(id: Long) {
        val items = _state.value.pagerItems
        val index = items.indexOf(id)
        if (index >= 0) {
            _state.value = _state.value.copy(pagerIndex = index)
            if (items[index] != _state.value.detail?.id) load(items[index])
        } else {
            _state.value = _state.value.copy(pagerItems = items + id, pagerIndex = items.size)
            load(id)
        }
    }

    private fun observeFavorite() {
        viewModelScope.launch {
            repository.isFavorite(postId).collect { value ->
                _state.value = _state.value.copy(isFavorite = value)
            }
        }
    }

    fun toggleFavorite() {
        val detail = _state.value.detail ?: return
        viewModelScope.launch {
            repository.toggleFavorite(
                Post(
                    id = detail.id,
                    width = detail.width,
                    height = detail.height,
                    thumbnail = detail.medium,
                    tag = detail.displayName,
                )
            )
        }
    }

    fun download() {
        val detail = _state.value.detail ?: return
        pagerJob?.cancel()
        viewModelScope.launch {
            repository.startDownload(detail) { progress -> _state.value = _state.value.copy(downloadState = progress) }
            when (val result = _state.value.downloadState) {
                is DownloadState.Done -> _state.value = _state.value.copy(toast = "Saved ${result.file.name}")
                is DownloadState.Failed -> _state.value = _state.value.copy(toast = result.reason)
                else -> Unit
            }
        }
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = null, downloadState = null)
    }

    fun retry() = load(_state.value.detail?.id ?: postId)

    class Factory(
        private val repository: ZerochanRepository,
        private val postId: Long,
        private val pagerItems: List<Long>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PostViewModel(repository, postId, pagerItems) as T
    }
}