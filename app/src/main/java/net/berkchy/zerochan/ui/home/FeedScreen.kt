package net.berkchy.zerochan.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import net.berkchy.zerochan.core.model.Post
import net.berkchy.zerochan.ui.components.EmptyState
import net.berkchy.zerochan.ui.components.ErrorState
import net.berkchy.zerochan.ui.components.PostGrid

private val TRENDING = listOf(
    "Genshin Impact", "Hatsune Miku", "Honkai Star Rail", "Zenless Zone Zero",
    "Blue Archive", "Naruto", "Fate Grand Order", "Honkai Impact 3rd",
    "Arknights", "Vocaloid", "Touhou", "Original",
)

@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    onPostClick: (Post) -> Unit,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    topContent: (@Composable () -> Unit)? = null,
) {
    val posts = viewModel.posts.collectAsLazyPagingItems()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val isLatest = viewModel.initialPath == null && search.resolvedPath == null

    Column(modifier.fillMaxSize()) {
        topContent?.invoke()
        Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            posts.loadState.refresh is LoadState.Loading && posts.itemCount == 0 ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))

            posts.loadState.refresh is LoadState.Error && posts.itemCount == 0 ->
                ErrorState(
                    message = posts.loadState.refresh.error.message ?: "Could not reach zerochan",
                    onRetry = posts::retry,
                )

            posts.itemCount == 0 ->
                EmptyState(search.error ?: "Nothing here yet")

            else -> PostGrid(
                posts = posts,
                onPostClick = onPostClick,
                header = {
                    FeedHeader(
                        path = search.resolvedPath,
                        query = search.query,
                        isLatest = isLatest,
                        onTagClick = onTagClick,
                    )
                },
            )
        }
        }
    }
}

@Composable
private fun FeedHeader(
    path: String?,
    query: String,
    isLatest: Boolean,
    onTagClick: (String) -> Unit,
) {
    val title = when {
        !query.isBlank() -> query
        path == null -> "Latest posts"
        path.equals("popular", ignoreCase = true) -> "Popular today"
        else -> path.replace('+', ' ')
    }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = if (isLatest) Icons.Rounded.History else Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (isLatest) {
            LazyRow(
                contentPadding = PaddingValues(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(TRENDING, key = { it }) { tag ->
                    AssistChip(
                        onClick = { onTagClick(tag) },
                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.TrendingUp,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 2.dp).size(14.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}