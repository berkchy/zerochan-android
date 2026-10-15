package net.berkchy.zerochan.core.model

/** A single image entry as returned by the zerochan listing endpoints. */
data class Post(
    val id: Long,
    val width: Int,
    val height: Int,
    val thumbnail: String,
    val source: String = "",
    val tag: String = "",
    val tags: List<String> = emptyList(),
    val md5: String = "",
    val favorites: Int = 0,
    /** All image ids that belong to the same (multi image) post, including [id]. */
    val children: List<Long> = emptyList(),
) {
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f

    val isMultiple: Boolean get() = children.size > 1

    val sourceHost: String
        get() = source.toSourceHost()

    val fullUrl: String get() = "https://www.zerochan.net/$id"

    val previewUrl: String get() = if (thumbnail.endsWith(".avif")) thumbnail else thumbnail
}

fun String.toSourceHost(): String {
    if (isBlank()) return ""
    val afterScheme = substringAfter("://", this)
    val host = afterScheme.substringBefore('/').substringBefore('?')
    return host.removePrefix("www.")
}