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

    /**
     * Listings are read straight from the rendered grid.
     *
     * zerochan serves `?json` only for a handful of requests per session and then quietly falls
     * back to the html page, so the json variant is not something a browsable feed can rely on.
     * The grid markup carries everything the list needs - id, preview, WxH, favourite count and
     * the multi image grouping - in a single request, which also halves the request count.
     */
    suspend fun loadListing(feed: Feed, page: Int, offsetId: Long?): List<Post> =
        withContext(Dispatchers.IO) {
            val url = listingUrl(feed, page, offsetId)
            AppLog.d(TAG, "listing ${feed.key} page=$page offset=$offsetId -> $url")

            val html = fetch(url, attempts = 3) { it.length > MIN_PAGE_BYTES }
                ?: throw NetworkException("zerochan did not answer with a listing")

            // Members-only tags (Ecchi, ...) silently serve the personalised home feed instead.
            if (HtmlParser.isHomeFeed(html)) {
                AppLog.w(TAG, "${feed.key} answered with the home feed (restricted tag?)")
                throw NetworkException(
                    "This tag is restricted. Sign in on the website to see it.",
                )
            }

            HtmlParser.parseGrid(html).also { posts ->
                AppLog.d(
                    TAG,
                    "listing ${feed.key} page=$page -> ${posts.size} posts, " +
                        "multi=${posts.count { it.isMultiple }}",
                )
            }
        }

    private fun listingUrl(feed: Feed, page: Int, offsetId: Long?): String {
        val query = buildList {
            if (offsetId != null) add("o=$offsetId") else if (page > 1) add("p=$page")
            if (feed is Feed.Tag) add("s=id")
        }
        val path = when (feed) {
            Feed.Latest -> "/"
            Feed.Popular -> "/popular"
            is Feed.Tag -> "/" + feed.path.toTagPath()
        }
        return if (query.isEmpty()) "$BASE$path" else "$BASE$path?${query.joinToString("&")}"
    }

    /** zerochan writes tag urls with `+` separators: `/Zenless+Zone+Zero`. */
    private fun String.toTagPath(): String =
        split(' ')
            .filter { it.isNotBlank() }
            .joinToString("+") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    // ---------------------------------------------------------------- details

    /**
     * `/{id}?json` is only served for some client profiles; for the rest zerochan answers with
     * the rendered page, which carries the very same metadata inside `application/ld+json`.
     * Whichever shape comes back is parsed, so a post never fails just because of that.
     */
    suspend fun loadDetail(id: Long): PostDetail = withContext(Dispatchers.IO) {
        AppLog.d(TAG, "detail $id")

        val json = fetch("$BASE/$id?json", attempts = 2) { JsonEnvelope.isJsonObject(it) }
        if (json != null) {
            JsonEnvelope.decode<PostDetailDto>(json)?.let { dto ->
                val meta = fetch("$BASE/$id", attempts = 2, required = false)
                    ?.let { HtmlParser.parsePostMeta(it) }
                    ?: HtmlParser.PostMeta()
                return@withContext PostDetail.from(dto).copy(
                    favorites = meta.favorites,
                    format = dto.full.substringAfterLast('.', "").uppercase()
                        .ifBlank { meta.encodingFormat },
                    uploadedBy = meta.uploadedBy,
                    creator = meta.creator,
                    publishedAt = meta.publishedAt,
                    typedTags = meta.typedTags,
                ).also {
                    AppLog.d(
                        TAG,
                        "detail $id <- json ${it.width}x${it.height} ${it.format} " +
                            "${it.sizeBytes}B favs=${it.favorites} tags=${it.typedTags.size}"
                    )
                }
            }
        }

        // Fallback: scrape the page itself.
        val page = fetch("$BASE/$id", attempts = 4) { HtmlParser.parsePostPage(it) != null }
        val detail = page?.let { HtmlParser.parsePostPage(it) }
        if (detail == null) throw NetworkException("Unexpected response for post $id")
        AppLog.w(
            TAG,
            "detail $id <- html fallback ${detail.width}x${detail.height} " +
                "${detail.format} ${detail.sizeBytes}B favs=${detail.favorites} " +
                "tags=${detail.typedTags.size} uploader=${detail.uploadedBy}"
        )
        detail
    }

    suspend fun loadSimilar(id: Long): List<Post> = withContext(Dispatchers.IO) {
        val ajax = fetch("$BASE/similar3?id=$id&ajax", attempts = 2, required = false)
        val parsed = ajax?.let { HtmlParser.parseSimilar(it) }.orEmpty()
        if (parsed.isNotEmpty()) return@withContext parsed.also { AppLog.d(TAG, "similar $id -> ${it.size} (ajax)") }

        // zerochan inlines the same list into the mobile post page
        val html = fetch("$BASE/$id?mobile=1", attempts = 2, required = false)
            ?: return@withContext emptyList()
        HtmlParser.parseSimilar(html).also { AppLog.d(TAG, "similar $id -> ${it.size} (mobile page)") }
    }

    /**
     * Ids that belong to the same multi-image post. The detail page prefetches its neighbours,
     * which is where zerochan advertises the group members.
     */
    suspend fun loadChildren(id: Long): List<Long> = withContext(Dispatchers.IO) {
        val html = fetch("$BASE/$id?mobile=1", attempts = 2, required = false)
            ?: return@withContext listOf(id)
        val prefetched = HtmlParser.parsePrefetchIds(html)
        (listOf(id) + prefetched).distinct().also {
            AppLog.d(TAG, "children $id -> ${it.size}")
        }
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
                when {
                    location != null -> {
                        // /search?q=raiden -> /Raiden?q=raiden
                        val path = (url.resolve(location))?.encodedPath
                        AppLog.d(TAG, "search '$query' -> $location (${path.orEmpty()})")
                        path?.takeIf { it.length > 1 }
                    }
                    response.isSuccessful && !url.encodedPath.endsWith("/search") -> {
                        AppLog.d(TAG, "search '$query' -> ${url.encodedPath}")
                        url.encodedPath
                    }
                    else -> {
                        AppLog.w(TAG, "search '$query' -> no match (${response.code})")
                        null
                    }
                }
            }
        }

        if (resolved == null) throw NoResultsException("No tag matches \"$query\"")
        // the canonical path comes back with `+` separators, the route wants percent encoding
        resolved.removePrefix("/").replace("+", " ")
    }

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
        .header("Cache-Control", "no-cache")
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
        private const val MIN_PAGE_BYTES = 4_000

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

class NoResultsException(message: String) : IOException(message)
