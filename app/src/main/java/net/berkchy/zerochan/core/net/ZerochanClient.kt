package net.berkchy.zerochan.core.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Requests are funnelled through a single gate: zerochan starts truncating or challengeing
 * bursts, so document requests are spaced out and retried with backoff.
 */
private class RequestGate(private val minIntervalMs: Long) {
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

    private typealias Response = okhttp3.Response

    companion object {
        const val BASE = "https://www.zerochan.net"
        const val HOST = "www.zerochan.net"
        const val SVG_PATH = "totally-innocent-logo-image.svg"
        private const val MAX_BODY = 8L * 1024L
        private val TAG = "botcheck"

        val USER_AGENT: String
            get() = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

class ZerochanClient(
    context: Context,
    baseClient: OkHttpClient = createBaseClient(),
    private val minIntervalMs: Long = 260L,
) {
    private val cookieJar = ZerochanCookieJar(context)
    private val gate = RequestGate(minIntervalMs)
    private val cache = ResponseCache()

    private val client: OkHttpClient = baseClient.newBuilder()
        .cookieJar(cookieJar)
        .addInterceptor(BotCheckInterceptor(cookieJar))
        .build()

    private val noRedirectClient: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(cookieJar)
        .addInterceptor(BotCheckInterceptor(cookieJar))
        .build()

    fun clearCache() = cache.clear()

    // ---------------------------------------------------------------- listings

    suspend fun loadListing(feed: Feed, page: Int, offsetId: Long?): List<Post> =
        withContext(Dispatchers.IO) {
            val (jsonUrl, htmlUrl) = listingUrls(feed, page, offsetId)
            AppLog.d(TAG, "listing ${feed.key} page=$page offset=$offsetId")

            if (feed == Feed.Popular) {
                val html = fetch(htmlUrl, attempts = 4) { it.length > 500 }
                    ?: throw NetworkException("zerochan is not reachable right now")
                return@withContext HtmlParser.parseGrid(html).also {
                    AppLog.d(TAG, "listing ${feed.key} page=$page -> ${it.size} posts")
                }
            }

            // Sequential on purpose: firing both at once is what triggers the throttle.
            val jsonBody = fetch(jsonUrl, attempts = 4) { JsonEnvelope.hasItems(it) }
            val dto = jsonBody?.let { JsonEnvelope.decode<ListingDto>(it) }
            if (dto == null || dto.items.isEmpty()) {
                throw NetworkException("Unexpected response from zerochan")
            }

            val enrichment = fetch(htmlUrl, attempts = 2, required = false)
                ?.let { HtmlParser.parseListing(it) }
                ?: HtmlParser.Enrichment()

            val posts = dto.items.map { item ->
                val post = item.toPost()
                post.copy(
                    favorites = enrichment.favoritesOf(post.id) ?: post.favorites,
                    children = enrichment.childrenOf(post.id)?.let { kids ->
                        if (kids.contains(post.id)) kids else listOf(post.id) + kids
                    } ?: post.children,
                )
            }
            AppLog.d(
                TAG,
                "listing ${feed.key} page=$page -> ${posts.size} posts, " +
                    "favs=${enrichment.favorites.size}, groups=${enrichment.children.size}"
            )
            posts
        }

    private fun listingUrls(feed: Feed, page: Int, offsetId: Long?): Pair<String, String> {
        val params = buildList {
            if (offsetId != null) add("o=$offsetId") else if (page > 1) add("p=$page")
            if (feed is Feed.Tag) add("s=id")
        }
        val jsonQuery = (params + "json").joinToString("&")
        val htmlQuery = params.joinToString("&")
        val path = when (feed) {
            Feed.Latest -> "/"
            Feed.Popular -> "/popular"
            is Feed.Tag -> "/${feed.path}"
        }
        val htmlUrl = if (htmlQuery.isBlank()) "$BASE$path" else "$BASE$path?$htmlQuery"
        return "$BASE$path?$jsonQuery" to htmlUrl
    }

    // ---------------------------------------------------------------- details

    suspend fun loadDetail(id: Long): PostDetail = withContext(Dispatchers.IO) {
        AppLog.d(TAG, "detail $id")
        val body = fetch("$BASE/$id?json", attempts = 4) { JsonEnvelope.isJsonObject(it) }
            ?: throw NetworkException("Unexpected response for post $id")
        val dto = JsonEnvelope.decode<PostDetailDto>(body)
            ?: throw NetworkException("Unexpected response for post $id")

        val meta = fetch("$BASE/$id", attempts = 2, required = false)
            ?.let { HtmlParser.parsePostMeta(it) }
            ?: HtmlParser.PostMeta()

        val detail = PostDetail.from(dto).copy(
            favorites = meta.favorites,
            format = dto.full.substringAfterLast('.', "").uppercase().ifBlank { meta.encodingFormat },
            uploadedBy = meta.uploadedBy,
            creator = meta.creator,
            publishedAt = meta.publishedAt,
            typedTags = meta.typedTags,
        )
        AppLog.d(
            TAG,
            "detail $id -> ${detail.width}x${detail.height} ${detail.format} " +
                "${detail.sizeBytes}B favs=${detail.favorites} tags=${detail.typedTags.size}"
        )
        detail
    }

    suspend fun loadSimilar(id: Long): List<Post> = withContext(Dispatchers.IO) {
        val html = fetch("$BASE/similar3?id=$id&ajax", attempts = 2, required = false)
            ?: return@withContext emptyList()
        HtmlParser.parseSimilar(html).also { AppLog.d(TAG, "similar $id -> ${it.size}") }
    }

    /**
     * Ids that belong to the same multi-image post. The detail page prefetches its neighbours,
     * which is where zerochan advertises the group members.
     */
    suspend fun loadChildren(id: Long): List<Long> = withContext(Dispatchers.IO) {
        val html = fetch("$BASE/$id?mobile=1", attempts = 2, required = false)
            ?: return@withContext listOf(id)
        HtmlParser.parsePrefetchIds(html).ifEmpty { listOf(id) }
    }

    // ---------------------------------------------------------------- search

    /**
     * `/search?q=` 301s onto the canonical tag page; the `json` flag is dropped along the way,
     * so the redirect is followed manually and the resolved path is reused for paging.
     */
    suspend fun resolveSearch(query: String): String? = withContext(Dispatchers.IO) {
        val url = "$BASE/search?q=${query.urlEncoded()}".toHttpUrl()
        val request = documentRequest(url.toString()).build()
        val resolved = gate.run {
            runCatching { noRedirectClient.newCall(request).execute() }.getOrNull()?.use { response ->
                val location = response.header("Location")
                if (location != null) {
                    AppLog.d(TAG, "search '$query' -> $location")
                    (url.resolve(location))?.encodedPath
                } else if (response.isSuccessful) {
                    AppLog.d(TAG, "search '$query' -> ${url.encodedPath}")
                    url.encodedPath
                } else {
                    AppLog.w(TAG, "search '$query' failed with ${response.code}")
                    null
                }
            }
        }
        resolved
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun fetch(
        url: String,
        attempts: Int,
        required: Boolean = true,
        validate: (String) -> Boolean,
    ): String? {
        cache.get(url)?.let { cached ->
            AppLog.d(TAG, "cache hit $url")
            return cached
        }

        var lastError: Throwable? = null
        for (attempt in 1..attempts) {
            val body = try {
                gate.run { execute(url) }
            } catch (e: Exception) {
                lastError = e
                AppLog.w(TAG, "GET $url failed (attempt $attempt/$attempts): ${e.message}")
                null
            }

            if (body != null) {
                if (validate(body)) {
                    cache.put(url, body)
                    return body
                }
                lastError = NetworkException("Unusable response (${body.length} bytes)")
                AppLog.w(
                    TAG,
                    "GET $url returned an unusable body on attempt $attempt/${attempts} " +
                        "(${body.length} bytes, head=${body.take(60).replace("\n", " ")})"
                )
            }

            if (attempt < attempts) delay(backoffMs(attempt))
        }

        if (required) return null
        return null
    }

    private suspend fun execute(url: String): String? {
        val request = documentRequest(url).build()
        return runCatching { client.newCall(request).execute() }.getOrNull()?.use { response ->
            // Listings answer with HTTP 500 while still returning a perfectly valid body,
            // so the status code is ignored and only the payload shape is validated.
            response.body?.string().orEmpty().ifBlank { null }
        }
    }

    private fun backoffMs(attempt: Int) = (350L * attempt * attempt).coerceAtMost(3_000L)

    private fun documentRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Site", "none")
        .header("Sec-Fetch-User", "?1")
        .header("Upgrade-Insecure-Requests", "1")
        .get()

    companion object {
        private const val TAG = "net"
        private const val BASE = "https://www.zerochan.net"

        fun createBaseClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private fun String.urlEncoded(): String =
            java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    }
}
