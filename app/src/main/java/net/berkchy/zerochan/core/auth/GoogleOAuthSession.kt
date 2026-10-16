package net.berkchy.zerochan.core.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.berkchy.zerochan.core.net.AppLog

sealed interface GoogleAuthEvent {
    data object NeedsConfiguration : GoogleAuthEvent
    data object Cancelled : GoogleAuthEvent
    data class Code(val code: String) : GoogleAuthEvent
    data class Failure(val reason: String) : GoogleAuthEvent
}

/**
 * Holds the in-flight PKCE transaction between the Custom Tab and the app. The redirect comes
 * back into [OAuthRedirectActivity], which hands the uri here.
 */
object GoogleOAuthSession {
    private val _events = MutableSharedFlow<GoogleAuthEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<GoogleAuthEvent> = _events.asSharedFlow()

    internal var oauth: GoogleOAuth? = null
    internal var state: String? = null
    internal var nonce: String? = null

    fun begin(context: Context, clientId: String): Intent? {
        val session = GoogleOAuth(context, clientId)
        if (!session.configured) {
            _events.tryEmit(GoogleAuthEvent.NeedsConfiguration)
            return null
        }
        val (newState, newNonce) = session.begin()
        oauth = session
        state = newState
        nonce = newNonce
        AppLog.d("oauth", "starting authorization (state ${newState.take(6)}…)")
        return session.browserIntent(session.authorizeUrl(newState, newNonce))
    }

    fun onRedirect(uri: Uri?) {
        val session = oauth
        val expected = state
        if (session == null || expected == null || uri == null) {
            _events.tryEmit(GoogleAuthEvent.Cancelled)
            return
        }
        val code = session.consumeRedirect(uri, expected)
        if (code.isNullOrBlank()) {
            _events.tryEmit(GoogleAuthEvent.Cancelled)
            return
        }
        AppLog.d("oauth", "got the authorization code")
        _events.tryEmit(GoogleAuthEvent.Code(code))
    }

    internal fun finish() {
        oauth?.clear()
        oauth = null
        state = null
        nonce = null
    }
}

/** Receives `net.berkchy.zerochan://oauth2redirect` and closes itself. */
class OAuthRedirectActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        GoogleOAuthSession.onRedirect(intent?.data)
        finish()
    }
}
