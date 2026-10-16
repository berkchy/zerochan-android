package net.berkchy.zerochan.core.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException

/** What the app knows about the signed-in user. The password is never stored. */
data class Account(
    val username: String,
    val guestsFavorites: Boolean = true,
) {
    val initial: String get() = username.take(1).uppercase()
}

sealed interface LoginResult {
    data class Success(val account: Account) : LoginResult
    data class Failure(val reason: String) : LoginResult
}

/**
 * zerochan has no token API. Signing in is a plain `POST /login` with `ref`, `name`, `password`
 * and `login=Login`; the server binds the `PHPSESSID` cookie to the account and every later
 * request is authenticated by that cookie. So "being signed in" here means: the session cookie
 * is set and the pages we fetch come back with the logged-in chrome (`/logout` link).
 */
class ZerochanAccount(
    context: Context,
    private val client: ZerochanClient,
) {
    private val prefs = context.getSharedPreferences("zerochan_account", Context.MODE_PRIVATE)

    val storedUsername: String? get() = prefs.getString(KEY_USERNAME, null)

    val isSignedIn: Boolean
        get() = client.hasSession() && !storedUsername.isNullOrBlank()

    fun current(): Account? =
        storedUsername?.takeIf { client.hasSession() }?.let { Account(it) }

    suspend fun signIn(name: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        if (name.isBlank() || password.isBlank()) {
            return@withContext LoginResult.Failure("Enter a username and a password")
        }

        // Establishes PHPSESSID and solves the bot check before the credentials are sent, so the
        // form is never posted twice.
        client.page("${ZerochanApi.BASE}/login")

        val form = FormBody.Builder()
            .add("ref", "/")
            .add("name", name.trim())
            .add("password", password)
            .add("login", "Login")
            .build()

        val request = Request.Builder()
            .url("${ZerochanApi.BASE}/login")
            .post(form)
            .header("User-Agent", BotCheckInterceptor.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Upgrade-Insecure-Requests", "1")
            .build()

        val response = runCatching { client.documentClient().newCall(request).execute() }
            .getOrElse { return@withContext LoginResult.Failure(it.message ?: "Network error") }

        val body = response.use { it.body?.string().orEmpty() }
        AppLog.d(TAG, "login POST -> ${response.code}, ${body.length} bytes")

        // The form is still on the page when the credentials were rejected.
        if (body.contains("name=\"password\"")) {
            val message = ERROR_REGEX.find(body)?.groupValues?.getOrNull(1)
                ?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
            return@withContext LoginResult.Failure(message ?: "Invalid username or password")
        }

        when (val account = client.readAccount(body)) {
            is AccountCheck.SignedIn -> {
                prefs.edit().putString(KEY_USERNAME, account.username).apply()
                AppLog.d(TAG, "signed in as ${account.username}")
                LoginResult.Success(Account(account.username))
            }
            AccountCheck.SignedOut -> {
                client.clearSession()
                LoginResult.Failure("zerochan did not accept the sign in")
            }
        }
    }

    /**
     * Imports a session the user already established in their browser.
     *
     * Sign in on zerochan.net in any browser, copy the `Cookie:` header (or just the
     * `PHPSESSID=...` value) from devtools, and paste it here. The value goes into the same
     * cookie jar the app already uses, so every request is authenticated exactly as if the
     * login had happened in the app. The session id is never logged.
     */
    suspend fun importSession(cookieHeader: String): LoginResult = withContext(Dispatchers.IO) {
        adopt(
            cookieHeader,
            missing = "Could not find PHPSESSID in that. Copy the full Cookie header, or just " +
                "the PHPSESSID= part.",
        )
    }

    /**
     * Adopts the session the site's own Google sign in created inside the sign in WebView.
     *
     * Identical mechanics to [importSession] - the cookie goes into the same jar - but the jar
     * already holds one, so there is nothing to paste and no user-visible cookie to leak.
     */
    suspend fun adoptWebSession(cookieHeader: String): LoginResult = withContext(Dispatchers.IO) {
        adopt(cookieHeader, missing = "Google sign in did not return a zerochan session")
    }

    private suspend fun adopt(cookieHeader: String, missing: String): LoginResult {
        val phpsessid = Regex("PHPSESSID\\s*=\\s*([A-Za-z0-9_-]{8,})")
            .find(cookieHeader)?.groupValues?.getOrNull(1)
            ?: return LoginResult.Failure(missing)

        val guestId = Regex("guest_id\\s*=\\s*(\\d+)").find(cookieHeader)
            ?.groupValues?.getOrNull(1)
        val uid = Regex("(?:^|;\\s*)user_id\\s*=\\s*(\\d{1,12})").find(cookieHeader)
            ?.groupValues?.getOrNull(1)

        client.storeSessionCookies(phpsessid, guestId, uid)
        AppLog.d(TAG, "adopted a session (PHPSESSID captured, value not logged)")

        val page = client.page("${ZerochanApi.BASE}/") ?: ""
        return when (val check = client.readAccount(page)) {
            is AccountCheck.SignedIn -> {
                val username = check.username.ifBlank { storedUsername.orEmpty() }
                prefs.edit().putString(KEY_USERNAME, username.ifBlank { "member" }).apply()
                LoginResult.Success(Account(username.ifBlank { "member" }))
            }
            AccountCheck.SignedOut -> {
                client.clearSession()
                LoginResult.Failure("That session is not signed in (or has expired)")
            }
        }
    }

    fun signOut() {
        client.clearSession()
        prefs.edit().remove(KEY_USERNAME).apply()
        AppLog.d(TAG, "signed out")
    }

    /** Re-checks the stored session, e.g. after a cold start. */
    suspend fun refresh(): Account? = withContext(Dispatchers.IO) {
        val stored = storedUsername?.takeIf { it.isNotBlank() } ?: return@withContext null
        if (!client.hasSession()) return@withContext null
        val page = runCatching { client.page("${ZerochanApi.BASE}/") }.getOrNull()
            ?: return@withContext Account(stored)
        when (client.readAccount(page)) {
            is AccountCheck.SignedIn -> {
                prefs.edit().putString(KEY_USERNAME, stored).apply()
                Account(stored)
            }
            AccountCheck.SignedOut -> {
                client.clearSession()
                prefs.edit().remove(KEY_USERNAME).apply()
                null
            }
        }
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val TAG = "account"
        val ERROR_REGEX = Regex(
            "class=\"[^\"]*(?:error|notice|warning)[^\"]*\"[^>]*>([^<]{3,160})",
            RegexOption.IGNORE_CASE,
        )
    }
}

sealed interface AccountCheck {
    data class SignedIn(val username: String) : AccountCheck
    data object SignedOut : AccountCheck
}

/** zerochan renders a `/logout` link (and a `/user/<name>` profile link) only when signed in. */
fun ZerochanClient.readAccount(html: String): AccountCheck {
    if (html.isBlank()) return AccountCheck.SignedOut
    val signedIn = html.contains("href=\"/logout") ||
        html.contains("'/logout") ||
        html.contains("\"/logout\"")
    if (!signedIn) return AccountCheck.SignedOut
    val user = Regex("href=\"/user/([^\"/?]+)\"").find(html)?.groupValues?.getOrNull(1)
    return AccountCheck.SignedIn(user.orEmpty().replace("+", " "))
}

class AccountException(message: String) : IOException(message)
