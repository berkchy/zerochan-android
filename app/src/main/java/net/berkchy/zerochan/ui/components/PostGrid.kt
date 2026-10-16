package net.berkchy.zerochan.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import net.berkchy.zerochan.core.model.Post

/**
 * The zerochan grid. Uniform square cells with centre cropped previews, plus a header and a
 * footer supplied by the caller (page number and the page navigation buttons).
 */
@Composable
fun PostGrid(
    posts: List<Post>,
    onPostClick: (Post) -> Unit,
    modifier: Modifier = Modifier,
    columns: GridCells = GridCells.Adaptive(minSize = 108.dp),
    listState: LazyGridState = rememberLazyGridState(),
    header: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    LazyVerticalGrid(
        columns = columns,
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "__header__", span = { GridItemSpan(maxLineSpan) }) { header() }

        items(
            count = posts.size,
            key = { index -> posts[index].id },
        ) { index ->
            PostGridItem(post = posts[index], onClick = { onPostClick(posts[index]) })
        }

        item(key = "__footer__", span = { GridItemSpan(maxLineSpan) }) { footer() }
    }
}

@Composable
fun PostGridItem(
    post: Post,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTag: Boolean = true,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        // Uniform square cells: the grid stays aligned no matter how the artwork is proportioned.
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(post.thumbnail)
                    .crossfade(150)
                    .build(),
                contentDescription = post.tag.ifBlank { "Post ${post.id}" },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            if (post.isMultiple || post.favorites > 0) {
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (post.isMultiple) {
                        Badge(post.children.size.toString(), Icons.Rounded.PhotoLibrary)
                    }
                    if (post.favorites > 0) {
                        Badge(post.favorites.toString(), Icons.Rounded.Favorite)
                    }
                }
            }
        }

        if (showTag && post.tag.isNotBlank()) {
            Text(
                text = post.tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            )
                        )
                    )
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            )
        }
    }
}