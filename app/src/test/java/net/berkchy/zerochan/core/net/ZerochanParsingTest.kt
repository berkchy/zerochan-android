package net.berkchy.zerochan.core.net

import net.berkchy.zerochan.core.model.ListingDto
import net.berkchy.zerochan.core.model.Post
import net.berkchy.zerochan.core.model.PostDetailDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These run against payloads captured from the live site, because the listing endpoints are the
 * weird part of it: HTTP 500 with a `{ ... }` wrapper around the json document.
 */
class ZerochanParsingTest {

    private fun asset(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    // ------------------------------------------------------------------ envelope

    @Test
    fun `listing envelope is unwrapped even though the body has trailing html`() {
        val body = asset("listing_page1.txt")
        assertTrue(JsonEnvelope.hasItems(body))
        assertFalse(JsonEnvelope.isChallenge(body))

        val dto = JsonEnvelope.decode<ListingDto>(body)
        assertNotNull(dto)
        assertEquals(48, dto!!.items.size)

        val first = dto.items.first()
        assertEquals("4732146", first.id)
        assertEquals("1644", first.width)
        assertEquals("2192", first.height)
        assertEquals("https://s3.zerochan.net/240/46/42/4732146.avif", first.thumbnail)
        assertTrue(first.source.startsWith("https://"))
        assertTrue(first.tags.isNotEmpty())
    }

    @Test
    fun `plain post json is parsed without unwrapping`() {
        val body = asset("post_detail.json")
        assertFalse(JsonEnvelope.hasItems(body))
        assertTrue(JsonEnvelope.isJsonObject(body))

        val dto = JsonEnvelope.decode<PostDetailDto>(body)
        assertNotNull(dto)
        assertEquals(4732249L, dto!!.id)
        assertEquals(1341, dto.width)
        assertEquals(2184, dto.height)
        assertEquals(2512896L, dto.size)
        assertTrue(dto.full.endsWith(".png"))
        assertEquals("https://www.pixiv.net/artworks/147247560", dto.source)
        assertEquals("Lucifer (Ao No Exorcist)", dto.primary)
    }

    @Test
    fun `truncated payloads are rejected instead of parsed into garbage`() {
        val body = asset("listing_page1.txt")
        val truncated = body.substring(0, body.length / 3)
        assertFalse(JsonEnvelope.hasItems(truncated))
        assertTrue(JsonEnvelope.decode<ListingDto>(truncated)?.items.isNullOrEmpty())
    }

    @Test
    fun `bot check challenge is detected and the token extracted`() {
        val challenge = """
            <!DOCTYPE html><html><body>
            <img src="/totally-innocent-logo-image.svg?Z_Mo3MPA" onload="location.reload()">
            </body></html>
        """.trimIndent()
        assertTrue(JsonEnvelope.isChallenge(challenge))
        assertEquals("Z_Mo3MPA", JsonEnvelope.challengeToken(challenge))
        assertFalse(JsonEnvelope.isChallenge("not a challenge"))
    }

    // ------------------------------------------------------------------ html

    @Test
    fun `favorite counts and multi image groups come from the desktop markup`() {
        val enrichment = HtmlParser.parseListing(asset("listing_page1.html"))

        // id 4732146 is rendered as `<a data-id="4732146" class="fav"><b ...>4</b></a>`
        assertEquals(4, enrichment.favoritesOf(4732146L))
        // every rendered cell carries a counter, even the zero ones
        assertEquals(46, enrichment.favorites.size)

        // 4732141 is a grouped post: data-children="4732141,4732142"
        assertEquals(listOf(4732141L, 4732142L), enrichment.childrenOf(4732141L))
        assertNull(enrichment.childrenOf(4732146L))
    }

    @Test
    fun `post page metadata is scraped from ld json and the tag list`() {
        val meta = HtmlParser.parsePostMeta(asset("post_page.html"))
        assertEquals(1, meta.favorites)
        assertEquals("png", meta.encodingFormat)
        assertEquals("RSAMV", meta.uploadedBy)
        // the desktop page encodes the tag kind as a css class, not as data-type
        assertTrue(
            "typed tags were not parsed",
            meta.typedTags.any { it.type == "character" && it.name == "Lucifer (Ao No Exorcist)" },
        )
        assertTrue(meta.typedTags.any { it.type == "mangaka" && it.name == "Pixiv ID 124800819" })
        assertTrue(meta.typedTags.any { it.type == "series" && it.name == "Ao no Exorcist" })
    }

    @Test
    fun `post page is parsed when the json variant is not served`() {
        val detail = HtmlParser.parsePostPage(asset("post_page.html"))
        assertNotNull("the desktop post page must yield a detail", detail)
        assertEquals(4732249L, detail!!.id)
        assertEquals(1341, detail.width)
        assertEquals(2184, detail.height)
        assertEquals("PNG", detail.format)
        assertTrue("contentSize should parse", detail.sizeBytes in 2_400_000..2_500_000)
        assertEquals(1, detail.favorites)
        assertEquals("https://www.pixiv.net/artworks/147247560", detail.source)
        assertTrue(detail.full.endsWith(".png"))
        assertTrue(detail.large.endsWith(".jpg"))
        assertEquals("RSAMV", detail.uploadedBy)
        assertEquals("Lucifer (Ao No Exorcist)", detail.primaryTag)
        assertTrue(detail.typedTags.any { it.type == "character" })
    }

    @Test
    fun `a page without ld+json is rejected`() {
        assertNull(HtmlParser.parsePostPage("<html><body>nope</body></html>"))
    }

    @Test
    fun `similar fragment yields posts with dimensions and favorites`() {
        val similar = HtmlParser.parseSimilar(asset("similar.html"))
        assertTrue("expected a non empty similar list", similar.isNotEmpty())
        val first = similar.first()
        assertEquals(4723362L, first.id)
        assertTrue(first.width > 0 && first.height > 0)
        assertTrue(first.thumbnail.startsWith("https://"))
    }

    @Test
    fun `grid html listing parses into full posts`() {
        // the desktop html only renders 46 of the 48 items the json listing returns
        val grid = HtmlParser.parseGrid(asset("listing_page1.html"))
        assertEquals(46, grid.size)
        assertTrue(grid.all { it.width > 0 && it.height > 0 })
        val grouped = grid.firstOrNull { it.isMultiple }
        assertNotNull("expected at least one grouped post in page 1", grouped)
        assertTrue(grouped!!.children.size > 1)
    }

    // ------------------------------------------------------------------ model

    @Test
    fun `aspect ratio is clamped by the grid but kept raw on the model`() {
        val post = Post(
            id = 1,
            width = 4000,
            height = 200,
            thumbnail = "",
        )
        assertEquals(20f, post.aspectRatio, 0.001f)
        assertFalse(post.isMultiple)
        assertEquals("https://www.zerochan.net/1", post.fullUrl)
    }

    @Test
    fun `source host is stripped of the www prefix`() {
        assertEquals("pixiv.net", "https://www.pixiv.net/en/artworks/1".toSourceHostForTest())
        assertEquals("", "".toSourceHostForTest())
    }

    private fun String.toSourceHostForTest(): String =
        Post(id = 1, width = 1, height = 1, thumbnail = "", source = this).sourceHost
}