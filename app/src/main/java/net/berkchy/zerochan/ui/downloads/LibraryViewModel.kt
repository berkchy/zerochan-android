package net.berkchy.zerochan.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.berkchy.zerochan.core.data.DownloadEntity
import net.berkchy.zerochan.core.data.FavoriteEntity
import net.berkchy.zerochan.core.data.ZerochanRepository
import net.berkchy.zerochan.core.download.AppSettings
import net.berkchy.zerochan.core.download.QueueSnapshot

class LibraryViewModel(
    private val repository: ZerochanRepository,
) : ViewModel() {

    val favorites: StateFlow<List<FavoriteEntity>> = repository.favorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloads: StateFlow<List<DownloadEntity>> = repository.downloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val queue: StateFlow<QueueSnapshot> = repository.queue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueSnapshot())

    val settings: StateFlow<AppSettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val account: StateFlow<net.berkchy.zerochan.core.net.Account?> =
        repository.accountFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.account.current())

    fun cancelDownload(id: Long) = viewModelScope.launch { repository.cancelDownload(id) }
    fun retryDownload(id: Long) = viewModelScope.launch { repository.retryDownload(id) }
    fun removeDownload(id: Long) = viewModelScope.launch { repository.removeDownload(id) }
    fun clearFinished() = viewModelScope.launch { repository.clearFinishedDownloads() }

    fun setMaxConcurrent(value: Int) = viewModelScope.launch { repository.setMaxConcurrent(value) }
    fun setWifiOnly(value: Boolean) = viewModelScope.launch { repository.setWifiOnly(value) }
    fun setAutoStart(value: Boolean) = viewModelScope.launch { repository.setAutoStart(value) }
    fun setNotifyOnComplete(value: Boolean) = viewModelScope.launch { repository.setNotifyOnComplete(value) }

    class Factory(private val repository: ZerochanRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repository) as T
    }
}