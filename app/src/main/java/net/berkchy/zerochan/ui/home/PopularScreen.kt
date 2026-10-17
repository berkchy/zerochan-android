package net.berkchy.zerochan.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.berkchy.zerochan.ui.components.AsyncPhotoFit
import net.berkchy.zerochan.ui.components.ErrorState
import net.berkchy.zerochan.ui.components.GridImage
import kotlin.math.abs

/**
 * Popular is a full screen pager: one picture at a time, swipe left/right (or up/down) to walk
 * the ranking, tap to open the post. A thumbnail rail under the picture shows where you are and
 * jumps straight to any of the neighbours.
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

            // Zoom hand off from the rail: the picture that was tapped arrives a little small and
            // a little above where it settles, then grows into place. The jump itself is instant
            // on purpose - sliding the page across *and* zooming it reads as two animations racing
            // each other, and the zoom is the one that was asked for.
            val zoom = remember { Animatable(1f) }
            var zoomTarget by remember { mutableStateOf(-1) }

            fun zoomTo(index: Int) {
                pagerScope.launch {
                    zoomTarget = index
                    pagerState.scrollToPage(index)
                    zoom.snapTo(0f)
                    zoom.animateTo(1f, tween(ZOOM_MILLIS, easing = FastOutSlowInEasing))
                    zoomTarget = -1
                }
            }

            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect { viewModel.onPageChanged(it) }
            }

            // Black behind the system bars as well, but the pager and the rail stay inside the window
            // insets, so the rail is not buried under the navigation bar.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                Column(
                    modifier
                        .fillMaxSize()
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
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
                                    .graphicsLayer {
                                        // Read here rather than in composition: the progress only has to
                                        // invalidate the draw, not re-run this page's Coil subcompose on
                                        // every frame of the zoom.
                                        if (page == zoomTarget) {
                                            val progress = zoom.value
                                            val scale = ZOOM_FROM + (1f - ZOOM_FROM) * progress
                                            scaleX = scale
                                            scaleY = scale
                                            // starts above the resting spot and drops onto it
                                            translationY = (1f - progress) * -ZOOM_LIFT.toPx()
                                        }
                                    }
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

                    PopularStrip(
                        items = items,
                        currentPage = pagerState.currentPage,
                        onSelect = { index -> zoomTo(index) },
                    )
                }
            }
        }
    }
}

/**
 * Compact ranking rail under the picture. The tiles are small and tightly packed so roughly nine
 * of them sit on screen at once and the row never looks like a row of empty gaps; the pager's
 * current picture is ringed, and the rail follows the swipe on its own. Tapping a tile hands the
 * pager that picture with a zoom - see [zoomTo]'s caller above.
 */
@Composable
private fun PopularStrip(
    items: List<PopularItem>,
    currentPage: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.size < 2) return
    val listState = rememberLazyListState()

    // Follow the pager. Before the rail has been measured there is nothing to centre against, so
    // that first pass is a plain jump to the item; every swipe after that has a measured tile.
    LaunchedEffect(currentPage, items.size) {
        val tile = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentPage }
        if (tile == null) {
            listState.animateScrollToItem(currentPage)
        } else {
            val delta = (tile.offset + tile.size / 2) - (listState.viewportSize.width / 2)
            if (delta != 0) listState.animateScrollBy(delta.toFloat())
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            val shape = RoundedCornerShape(STRIP_TILE_RADIUS)
            GridImage(
                url = item.thumbnail.ifBlank { item.bestUrl },
                contentDescription = item.tag.ifBlank { "#${item.id}" },
                modifier = Modifier
                    .size(STRIP_TILE)
                    .clip(shape)
                    .then(
                        if (index == currentPage) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelect(index) },
            )
        }
    }
}

private val STRIP_TILE = 40.dp
private val STRIP_TILE_RADIUS = 6.dp

/** How far off its resting scale a rail tap starts the picture, and how far above it. */
private const val ZOOM_FROM = 0.84f
private val ZOOM_LIFT = 26.dp
private const val ZOOM_MILLIS = 300
