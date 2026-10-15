package net.berkchy.zerochan.core.net

import android.os.Build
import android.util.LruCache
import coil.ImageLoader
import coil.map.Mapper
import coil.request.Options

/**
 * zerochan hands out `.avif` previews. AVIF decoding is not available on every Android release
 * we still support, and the very same CDN path also serves a `.jpg` of identical dimensions,
 * so thumbnails are always requested as JPEG. Byte size is essentially the same (the previews
 * are only ~15-25 kB) and it works everywhere.
 */
class AvifFallbackMapper : Mapper<String, Any> {

    override fun map(data: String, options: Options): Any? {
        if (!data.endsWith(".avif")) return null
        return data.dropLast(5) + ".jpg"
    }

    private companion object {
        @Suppress("unused")
        val MIN = Build.VERSION_CODES.LOLLIPOP
    }
}

fun ImageLoader.Builder.withAvifFallback(): ImageLoader.Builder = apply {
    components { add(AvifFallbackMapper()) }
}

/** Short lived in-memory response cache so scrolling back through pages costs nothing. */
internal class ResponseCache(maxBytes: Int = 6 * 1024 * 1024) {
    private val cache = LruCache<String, String>(maxBytes)

    fun get(url: String): String? = cache.get(url)

    fun put(url: String, body: String) {
        if (body.length < 512 * 1024) cache.put(url, body)
    }

    fun clear() = cache.evictAll()
}