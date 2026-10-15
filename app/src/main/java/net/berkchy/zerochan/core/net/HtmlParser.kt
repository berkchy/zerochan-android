package net.berkchy.zerochan.core.net

import net.berkchy.zerochan.core.model.Post
import net.berkchy.zerochan.core.model.PostDetail
import net.berkchy.zerochan.core.model.TypedTag
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The listing `?json` payload carries no favorite counts and no grouping information, so those
 * are read from the desktop html rendering of the very same page.
 */
object HtmlParser {

    data class Enrichment(
        val favorites: Map<Long, Int> = emptyMap(),
        val children: Map<Long, List<Long>> = emptyMap(),
    ) {
        fun favoritesOf(id: Long): Int? = favorites[id]
        fun childrenOf(id: Long): List<Long>? = children[id]
    }

    fun parseListing(html: String): Enrichment {
        val doc = Jsoup.parse(html)
        val fav = HashMap<Long, Int>()
        val kids = HashMap<Long, List<Long>>()

        doc.select("li[data-id]").forEach { li ->
            val id = li.attr("data-id").toLongOrNull() ?: return@forEach
            li.selectFirst("a.fav > b")?.text()?.trim()?.toIntOrNull()?.let { fav[id] = it }
            val children = li.attr("data-children")
            if (children.isNotBlank()) {
                kids[id] = children.split(',').mapNotNull { it.trim().toLongOrNull() }
            }
        }
        return Enrichment(fav, kids)
    }

    /** Full grid parse for html-only listings such as `/popular`. */
    fun parseGrid(html: String): List<Post> {
        val doc = Jsoup.parse(html)
        val fav = HashMap<Long, Int>()
        doc.select("a.fav[data-id] > b").forEach { b ->
            val id = b.parent().attr("data-id").toLongOrNull() ?: return@forEach
            b.text().trim().toIntOrNull()?.let { fav[id] = it }
        }

        return doc.select("ul#thumbs2 > li, ul#thumbs > li").mapNotNull { li ->
            gridItem(li, fav)
        }
    }

    private fun gridItem(li: Element, fav: Map<Long, Int>): Post? {
        val anchor = li.selectFirst("a.thumb") ?: return null
        // desktop grid carries data-id; the mobile "similar" markup only links to /<id>
        val id = li.attr("data-id").toLongOrNull()
            ?: anchor.attr("href").substringBefore('?').trim('/').substringAfterLast('/').toLongOrNull()
            ?: return null
        val img = li.selectFirst("a.thumb > img") ?: anchor.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        if (src.isBlank()) return null
        val (w, h) = parseSize(img.attr("title"))
        val children = li.attr("data-children")
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
        val source = li.selectFirst("a[href^=https://static]")?.attr("href").orEmpty()
        return Post(
            id = id,
            width = w,
            height = h,
            thumbnail = src,
            source = source,
            tag = img.attr("alt"),
            favorites = fav[id] ?: 0,
            children = children,
        )
    }

    /** `/similar3?id=...&ajax` fragment -> lightweight post stubs for the pager strip. */
    fun parseSimilar(html: String): List<Post> {
        val doc = Jsoup.parse(html)
        // the ajax endpoint returns only the section, the mobile post page inlines it
        val scoped = doc.selectFirst("section#similar") ?: doc

        val fav = HashMap<Long, Int>()
        scoped.select("a.fav[data-id] > b").forEach { b ->
            val id = b.parent().attr("data-id").toLongOrNull() ?: return@forEach
            b.text().trim().toIntOrNull()?.let { fav[id] = it }
        }
        // mobile markup carries the counter as a sibling <span> next to the fav link
        scoped.select("p.actions > span").forEach { span ->
            val id = span.parent()?.selectFirst("a.fav")?.attr("data-id")?.toLongOrNull() ?: return@forEach
            span.text().trim().toIntOrNull()?.let { fav[id] = it }
        }

        return scoped.select("ul#thumbs2 > li, ul#thumbs > li").mapNotNull { li ->
            gridItem(li, fav)
        }
    }

    /**
     * The desktop post page marks the tag kind with a css class
     * (`<li class="character primary">`) while the mobile page uses `data-type`. Both are
     * accepted so the detail screen is populated whichever page answered.
     */
    fun parseTypedTags(doc: Document): List<TypedTag> =
        doc.select("ul#tags > li").mapNotNull { li ->
            val type = li.attr("data-type").ifBlank {
                li.attr("class")
                    .split(' ')
                    .mapNotNull { it.takeIf { token -> token in TAG_TYPES } }
                    .firstOrNull()
            } ?: return@mapNotNull null
            val name = li.selectFirst("a")?.text()?.trim().orEmpty()
            if (name.isBlank()) null else TypedTag(name, type)
        }

    private val TAG_TYPES = setOf(
        "character", "series", "outfit", "theme", "mangaka", "source", "group",
        "copyright", "artist", "tool", "medium", "setting",
    )

    fun parsePostMeta(html: String): PostMeta {
        val doc = Jsoup.parse(html)
        val ldJson = LD_JSON.find(html)?.groupValues?.getOrNull(1)

        val meta = PostMeta()
        if (ldJson != null) {
            meta.favorites = Regex("\"userInteractionCount\"\\s*:\\s*\"?(\\d+)").find(ldJson)
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            meta.publishedAt = Regex("\"datePublished\"\\s*:\\s*\"([^\"]+)\"").find(ldJson)
                ?.groupValues?.getOrNull(1).orEmpty()
            meta.creator = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(ldJson)
                ?.groupValues?.getOrNull(1).orEmpty()
            meta.encodingFormat = Regex("\"encodingFormat\"\\s*:\\s*\"([^\"]+)\"").find(ldJson)
                ?.groupValues?.getOrNull(1).orEmpty()
        }

        meta.uploadedBy = doc.selectFirst("a.user")?.text()?.trim().orEmpty()
        meta.typedTags = parseTypedTags(doc)
        meta.sourceUrl = doc.selectFirst("#source-url")?.text()?.trim().orEmpty()
        return meta
    }

    /**
     * Full post metadata out of the rendered post page.
     *
     * zerochan only emits `/{id}?json` for some client profiles and otherwise answers with
     * this page, which carries the same information inside `application/ld+json`.
     */
    fun parsePostPage(html: String): PostDetail? {
        val doc = Jsoup.parse(html)
        val ldJson = LD_JSON.find(html)?.groupValues?.getOrNull(1) ?: return null
        if (!ldJson.contains("\"@type\"") || !ldJson.contains("ImageObject")) return null

        val full = string(ldJson, "contentUrl")
        if (full.isBlank()) return null

        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty()
        val id = Regex("/(\\d+)\\?$").find(canonical)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: Regex("(\\d+)\\.(?:png|jpe?g|gif|webp|avif)\\b", RegexOption.IGNORE_CASE)
                .find(full)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return null

        val large = string(ldJson, "thumbnail")

        val width = number(ldJson, "width")
        val height = number(ldJson, "height")
        val sizeBytes = parseSizeBytes(string(ldJson, "contentSize"))

        val meta = parsePostMeta(html)
        val format = string(ldJson, "encodingFormat").ifBlank {
            full.substringAfterLast('.', "").substringBefore('?').uppercase()
        }

        return PostDetail(
            id = id,
            small = "",
            medium = large,
            large = large,
            full = full,
            width = width,
            height = height,
            sizeBytes = sizeBytes,
            hash = "",
            source = meta.sourceUrl,
            primaryTag = doc.selectFirst("a.primary")?.text()?.trim()
                ?: doc.select("h1").firstOrNull()?.text()?.trim()
                    ?.substringBefore(" by ")?.substringBeforeLast(" #").orEmpty(),
            tags = meta.typedTags.map { it.name }.ifEmpty { doc.select("ul#tags > li a").map { it.text().trim() } },
            favorites = meta.favorites,
            format = format,
            uploadedBy = meta.uploadedBy,
            creator = string(ldJson, "name").substringBefore(" by ").trim(),
            publishedAt = string(ldJson, "datePublished"),
            typedTags = meta.typedTags,
        )
    }

    private fun string(json: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.getOrNull(1).orEmpty()

    private fun number(json: String, key: String): Int =
        Regex("\"$key\"\\s*:\\s*\"?(\\d+)").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun parseSizeBytes(text: String): Long {
        if (text.isBlank()) return 0
        val value = text.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0
        val unit = text.lowercase()
        return when {
            unit.contains("kb") -> (value * 1024).toLong()
            unit.contains("mb") -> (value * 1024 * 1024).toLong()
            unit.contains("gb") -> (value * 1024 * 1024 * 1024).toLong()
            else -> value.toLong()
        }
    }

    /** `<link rel="prefetch" href="/id">` ids: prev/next inside the same tag. */
    fun parsePrefetchIds(html: String): List<Long> =
        Regex("<link rel=\"prefetch\" href=\"/(\\d+)\"")
            .findAll(html)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .toList()

    private val LD_JSON = Regex(
        "<script type=\"application/ld\\+json\">(.*?)</script>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** zerochan writes dimensions as `1920&#10005;1080`, i.e. U+2715, not U+00D7. */
    private val SIZE = Regex("(\\d+)\\s*[x\u00d7\u2715\u2716*]\\s*(\\d+)")

    private fun parseSize(title: String): Pair<Int, Int> {
        val m = SIZE.find(title) ?: return 0 to 0
        return (m.groupValues[1].toIntOrNull() ?: 0) to (m.groupValues[2].toIntOrNull() ?: 0)
    }

    data class PostMeta(
        var favorites: Int = 0,
        var publishedAt: String = "",
        var creator: String = "",
        var encodingFormat: String = "",
        var uploadedBy: String = "",
        var sourceUrl: String = "",
        var typedTags: List<TypedTag> = emptyList(),
    )
}