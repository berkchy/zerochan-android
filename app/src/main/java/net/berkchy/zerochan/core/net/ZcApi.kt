package net.berkchy.zerochan.core.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.berkchy.zerochan.core.model.Post
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Read only json mirror of zerochan, served from `zerochan-api.vercel.app`.
 *
 * The site itself is hostile to json clients: `?json` listings answer `HTTP 500` with the document
 * wrapped in a stray brace pair, they are throttled after a handful of requests, and the payload
 * carries neither favourite counts nor multi image grouping - the two things a listing is mostly
 * made of. The mirror fixes all of that and, with `include=engagement`, reads the counts off the
 * rendered grid for the whole page in one extra upstream request.
 *
 * It is used as a fast path only: the html grid stays the reference implementation and is still
 * what answers when the mirror is unreachable or has nothing for a page.
 */
object ZcApi {

    const val BASE = "https://zerochan-api.vercel.app/api"

    /** Widest page the mirror will serve, and the ceiling for its `page` parameter. */
    const val MAX_LIMIT = 250
    const val MAX_PAGE = 100

    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * One listing page, or null when the mirror cannot answer (offline, rate limited, unknown
     * shape). An empty list is a real answer: the tag exists but holds nothing for this page.
     */
    fun posts(
        client: OkHttpClient,
        feed: Feed,
        limit: Int,
        page: Int,
        offsetId: Long?,
    ): List<Post>? {
        val builder = "$BASE${listingPath(feed)}".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.coerceIn(1, MAX_LIMIT).toString())
            .addQueryParameter("include", INCLUDE_ENGAGEMENT)

        // Past the mirror's page ceiling the id cursor takes over, same as the site does. Without
        // a cursor there is nothing the mirror can answer, so the grid gets to handle the page.
        when {
            offsetId != null -> builder.setQueryParameter("offset", offsetId.toString())
            page in 1..MAX_PAGE -> builder.setQueryParameter("page", page.toString())
            else -> return null
        }
        if (feed is Feed.Tag) feed.tags().forEach { builder.addQueryParameter("tags", it) }

        val body = get(client, builder.build().toString()) ?: return null
        return decodePosts(body)
    }

    /**
     * Ids of the images that share a post, itself included. Null when the mirror has no detail for
     * it, which also covers ids zerochan no longer serves.
     */
    fun children(client: OkHttpClient, id: Long): List<Long>? =
        get(client, "$BASE/posts/$id")?.let { decodeChildren(it, id) }

    private fun listingPath(feed: Feed): String = when (feed) {
        Feed.Latest -> "/posts"
        Feed.Popular -> "/popular"
        is Feed.Tag -> "/tags"
    }

    /**
     * `/Tag+Tag` arrives as one percent encoded path segment; the mirror wants the tags as
     * separate `tags=` parameters so it can apply AND across them.
     */
    private fun Feed.Tag.tags(): List<String> =
        path.split('+', ' ')
            .map { it.replace("%20", " ") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * The mirror needs no session, so requests deliberately go out without the zerochan cookie
     * jar: a signed in user's cookies are never handed to a third party host.
     */
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private suspend fun get(client: OkHttpClient, url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", BotCheckInterceptor.USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            // Unlike zerochan the mirror reports real status codes, so failures are visible here.
            if (!response.isSuccessful) throw IOException("$url -> HTTP ${response.code}")
            response.body?.string().orEmpty().ifBlank { throw IOException("$url -> empty body") }
        }
    }.getOrNull()

    internal fun decodePosts(body: String): List<Post>? =
        decode<List<ZcPost>>(body)?.mapNotNull { it.toPost() }

    internal fun decodeChildren(body: String, id: Long): List<Long>? =
        decode<ZcPostDetail>(body)?.let { (listOf(id) + it.children).distinct() }

    /** The envelope answers `{ok:false, error:{...}}` for anything it will not serve. */
    internal inline fun <reified T> decode(body: String): T? {
        val envelope = runCatching { json.decodeFromString<ZcEnvelope<T>>(body) }.getOrNull()
        if (envelope == null || !envelope.ok) return null
        return envelope.data
    }

    private const val INCLUDE_ENGAGEMENT = "engagement"
}

// ----------------------------------------------------------------------- payloads

@Serializable
internal data class ZcEnvelope<T>(
    val ok: Boolean = false,
    val data: T? = null,
    val error: ZcError? = null,
)

@Serializable
internal data class ZcError(
    val code: String = "",
    val message: String = "",
)

@Serializable
internal data class ZcUrls(
    val thumbnail: String = "",
    val small: String = "",
    val medium: String = "",
    val large: String = "",
    val full: String = "",
)

@Serializable
internal data class ZcPost(
    val id: Long = 0,
    val primary: String = "",
    val tags: List<String> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    val source: String = "",
    val md5: String = "",
    /** Only present with `include=engagement`. */
    val fav: Int? = null,
    /** Size of the image group, only present with `include=engagement`. */
    val childCount: Int? = null,
    val urls: ZcUrls = ZcUrls(),
) {
    private val preview: String
        get() = urls.thumbnail.ifBlank { urls.medium }.ifBlank { urls.small }

    fun toPost(): Post? {
        if (id <= 0L || preview.isBlank()) return null
        return Post(
            id = id,
            width = width,
            height = height,
            thumbnail = preview,
            source = source,
            tag = primary,
            tags = tags,
            md5 = md5,
            favorites = fav ?: 0,
            childCount = childCount ?: 0,
        )
    }
}

@Serializable
internal data class ZcPostDetail(
    val id: Long = 0,
    /** Siblings only, the post itself is not part of the array. */
    val children: List<Long> = emptyList(),
)
