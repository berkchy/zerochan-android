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
import androidx.compose.foundation.lazy.grid.itemKey
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest
import net.berkchy.zerochan.core.model.Post

/**
 * The zerochan masonry grid. Preview thumbnails are small so the grid fills up quickly;
 * the favourite counter sits on the image and grouped posts show how many pictures they hold.
 */
@Composable
fun PostGrid(
    posts: LazyPagingItems<Post>,
    onPostClick: (Post) -> Unit,
    modifier: Modifier = Modifier,
    columns: GridCells = GridCells.Adaptive(minSize = 108.dp),
    listState: LazyGridState = rememberLazyGridState(),
    header: (@Composable () -> Unit)? = null,
) {
    val shouldPrefetch by remember(posts.itemCount) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            posts.itemCount > 0 && last >= posts.itemCount - PREFETCH_DISTANCE
        }
    }

    LaunchedEffect(shouldPrefetch) {
        if (shouldPrefetch) posts.appendCatching()
    }

    LazyVerticalGrid(
        columns = columns,
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (header != null) {
            item(key = "__header__", span = { GridItemSpan(maxLineSpan) }) { header() }
        }

        items(
            count = posts.itemCount,
            key = posts.itemKey { it.id },
        ) { index ->
            val post = posts[index]
            if (post == null) {
                ShimmerBox(Modifier.aspectRatio(0.8f))
            } else {
                PostGridItem(post = post, onClick = { onPostClick(post) })
            }
        }

        if (posts.loadState.append is LoadState.Loading) {
            item(key = "__footer__", span = { GridItemSpan(maxLineSpan) }) { LoadingFooter() }
        }
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(post.aspectRatio.coerceIn(0.4f, 2.2f))
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

private const val PREFETCH_DISTANCE = 12