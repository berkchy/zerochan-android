package net.berkchy.zerochan.core.net

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

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

        val first = chain.proceed(request)
        val body = first.peekBody(MAX_BODY).string()
        if (!JsonEnvelope.isChallenge(body)) return first

        first.close()
        val token = JsonEnvelope.challengeToken(body) ?: throw IOException("Bot check failed")
        solve(chain, token)
        return chain.proceed(request)
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
            .header("Upgrade-Insecure-Requests", null)
            .build()
        chain.proceed(probe).close()
        if (cookieJar.loadForRequest(url).none { it.name == "xbotcheck" }) {
            cookieJar.clear()
            throw IOException("Bot check cookie not issued")
        }
    }

    companion object {
        const val BASE = "https://www.zerochan.net"
        const val HOST = "www.zerochan.net"
        const val SVG_PATH = "totally-innocent-logo-image.svg"
        private const val MAX_BODY = 8L * 1024L

        val USER_AGENT: String
            get() = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}