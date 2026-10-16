package net.berkchy.zerochan.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.berkchy.zerochan.core.data.ZerochanRepository
import net.berkchy.zerochan.core.net.Account
import net.berkchy.zerochan.core.net.LoginResult

data class AccountUiState(
    val account: Account? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class AccountViewModel(
    private val repository: ZerochanRepository,
) : ViewModel() {

    private val account get() = repository.account

    private val _state = MutableStateFlow(
        AccountUiState(
            account = account.storedUsername
                ?.takeIf { account.isSignedIn }
                ?.let { Account(it) },
        )
    )
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val refreshed = account.refresh()
            _state.value = _state.value.copy(account = refreshed)
        }
    }

    fun signIn(name: String, password: String) {
        if (name.isBlank() || password.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            when (val result = repository.signIn(name, password)) {
                is LoginResult.Success ->
                    _state.value = AccountUiState(account = result.account)
                is LoginResult.Failure ->
                    _state.value = _state.value.copy(busy = false, error = result.reason)
            }
        }
    }

    fun signOut() {
        repository.signOut()
        _state.value = AccountUiState()
    }

    fun clearError() {
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
    }

    class Factory(private val repository: ZerochanRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountViewModel(repository) as T
    }
}
