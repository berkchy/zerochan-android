package net.berkchy.zerochan.core.net

import android.os.Build
import coil.ImageLoader
import coil.map.Mapper
import coil.request.Options

/**
 * zerochan hands out `.avif` previews, which only the platform decoder can read from
 * Android 11 onwards. The very same CDN path also serves a `.jpg` of identical dimensions,
 * so pre-Android 11 devices simply swap the extension.
 */
class AvifFallbackMapper : Mapper<String, Any> {

    override fun map(data: String, options: Options): Any? {
        if (Build.VERSION.SDK_INT >= AVIF_MIN_SDK) return null
        if (!data.contains(".avif")) return null
        if (!data.contains("zerochan.net")) return null
        return data.substringBeforeLast(".avif") + ".jpg"
    }

    private companion object {
        const val AVIF_MIN_SDK = Build.VERSION_CODES.R
    }
}

fun ImageLoader.Builder.withAvifFallback(): ImageLoader.Builder = apply {
    components { add(AvifFallbackMapper()) }
}