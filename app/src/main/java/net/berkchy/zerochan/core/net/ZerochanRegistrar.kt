package net.berkchy.zerochan.core.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException

sealed interface RegisterResult {
    data object Success : RegisterResult
    data class Failure(val reason: String, val needsCaptcha: Boolean = false) : RegisterResult
}

/**
 * `POST /register` with `username`, `email`, `password`, `verify`, `register` and the
 * `g-recaptcha-response` token produced by the user ticking the checkbox.
 */
class ZerochanRegistrar(
    context: Context,
    private val client: ZerochanClient,
) {
    suspend fun register(
        username: String,
        email: String,
        password: String,
        verify: String,
        captchaToken: String,
    ): RegisterResult = withContext(Dispatchers.IO) {
        when {
            username.length < 3 -> return@withContext RegisterResult.Failure("Username is too short")
            username.contains(' ') -> return@withContext RegisterResult.Failure("No spaces in the username")
            password.length < 6 -> return@withContext RegisterResult.Failure("Password is at least 6 characters")
            password != verify -> return@withContext RegisterResult.Failure("The passwords do not match")
            !email.contains('@') || !email.contains('.') ->
                return@withContext RegisterResult.Failure("That does not look like an email address")
            captchaToken.isBlank() ->
                return@withContext RegisterResult.Failure("Tick the \"I'm not a robot\" box", needsCaptcha = true)
        }

        client.page("${ZerochanApi.BASE}/register")

        val form = FormBody.Builder()
            .add("username", username.trim())
            .add("email", email.trim())
            .add("password", password)
            .add("verify", verify)
            .add("register", "Register")
            .add("g-recaptcha-response", captchaToken)
            .build()

        val request = Request.Builder()
            .url("${ZerochanApi.BASE}/register")
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
            .getOrElse { return@withContext RegisterResult.Failure(it.message ?: "Network error") }

        val body = response.use { it.body?.string().orEmpty() }
        AppLog.d(TAG, "register POST -> ${response.code}, ${body.length} bytes")

        val message = MESSAGE.find(body)?.groupValues?.getOrNull(1)
            ?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()

        // A successful registration drops the visitor on the login page without the form.
        val stillOnForm = body.contains("name=\"verify\"")
        when {
            message != null && message.isNotBlank() ->
                RegisterResult.Failure(message, needsCaptcha = message.contains("captcha", ignoreCase = true))

            stillOnForm -> RegisterResult.Failure("zerochan rejected the registration")

            else -> {
                AppLog.d(TAG, "registration accepted")
                RegisterResult.Success
            }
        }
    }

    private companion object {
        const val TAG = "register"
        val MESSAGE = Regex(
            "class=\"[^\"]*(?:error|notice|warning|success)[^\"]*\"[^>]*>([^<]{3,200})",
            RegexOption.IGNORE_CASE,
        )
    }
}

class RegistrationException(message: String) : IOException(message)
