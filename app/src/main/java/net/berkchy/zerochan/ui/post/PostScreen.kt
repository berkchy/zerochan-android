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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.snapshotFlow
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
import net.berkchy.zerochan.ui.components.formatBytes
import net.berkchy.zerochan.ui.components.ErrorState
import net.berkchy.zerochan.ui.components.PostBadge

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
    onOpenViewer: (Long) -> Unit,
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
                            text = state.detail?.displayName.orEmpty().ifBlank { "Post" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "#${state.detail?.id ?: 0}",
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            if (state.detail != null) {
                ExtendedFloatingActionButton(
                    onClick = onDownload,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                    text = { Text("Download") },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error != null -> ErrorState(state.error, onRetry, Modifier.padding(padding))

            state.detail != null -> PostContent(
                state = state,
                detail = state.detail,
                padding = padding,
                onPageChanged = onPageChanged,
                onShare = onShare,
                onOpenSource = onOpenSource,
                onTagClick = onTagClick,
                onOpenViewer = onOpenViewer,
            )
        }
    }
}

@Composable
private fun PostContent(
    state: PostUiState,
    detail: PostDetail,
    padding: PaddingValues,
    onPageChanged: (Int) -> Unit,
    onShare: () -> Unit,
    onOpenSource: () -> Unit,
    onTagClick: (String) -> Unit,
    onOpenViewer: (Long) -> Unit,
) {
    val pages = state.pagerItems.ifEmpty { listOf(detail.id) }
    val pagerState = rememberPagerState(
        initialPage = state.pagerIndex.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )

    LaunchedEffect(pagerState) {
        pagerState.snapshotFlow { it.currentPage }.collect { onPageChanged(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
    ) {
        // The picture itself. Swiping left/right walks the images of the same post and,
        // once you run past the end, the related images listed underneath.
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { _ ->
                // Tapping the picture opens the viewer with the WxH line and both download buttons.
                PostPreview(detail = detail, onTap = { onOpenViewer(detail.id) })
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

        // WxH + size + format, right under the picture.
        ResolutionBar(detail)

        SourceRow(detail, onOpenSource, onShare)

        if (state.pagerItems.size > 1) {
            RelatedStrip(
                title = "Images in this post",
                items = state.pagerItems.map { id ->
                    PostDetail(
                        id = id,
                        small = "",
                        medium = detail.medium,
                        large = detail.large,
                        full = detail.full,
                        width = detail.width,
                        height = detail.height,
                        sizeBytes = detail.sizeBytes,
                        hash = detail.hash,
                        source = detail.source,
                        primaryTag = detail.primaryTag,
                        tags = detail.tags,
                    )
                },
                selectedIndex = state.pagerIndex,
                onSelected = onPageChanged,
            )
        }

        TagSection(detail, onTagClick)

        if (state.related.isNotEmpty()) {
            Text(
                text = "Related posts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.related, key = { it.id }) { post ->
                    Column(Modifier.width(110.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(post.aspectRatio.coerceIn(0.4f, 2.2f))
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(post.thumbnail)
                                    .crossfade(150)
                                    .build(),
                                contentDescription = post.tag,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            PostBadge(post, Modifier.align(Alignment.BottomStart).padding(4.dp))
                        }
                        Text(
                            text = post.tag.ifBlank { "#${post.id}" },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (detail.uploadedBy.isNotBlank()) {
            Text(
                text = "Uploaded by ${detail.uploadedBy}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun PostPreview(detail: PostDetail, onTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(detail.aspectRatio.coerceIn(0.35f, 2.4f))
            .background(Color.Black)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        AsyncPhotoFit(
            url = detail.previewUrl,
            contentDescription = detail.displayName,
            modifier = Modifier.fillMaxSize(),
        )
        // Download affordance sits inside the picture, bottom right.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.55f),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text("Download", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
    }
}

@Composable
private fun ResolutionBar(detail: PostDetail) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${detail.width} x ${detail.height}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (detail.format.isNotBlank()) {
                    Text(detail.format, style = MaterialTheme.typography.labelMedium)
                }
                Text(formatBytes(detail.sizeBytes), style = MaterialTheme.typography.labelMedium)
                if (detail.favorites > 0) {
                    Text("♥ ${detail.favorites}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    detail: PostDetail,
    onOpenSource: () -> Unit,
    onShare: () -> Unit,
) {
    if (detail.source.isBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
private fun RelatedStrip(
    title: String,
    items: List<PostDetail>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items.size, key = { items[it].id }) { index ->
                val selected = index == selectedIndex
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(items[index].previewUrl)
                        .crossfade(150)
                        .build(),
                    contentDescription = "#${items[index].id}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(if (selected) 72.dp else 52.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onSelected(index) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSection(detail: PostDetail, onTagClick: (String) -> Unit) {
    val typed = detail.typedTags.ifEmpty {
        detail.tags.map { TypedTag(it, "tag") }
    }
    if (typed.isEmpty()) return

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Tags", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        val grouped = typed.groupBy { it.type }
        grouped.forEach { (type, tags) ->
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
