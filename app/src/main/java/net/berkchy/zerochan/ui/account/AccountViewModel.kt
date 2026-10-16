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
import net.berkchy.zerochan.core.net.RegisterResult

data class AccountUiState(
    val account: Account? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

data class RegisterUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
    val needsCaptcha: Boolean = false,
)

class AccountViewModel(
    private val repository: ZerochanRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AccountUiState(
            account = repository.account.current(),
        )
    )
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private val _register = MutableStateFlow(RegisterUiState())
    val register: StateFlow<RegisterUiState> = _register.asStateFlow()

    init {
        viewModelScope.launch {
            val refreshed = repository.account.refresh()
            _state.value = _state.value.copy(account = refreshed)
        }
    }

    fun signIn(name: String, password: String) {
        if (name.isBlank() || password.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            when (val result = repository.signIn(name, password)) {
                is LoginResult.Success -> _state.value = AccountUiState(account = result.account)
                is LoginResult.Failure ->
                    _state.value = _state.value.copy(busy = false, error = result.reason)
            }
        }
    }

    fun importSession(cookieHeader: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            when (val result = repository.importSession(cookieHeader)) {
                is LoginResult.Success -> _state.value = AccountUiState(account = result.account)
                is LoginResult.Failure ->
                    _state.value = _state.value.copy(busy = false, error = result.reason)
            }
        }
    }

    /** Adopts the session the WebView got from zerochan's own Google sign in. */
    fun signInWithGoogle(cookieHeader: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            when (val result = repository.signInWithGoogle(cookieHeader)) {
                is LoginResult.Success -> _state.value = AccountUiState(account = result.account)
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

    fun register(
        username: String,
        email: String,
        password: String,
        verify: String,
        captchaToken: String,
    ) {
        if (_register.value.busy) return
        viewModelScope.launch {
            _register.value = RegisterUiState(busy = true)
            when (
                val result = repository.register(username, email, password, verify, captchaToken)
            ) {
                RegisterResult.Success -> _register.value = RegisterUiState(done = true)
                is RegisterResult.Failure -> _register.value = RegisterUiState(
                    error = result.reason,
                    needsCaptcha = result.needsCaptcha,
                )
            }
        }
    }

    fun clearRegisterError() {
        if (_register.value.error != null) _register.value = _register.value.copy(error = null)
    }

    class Factory(private val repository: ZerochanRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountViewModel(repository) as T
    }
}
