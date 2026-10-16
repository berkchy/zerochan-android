package net.berkchy.zerochan.core.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import net.berkchy.zerochan.core.net.AppLog
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth 2.0 Authorization Code flow with PKCE (RFC 7636) for "Sign in with Google".
 *
 * This is the only correct way for a third party Android app to run a Google sign in. The
 * interesting part is the *authorization*: Google's redirect hands the app an authorization
 * `code`, not credentials, and the code only becomes an ID token for this app once
 * zerochan lists our package name and signing certificate as an authorized client in their
 * Google Cloud project. That is a step only the site owner can take, which is why the client id
 * is configuration rather than a constant - see `Settings` in the README.
 */
class GoogleOAuth(
    private val context: Context,
    private val clientId: String,
) {
    val configured: Boolean get() = clientId.isNotBlank() && !clientId.contains("YOUR", ignoreCase = true)

    /** `net.berkchy.zerochan://oauth2redirect` - must be registered on the Google side too. */
    val redirectUri: String = "${context.packageName}://oauth2redirect"

    private val pending = Pending()

    fun authorizeUrl(state: String, nonce: String): String {
        val params = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to "openid email profile",
            "code_challenge" to pending.challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "nonce" to nonce,
        ).joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" }
        return "$AUTH_ENDPOINT?$params"
    }

    fun begin(): Pair<String, String> {
        pending.verifier = random()
        pending.challenge = sha256Base64Url(pending.verifier)
        val state = random()
        val nonce = random()
        return state to nonce
    }

    /** Returns the authorization code when [uri] is our redirect carrying a valid `state`. */
    fun consumeRedirect(uri: Uri, expectedState: String): String? {
        if (uri.scheme != context.packageName || uri.host != "oauth2redirect") return null
        if (uri.getQueryParameter("state") != expectedState) {
            AppLog.w(TAG, "oauth state mismatch, ignoring the redirect")
            return null
        }
        uri.getQueryParameter("error")?.let {
            AppLog.w(TAG, "oauth error: $it")
            return null
        }
        return uri.getQueryParameter("code")
    }

    /**
     * Exchanges the authorization code for tokens. With PKCE the code is bound to the verifier
     * we generated, so no client secret is needed.
     */
    fun exchange(code: String, state: String, nonce: String, fetcher: TokenFetcher): TokenResult {
        val body = listOf(
            "client_id" to clientId,
            "code" to code,
            "code_verifier" to pending.verifier.orEmpty(),
            "grant_type" to "authorization_code",
            "redirect_uri" to redirectUri,
        ).joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" }

        return runCatching { fetcher.post("$TOKEN_ENDPOINT", body) }
            .fold(
                onSuccess = { json ->
                    val idToken = Regex("\"id_token\"\\s*:\\s*\"([^\"]+)\"")
                        .find(json)?.groupValues?.getOrNull(1)
                    val email = Regex("\"email\"\\s*:\\s*\"([^\"]+)\"")
                        .find(json)?.groupValues?.getOrNull(1)
                    if (idToken.isNullOrBlank()) {
                        TokenResult.Failure("Google did not return an identity token")
                    } else {
                        TokenResult.Success(idToken, email.orEmpty())
                    }
                },
                onFailure = { TokenResult.Failure(it.message ?: "Token exchange failed") },
            )
    }

    fun clear() {
        pending.verifier = null
        pending.challenge = ""
    }

    fun browserIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private class Pending {
        var verifier: String? = null
        var challenge: String = ""
    }

    companion object {
        private const val TAG = "oauth"
        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        private fun random(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        private fun sha256Base64Url(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }
}

sealed interface TokenResult {
    data class Success(val idToken: String, val email: String) : TokenResult
    data class Failure(val reason: String) : TokenResult
}

fun interface TokenFetcher {
    /** Posts a form body and returns the raw json response. */
    fun post(url: String, body: String): String
}
