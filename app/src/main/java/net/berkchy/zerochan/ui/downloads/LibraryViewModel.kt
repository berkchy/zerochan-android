package net.berkchy.zerochan.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import net.berkchy.zerochan.core.data.DownloadEntity
import net.berkchy.zerochan.core.data.FavoriteEntity
import net.berkchy.zerochan.core.data.ZerochanRepository

class LibraryViewModel(
    repository: ZerochanRepository,
) : ViewModel() {

    val favorites: StateFlow<List<FavoriteEntity>> = repository.favorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloads: StateFlow<List<DownloadEntity>> = repository.downloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    class Factory(private val repository: ZerochanRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repository) as T
    }
}