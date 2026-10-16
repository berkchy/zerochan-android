package net.berkchy.zerochan.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import net.berkchy.zerochan.core.download.DownloadState
import net.berkchy.zerochan.core.model.PostDetail
import net.berkchy.zerochan.core.model.TypedTag
import net.berkchy.zerochan.ui.components.AsyncPhotoFit
import net.berkchy.zerochan.ui.components.ErrorState
import net.berkchy.zerochan.ui.components.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostScreen(
    state: PostUiState,
    onBack: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onRetry: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onOpenSource: () -> Unit,
    onTagClick: (String) -> Unit,
    onToast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            onToast()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.current?.tag.orEmpty().ifBlank { "Post" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "#${state.currentId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (state.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (state.isFavorite) "Remove from favorites" else "Add to favorites",
                            tint = if (state.isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            if (state.detail != null) {
                ExtendedFloatingActionButton(
                    onClick = onDownload,
                    expanded = state.downloadState != DownloadState.Queued,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                    text = {
                        Text(
                            when (state.downloadState) {
                                DownloadState.Queued -> "Queued"
                                else -> "Download"
                            }
                        )
                    },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error != null -> ErrorState(state.error, onRetry, Modifier.padding(padding))

            state.pager.isNotEmpty() -> PostContent(
                state = state,
                padding = padding,
                onPageChanged = onPageChanged,
                onOpenSource = onOpenSource,
                onTagClick = onTagClick,
            )
        }
    }
}

@Composable
private fun PostContent(
    state: PostUiState,
    padding: PaddingValues,
    onPageChanged: (Int) -> Unit,
    onOpenSource: () -> Unit,
    onTagClick: (String) -> Unit,
) {
    val pages = state.pager
    val initial = state.currentIndex
    val pagerState = rememberPagerState(initialPage = initial, pageCount = { pages.size })

    // Keep the pager aligned with the model after the neighbour strip arrives.
    LaunchedEffect(initial, pages.size) {
        if (pagerState.currentPage != initial) pagerState.scrollToPage(initial)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onPageChanged(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
    ) {
        Box(Modifier.fillMaxWidth().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                PostPreview(pages.getOrNull(page))
            }
            if (pages.size > 1) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1}/${pages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        ResolutionBar(state)

        state.detail?.takeIf { it.id == state.currentId }?.let { detail ->
            SourceRow(detail, onOpenSource)
            TagSection(detail, onTagClick)
            if (detail.uploadedBy.isNotBlank()) {
                Text(
                    text = "Uploaded by ${detail.uploadedBy}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // Thumbnail strip mirroring the pager: pictures of this post first, then related posts.
        PagerStrip(
            pages = pages,
            currentId = state.currentId,
            onSelected = onPageChanged,
        )

        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun PostPreview(item: PagerItem?) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio((item?.ratio ?: 1f).coerceIn(0.35f, 2.4f))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (item != null) {
            AsyncPhotoFit(
                url = item.bestUrl,
                contentDescription = item.tag.ifBlank { "Post ${item.id}" },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ResolutionBar(state: PostUiState) {
    val detail = state.detail?.takeIf { it.id == state.currentId }
    val width = detail?.width ?: state.current?.width ?: 0
    val height = detail?.height ?: state.current?.height ?: 0

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (width > 0 && height > 0) "$width x $height" else "loading…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (detail != null) {
                    if (detail.format.isNotBlank()) Text(detail.format, style = MaterialTheme.typography.labelMedium)
                    Text(formatBytes(detail.sizeBytes), style = MaterialTheme.typography.labelMedium)
                    if (detail.favorites > 0) {
                        Text("♥ ${detail.favorites}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(detail: PostDetail, onOpenSource: () -> Unit) {
    if (detail.source.isBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(detail.sourceHost, style = MaterialTheme.typography.labelMedium)
            Text(
                text = detail.source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalButton(onClick = onOpenSource, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Source")
        }
    }
}

@Composable
private fun PagerStrip(
    pages: List<PagerItem>,
    currentId: Long,
    onSelected: (Int) -> Unit,
) {
    if (pages.size < 2) return
    val groupEnd = pages.indexOfFirst { !it.partOfPost }.let { if (it < 0) pages.size else it }

    Column(Modifier.padding(top = 12.dp)) {
        Text(
            text = "Images in this post (${groupEnd})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(pages, key = { it.id }) { item ->
                val index = pages.indexOf(item)
                val selected = item.id == currentId
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.bestUrl)
                            .crossfade(150)
                            .build(),
                        contentDescription = item.tag.ifBlank { "#${item.id}" },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(if (selected) 76.dp else 54.dp)
                            .height(76.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onSelected(index) },
                    )
                    if (!item.partOfPost) {
                        Icon(
                            Icons.Rounded.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSection(detail: PostDetail, onTagClick: (String) -> Unit) {
    val typed = detail.typedTags.ifEmpty { detail.tags.map { TypedTag(it, "tag") } }
    if (typed.isEmpty()) return

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Tags", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        typed.groupBy { it.type }.forEach { (type, tags) ->
            Text(
                text = type.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEach { tag ->
                    AssistChip(
                        onClick = { onTagClick(tag.name) },
                        label = { Text(tag.name, style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        border = null,
                    )
                }
            }
        }
    }
}