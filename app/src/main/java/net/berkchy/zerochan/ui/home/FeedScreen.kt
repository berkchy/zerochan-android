package net.berkchy.zerochan.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LastPage
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val isLatest = viewModel.initialPath == null && search.resolvedPath == null
    val listState = rememberLazyGridState()

    // A new page always starts at the top of the grid.
    LaunchedEffect(state.page) { listState.scrollToItem(0) }

    // Warm the next page up while the reader is a few rows away from the bottom.
    LaunchedEffect(state.posts.size, state.page) {
        if (state.posts.size >= FeedState.PAGE_SIZE - 6) viewModel.prefetchNext()
    }

    Column(modifier.fillMaxSize()) {
        topContent?.invoke()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading && state.posts.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null && state.posts.isEmpty() ->
                    ErrorState(
                        message = state.error ?: "Could not reach zerochan",
                        onRetry = { viewModel.load(state.page) },
                    )

                state.posts.isEmpty() ->
                    EmptyState(search.error ?: "Nothing here yet")

                else -> PostGrid(
                    posts = state.posts,
                    onPostClick = onPostClick,
                    listState = listState,
                    header = {
                        FeedHeader(
                            title = feedTitle(viewModel, search),
                            icon = if (isLatest) Icons.Rounded.History else Icons.Rounded.Search,
                            showTrending = isLatest,
                            onTagClick = onTagClick,
                        )
                    },
                    footer = {
                        PagerFooter(
                            state = state,
                            onPrevious = viewModel::previousPage,
                            onNext = viewModel::nextPage,
                            onGoTo = viewModel::goToPage,
                        )
                    },
                )
            }
        }
    }
}

private fun feedTitle(viewModel: FeedViewModel, search: SearchState): String {
    val resolved = search.resolvedPath
    return when {
        !search.query.isBlank() -> search.query
        !resolved.isNullOrBlank() -> resolved.replace("%20", " ")
        viewModel.initialPath == null -> "Latest posts"
        viewModel.initialPath.equals("popular", ignoreCase = true) -> "Popular today"
        else -> viewModel.initialPath.replace("%20", " ")
    }
}

@Composable
private fun FeedHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    showTrending: Boolean,
    onTagClick: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        if (showTrending) {
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

/** The page controls the reader asked for: where am I, and how do I move on. */
@Composable
private fun PagerFooter(
    state: FeedState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGoTo: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.prefetching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 10.dp))
        }

        Text(
            text = "Page ${state.page}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (state.posts.isEmpty()) {
                "No posts on this page"
            } else {
                "${state.rangeStart}-${state.rangeEnd} of the feed"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = { onGoTo(1) }, enabled = state.page > 1 && !state.loading) {
                Icon(Icons.Rounded.FirstPage, contentDescription = "First page")
            }
            OutlinedButton(
                onClick = onPrevious,
                enabled = state.page > 1 && !state.loading,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text("Prev")
            }
            FilledTonalButton(
                onClick = onNext,
                enabled = state.hasMore && !state.loading,
            ) {
                Text("Next")
                Spacer(Modifier.size(4.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { onGoTo(state.page + 5) },
                enabled = state.hasMore && !state.loading,
            ) {
                Icon(Icons.Rounded.LastPage, contentDescription = "Jump ahead")
            }
        }

        Spacer(Modifier.height(8.dp))
        if (!state.hasMore) {
            Text(
                text = "You reached the end of this listing.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}