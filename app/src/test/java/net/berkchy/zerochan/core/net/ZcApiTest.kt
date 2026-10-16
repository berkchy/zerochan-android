package net.berkchy.zerochan.core.net

import net.berkchy.zerochan.core.model.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror is the fast path for listings, so its payload shape is pinned down here against
 * responses captured from the live deployment. The two things a listing is made of - favourite
 * counts and multi image groups - only appear because of `include=engagement`, so losing that
 * parameter would silently degrade the grid rather than break it.
 */
class ZcApiTest {

    private fun asset(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    @Test
    fun `listing decodes into posts with engagement counts`() {
        val posts = ZcApi.decodePosts(asset("zc_api_listing.json"))
        assertNotNull(posts)
        assertEquals(3, posts!!.size)

        val first = posts.first()
        assertEquals(4696311L, first.id)
        assertEquals(63, first.favorites)
        assertEquals("March 7th", first.tag)
        assertTrue(first.thumbnail.endsWith(".avif"))

        val single = posts.last()
        assertEquals(4732428L, single.id)
        assertEquals(2160, single.width)
        assertEquals(3840, single.height)
        assertEquals("https://s3.zerochan.net/240/28/48/4732428.avif", single.thumbnail)
        assertEquals("https://www.pixiv.net/en/artworks/150914745", single.source)
        assertEquals("pixiv.net", single.sourceHost)
        assertEquals("Vodyanitsa (Genshin Impact)", single.tag)
        assertTrue(single.tags.contains("Genshin Impact"))
        assertEquals("2fe8281972a9094e598ac942d7467d68", single.md5)
        assertEquals(4, single.favorites)
        assertEquals(1, single.childCount)
        assertFalse(single.isMultiple)
        assertEquals(1, single.imageCount)
    }

    @Test
    fun `multi image posts are flagged from childCount when no ids are known`() {
        val posts = ZcApi.decodePosts(asset("zc_api_listing.json"))!!
        val grouped = posts.filter { it.isMultiple }
        assertEquals(listOf(2, 3), grouped.map { it.childCount })
        assertEquals(listOf(2, 3), grouped.map { it.imageCount })
        // a listing only ever carries the size of the group, never its ids
        assertTrue(grouped.all { it.children.isEmpty() })
    }

    @Test
    fun `known group ids win over the reported size`() {
        val post = Post(id = 1, width = 1, height = 1, thumbnail = "t", childCount = 7)
        assertEquals(7, post.imageCount)
        assertTrue(post.isMultiple)

        val withIds = post.copy(children = listOf(1L, 2L))
        assertEquals(2, withIds.imageCount)
        assertTrue(withIds.isMultiple)

        val single = post.copy(childCount = 1)
        assertFalse(single.isMultiple)
    }

    @Test
    fun `items without an id or a preview are dropped instead of poisoning the grid`() {
        val body = """
            {"ok":true,"meta":{},"data":[
              {"id":0,"urls":{"thumbnail":"https://s3.zerochan.net/a.avif"}},
              {"id":5,"urls":{}},
              {"id":9,"urls":{"thumbnail":"https://s3.zerochan.net/c.avif"}}
            ]}
        """.trimIndent()
        assertEquals(listOf(9L), ZcApi.decodePosts(body)!!.map { it.id })
    }

    @Test
    fun `detail contributes the post itself to its own group`() {
        val children = ZcApi.decodeChildren(asset("zc_api_detail.json"), 4732396)
        assertNotNull(children)
        assertEquals(listOf(4732396L, 4732397L), children)
    }

    @Test
    fun `a single image post lists only itself`() {
        val body = """{"ok":true,"data":{"id":7,"children":[]}}"""
        assertEquals(listOf(7L), ZcApi.decodeChildren(body, 7L))
    }

    @Test
    fun `refused and malformed payloads decode to nothing rather than throwing`() {
        assertNull(ZcApi.decodePosts(asset("zc_api_error.json")))
        assertNull(ZcApi.decodeChildren(asset("zc_api_error.json"), 1))

        // what the site itself would have answered, and what a proxy error page looks like
        assertNull(ZcApi.decodePosts("<!doctype html><html><body>502 Bad Gateway</body></html>"))
        assertNull(ZcApi.decodePosts(""))
        assertNull(ZcApi.decodePosts("{\"ok\":true,\"data\":null}"))
    }

    @Test
    fun `an empty page is an answer, not a failure`() {
        val posts = ZcApi.decodePosts("""{"ok":true,"meta":{"count":0},"data":[]}""")
        assertNotNull(posts)
        assertTrue(posts!!.isEmpty())
    }
}
