package net.berkchy.zerochan.core.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Requests are funnelled through a single gate: zerochan starts truncating or challengeing
 * bursts, so document requests are spaced out and retried with backoff.
 */
class RequestGate(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var last = 0L

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val wait = minIntervalMs - (System.currentTimeMillis() - last)
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            last = System.currentTimeMillis()
        }
    }
}

class NetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * zerochan gates every document request behind a two step cookie handshake:
 *
 *  1. `GET <page>` answers `503` with a "Checking browser..." body that references
 *     `/totally-innocent-logo-image.svg?<token>`.
 *  2. Fetching that svg with `Sec-Fetch-Dest: image` returns `Set-Cookie: xbotcheck=<value>`.
 *  3. Replaying the original request with the cookie succeeds.
 *
 * The cookie is tied to the per-request token, so it has to be solved (and refreshed) live.
 */
class BotCheckInterceptor(
    private val cookieJar: ZerochanCookieJar,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST || request.url.encodedPath == SVG_PATH) {
            return chain.proceed(request)
        }

        var response = chain.proceed(request)
        var body = response.peekBody(MAX_BODY).string()

        var attempt = 0
        while (JsonEnvelope.isChallenge(body) && attempt < 2) {
            val token = JsonEnvelope.challengeToken(body) ?: break
            AppLog.d(TAG, "bot check challenge (attempt ${attempt + 1}), solving $SVG_PATH?$token")
            response.close()
            solve(chain, token)
            attempt++
            response = chain.proceed(request)
            body = response.peekBody(MAX_BODY).string()
        }

        if (JsonEnvelope.isChallenge(body)) {
            AppLog.e(TAG, "bot check could not be solved")
            response.close()
            throw NetworkException("Bot check failed")
        }
        return response
    }

    private fun solve(chain: Interceptor.Chain, token: String) {
        val url = ("$BASE/$SVG_PATH?$token").toHttpUrlOrNull() ?: throw IOException("Bad bot check url")
        val probe = chain.request().newBuilder()
            .url(url)
            .method("GET", null)
            .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            .header("Sec-Fetch-Dest", "image")
            .header("Sec-Fetch-Mode", "no-cors")
            .header("Sec-Fetch-Site", "same-origin")
            .removeHeader("Upgrade-Insecure-Requests")
            .build()
        chain.proceed(probe).close()
        if (cookieJar.loadForRequest(url).none { it.name == "xbotcheck" }) {
            cookieJar.clear()
            throw NetworkException("Bot check cookie was not issued")
        }
        AppLog.d(TAG, "bot check solved")
    }

        companion object {
        const val BASE = "https://www.zerochan.net"
        const val HOST = "www.zerochan.net"
        const val SVG_PATH = "totally-innocent-logo-image.svg"
        private const val MAX_BODY = 8L * 1024L
        private val TAG = "botcheck"

        /**
         * zerochan only emits the `?json` / `?xml` variants for what it considers a desktop
         * browser. With a mobile (or bare `okhttp`) User-Agent it quietly answers `/{id}?json`
         * with the rendered html page instead, which is why post details used to fail with
         * "unexpected response".
         */
        const val USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

