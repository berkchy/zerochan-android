package net.berkchy.zerochan.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import net.berkchy.zerochan.core.data.ZerochanRepository
import net.berkchy.zerochan.core.model.Post
import net.berkchy.zerochan.core.net.Feed

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val resolvedPath: String? = null,
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
)

class FeedViewModel(
    private val repository: ZerochanRepository,
    val initialPath: String? = null,
) : ViewModel() {

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val posts: Flow<PagingData<Post>> = MutableStateFlow(initialPath)
        .flatMapLatest { repository.posts(it?.let(::pathToFeed) ?: Feed.Latest) }
        .cachedIn(viewModelScope)

    private fun pathToFeed(path: String): Feed =
        if (path.equals("popular", ignoreCase = true)) Feed.Popular else Feed.Tag(path)

    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _search.value = _search.value.copy(query = query, loading = true, error = null)
            runCatching { repository.resolveSearch(query) }
                .onSuccess { path ->
                    _search.value = _search.value.copy(loading = false, resolvedPath = path)
                }
                .onFailure { error ->
                    _search.value = _search.value.copy(
                        loading = false,
                        error = error.message ?: "No results for \"$query\"",
                    )
                }
        }
    }

    fun clearSearch() {
        _search.value = SearchState()
    }

    class Factory(
        private val repository: ZerochanRepository,
        private val initialPath: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FeedViewModel(repository, initialPath) as T
    }
}