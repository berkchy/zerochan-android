package net.berkchy.zerochan.core.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.berkchy.zerochan.core.model.ListingDto
import net.berkchy.zerochan.core.model.Post
import net.berkchy.zerochan.core.model.PostDetail
import net.berkchy.zerochan.core.model.PostDetailDto
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
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
) {
    private val cookieJar = ZerochanCookieJar(context)

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

    // ---------------------------------------------------------------- listings

    suspend fun loadListing(feed: Feed, page: Int, offsetId: Long?): List<Post> =
        withContext(Dispatchers.IO) {
            val (jsonUrl, htmlUrl) = listingUrls(feed, page, offsetId)

            if (feed == Feed.Popular) {
                val html = get(htmlUrl) ?: throw IOException("zerochan is not reachable right now")
                return@withContext HtmlParser.parseGrid(html)
            }

            coroutineScope {
                val jsonDeferred = async { runCatching { get(jsonUrl) }.getOrNull() }
                val htmlDeferred = async { runCatching { get(htmlUrl) }.getOrNull() }

                val jsonBody = jsonDeferred.await()
                if (jsonBody == null) throw IOException("zerochan is not reachable right now")

                val enrichment = htmlDeferred.await()
                    ?.let { HtmlParser.parseListing(it) }
                    ?: HtmlParser.Enrichment()

                val dto: ListingDto = JsonEnvelope.decodeListing(jsonBody)
                    ?: throw IOException("Unexpected response from zerochan")

                dto.items.map { item ->
                    val post = item.toPost()
                    post.copy(
                        favorites = enrichment.favoritesOf(post.id) ?: post.favorites,
                        children = enrichment.childrenOf(post.id)?.let { kids ->
                            if (kids.contains(post.id)) kids else listOf(post.id) + kids
                        } ?: post.children,
                    )
                }
            }
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
        coroutineScope {
            val jsonDeferred = async { runCatching { get("$BASE/$id?json") }.getOrNull() }
            val htmlDeferred = async { runCatching { get("$BASE/$id") }.getOrNull() }

            val jsonBody = jsonDeferred.await() ?: throw IOException("Could not load post $id")
            val dto: PostDetailDto = JsonEnvelope.decode(jsonBody)
                ?: throw IOException("Unexpected response for post $id")
            val meta = htmlDeferred.await()?.let { HtmlParser.parsePostMeta(it) }
                ?: HtmlParser.PostMeta()

            PostDetail.from(dto).copy(
                favorites = meta.favorites,
                format = dto.full.substringAfterLast('.', "").uppercase()
                    .ifBlank { meta.encodingFormat },
                uploadedBy = meta.uploadedBy,
                creator = meta.creator,
                publishedAt = meta.publishedAt,
                typedTags = meta.typedTags,
            )
        }
    }

    suspend fun loadSimilar(id: Long): List<Post> = withContext(Dispatchers.IO) {
        val html = get("$BASE/similar3?id=$id&ajax") ?: return@withContext emptyList()
        HtmlParser.parseSimilar(html)
    }

    /**
     * Ids that belong to the same multi-image post. The detail page prefetches its neighbours,
     * which is where zerochan advertises the group members.
     */
    suspend fun loadChildren(id: Long): List<Long> = withContext(Dispatchers.IO) {
        val html = get("$BASE/$id?mobile=1") ?: return@withContext listOf(id)
        val prefetched = HtmlParser.parsePrefetchIds(html)
        prefetched.ifEmpty { listOf(id) }
    }

    // ---------------------------------------------------------------- search

    /**
     * `/search?q=` 301s onto the canonical tag page; the `json` flag is dropped along the way,
     * so the redirect is followed manually and the resolved path is reused for paging.
     */
    suspend fun resolveSearch(query: String): String? = withContext(Dispatchers.IO) {
        val url = "$BASE/search?q=${query.urlEncoded()}".toHttpUrl()
        val request = documentRequest(url.toString()).build()
        runCatching { noRedirectClient.newCall(request).execute() }.getOrNull()?.use { response ->
            val location = response.header("Location")
            if (location != null) {
                response.close()
                (url.resolve(location))?.encodedPath
            } else if (response.isSuccessful) {
                url.encodedPath
            } else {
                null
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun get(url: String): String? {
        val request = documentRequest(url).build()
        return runCatching { client.newCall(request).execute() }.getOrNull()?.use { response ->
            // Listings answer with HTTP 500 while still returning a perfectly valid body,
            // so the status code is ignored and only the payload shape is validated.
            response.body?.string().orEmpty().ifBlank { null }
        }
    }

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
        fun createBaseClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private fun String.urlEncoded(): String =
            URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    }
}