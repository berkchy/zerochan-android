package net.berkchy.zerochan.core.net

import android.content.Context
import net.berkchy.zerochan.core.model.ListingDto
import net.berkchy.zerochan.core.model.Post
import net.berkchy.zerochan.core.model.PostDetail
import net.berkchy.zerochan.core.model.PostDetailDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object ZerochanApi {
    const val BASE = BotCheckInterceptor.BASE
}

/** Listing flavours exposed by the site. */
sealed interface Feed {
    /** `/?p=n` - everything, newest first. */
    data object Latest : Feed

    /** `/popular?p=n` - ranked html grid. */
    data object Popular : Feed

    /** `/<Tag>?s=id` - a character / series / artist tag page. */
    data class Tag(val path: String) : Feed

    val key: String
        get() = when (this) {
            Latest -> "latest"
            Popular -> "popular"
            is Tag -> "tag:$path"
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
        validate: (String) -> Boolean = { it.length > 200 },
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
        .header("User-Agent", BotCheckInterceptor.USER_AGENT)
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
