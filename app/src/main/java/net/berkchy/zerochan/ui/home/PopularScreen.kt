package net.berkchy.zerochan.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.berkchy.zerochan.ui.components.AsyncPhotoFit
import net.berkchy.zerochan.ui.components.ErrorState
import kotlin.math.abs

/**
 * Popular is a full screen pager: one picture at a time, swipe left/right (or up/down) to walk
 * the ranking, tap to open the post. A slim dot rail shows where you are.
 */
@Composable
fun PopularScreen(
    viewModel: PopularViewModel,
    onOpenPost: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when {
        state.loading && state.items.isEmpty() ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

        state.items.isEmpty() && state.error != null ->
            ErrorState(state.error ?: "Could not reach zerochan", viewModel::retry, modifier)

        state.items.isEmpty() ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing here yet", style = MaterialTheme.typography.bodyMedium)
            }

        else -> {
            val items = state.items
            val pagerState = rememberPagerState(
                initialPage = state.currentIndex.coerceIn(0, items.lastIndex),
                pageCount = { items.size },
            )
            val density = LocalDensity.current
            val swipeThreshold = with(density) { 56.dp.toPx() }
            val pagerScope = rememberCoroutineScope()

            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect { viewModel.onPageChanged(it) }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(pagerState.currentPage) {
                        var travelled = 0f
                        detectVerticalDragGestures(
                            onDragStart = { travelled = 0f },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                travelled += amount
                            },
                            onDragEnd = {
                                if (abs(travelled) < swipeThreshold) return@detectVerticalDragGestures
                                val current = pagerState.currentPage
                                val next = (current + if (travelled < 0) 1 else -1)
                                    .coerceIn(0, items.lastIndex)
                                if (next != current) {
                                    pagerScope.launch { pagerState.animateScrollToPage(next) }
                                }
                            },
                        )
                    }
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val item = items[page]
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPost(item.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncPhotoFit(
                            url = item.bestUrl,
                            contentDescription = item.tag.ifBlank { "Post ${item.id}" },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // counter, same as the post screen
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1}/${items.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }

                // resolution + tag under the picture
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = items.getOrNull(pagerState.currentPage)?.tag.orEmpty()
                            .ifBlank { "Popular" },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val current = items.getOrNull(pagerState.currentPage)
                    Text(
                        text = buildString {
                            if (current != null && current.width > 0) {
                                append("${current.width} x ${current.height}")
                            }
                            if (current != null && current.favorites > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("♥ ${current.favorites}")
                            }
                            if (state.loadingMore) {
                                if (isNotEmpty()) append(" · ")
                                append("loading more…")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFCFC8CE),
                    )
                }
            }
        }
    }
}
