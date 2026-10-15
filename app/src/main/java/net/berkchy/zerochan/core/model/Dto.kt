package net.berkchy.zerochan.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Raw shape of `/{id}?json`. */
@Serializable
data class PostDetailDto(
    val id: Long,
    val small: String = "",
    val medium: String = "",
    val large: String = "",
    val full: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    val hash: String = "",
    val source: String = "",
    val primary: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class ListingDto(
    val items: List<ListingItemDto> = emptyList(),
)

@Serializable
data class ListingItemDto(
    val id: String = "",
    val width: String = "0",
    val height: String = "0",
    val md5: String = "",
    val thumbnail: String = "",
    val source: String = "",
    val tag: String = "",
    val tags: List<String> = emptyList(),
) {
    fun toPost(favorites: Int = 0, children: List<Long> = emptyList()) = Post(
        id = id.toLongOrNull() ?: 0L,
        width = width.toIntOrNull() ?: 0,
        height = height.toIntOrNull() ?: 0,
        thumbnail = thumbnail,
        source = source,
        tag = tag,
        tags = tags,
        md5 = md5,
        favorites = favorites,
        children = children,
    )
}

@Serializable
data class PostDetail(
    val id: Long,
    val small: String,
    val medium: String,
    val large: String,
    val full: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val hash: String,
    val source: String,
    val primaryTag: String,
    val tags: List<String>,
    val favorites: Int = 0,
    val format: String = "",
    val uploadedBy: String = "",
    val creator: String = "",
    val publishedAt: String = "",
    val typedTags: List<TypedTag> = emptyList(),
) {
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f

    val postUrl: String get() = "https://www.zerochan.net/$id"

    val sourceHost: String get() = source.toSourceHost()

    /** Best url to show in the viewer: prefers the 600px webp/jpg, falls back to medium thumb. */
    val previewUrl: String get() = large.ifBlank { medium }

    val displayName: String get() = primaryTag.ifBlank { tags.firstOrNull().orEmpty() }

    companion object {
        fun from(dto: PostDetailDto) = PostDetail(
            id = dto.id,
            small = dto.small,
            medium = dto.medium,
            large = dto.large,
            full = dto.full,
            width = dto.width,
            height = dto.height,
            sizeBytes = dto.size,
            hash = dto.hash,
            source = dto.source,
            primaryTag = dto.primary,
            tags = dto.tags,
            format = dto.full.substringAfterLast('.', "").uppercase().ifBlank { "" },
        )
    }
}

@Serializable
data class TypedTag(
    val name: String,
    val type: String,
) {
    val tagUrl: String get() = "https://www.zerochan.net/" + name.replace(' ', '+')
}