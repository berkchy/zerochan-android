package net.berkchy.zerochan.ui.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import net.berkchy.zerochan.core.data.DownloadEntity
import net.berkchy.zerochan.core.data.DownloadStatus
import net.berkchy.zerochan.core.data.FavoriteEntity
import net.berkchy.zerochan.core.download.DownloadService
import net.berkchy.zerochan.ui.components.EmptyState

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onFavoriteClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Favorites") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Downloads") })
        }

        when (tab) {
            0 -> FavoritesTab(favorites, onFavoriteClick)
            1 -> DownloadsTab(
                downloads = downloads,
                runningCount = queue.running,
                queuedCount = queue.queued,
                maxConcurrent = queue.maxConcurrent,
                onOpen = onFavoriteClick,
                onCancel = viewModel::cancelDownload,
                onRetry = viewModel::retryDownload,
                onRemove = viewModel::removeDownload,
                onClearFinished = viewModel::clearFinished,
            )

        }
    }
}

@Composable
private fun FavoritesTab(favorites: List<FavoriteEntity>, onClick: (Long) -> Unit) {
    if (favorites.isEmpty()) {
        EmptyState("No favorites yet.\nTap the heart on any post.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(favorites, key = { it.id }) { item ->
            LibraryCell(item.thumbnail, item.tag, item.id) { onClick(item.id) }
        }
    }
}

@Composable
private fun DownloadsTab(
    downloads: List<DownloadEntity>,
    runningCount: Int,
    queuedCount: Int,
    maxConcurrent: Int,
    onOpen: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onClearFinished: () -> Unit,
) {
    val finished = downloads.filter { it.isFinished }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    runningCount > 0 && queuedCount > 0 ->
                        "$runningCount running · $queuedCount queued (max $maxConcurrent)"
                    runningCount > 0 -> "$runningCount running (max $maxConcurrent)"
                    queuedCount > 0 -> "$queuedCount queued (max $maxConcurrent)"
                    else -> "Idle · up to $maxConcurrent at a time"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (finished.isNotEmpty()) {
                IconButton(onClick = onClearFinished) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear finished")
                }
            }
        }

        if (downloads.isEmpty()) {
            EmptyState("No downloads yet.\nTap Download on any post.")
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(downloads, key = { it.id }) { item ->
                DownloadRow(item, onOpen, onCancel, onRetry, onRemove)
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadEntity,
    onOpen: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val status = item.state
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(item.id) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(item.thumbnail).crossfade(150).build(),
            contentDescription = item.tag,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = item.tag.ifBlank { item.fileName.ifBlank { "Post ${item.id}" } },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = statusLine(item),
                style = MaterialTheme.typography.labelSmall,
                color = when (status) {
                    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                    DownloadStatus.DONE -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            AnimatedVisibility(status == DownloadStatus.RUNNING || status == DownloadStatus.QUEUED) {
                LinearProgressIndicator(
                    progress = { if (status == DownloadStatus.RUNNING) item.fraction else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        when (status) {
            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> IconButton(onClick = { onCancel(item.id) }) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel")
            }
            DownloadStatus.FAILED -> IconButton(onClick = { onRetry(item.id) }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Retry")
            }
            else -> IconButton(onClick = { onRemove(item.id) }) {
                Icon(
                    if (status == DownloadStatus.DONE) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusLine(item: DownloadEntity): String {
    val size = if (item.totalBytes > 0) {
        "${DownloadService.humanBytes(item.downloadedBytes)} / ${DownloadService.humanBytes(item.totalBytes)}"
    } else {
        DownloadService.humanBytes(item.downloadedBytes)
    }
    return when (item.state) {
        DownloadStatus.QUEUED -> "Queued${if (item.totalBytes > 0) " · $size" else ""}"
        DownloadStatus.RUNNING -> buildString {
            append(item.percent).append('%')
            append(" · ").append(size)
            if (item.bytesPerSecond > 0) append(" · ${DownloadService.humanBytes(item.bytesPerSecond)}/s")
            item.etaMillis.takeIf { it > 0 && item.bytesPerSecond > 0 }?.let {
                append(" · ${DownloadService.humanDuration(it)} left")
            }
        }
        DownloadStatus.DONE -> "Saved · $size"
        DownloadStatus.FAILED -> item.error.ifBlank { "Failed" }
        DownloadStatus.CANCELLED -> "Cancelled"
    }
}

@Composable
private fun LibraryCell(thumbnail: String, tag: String, id: Long, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(thumbnail).crossfade(150).build(),
                contentDescription = tag,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = tag.ifBlank { "#$id" },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}
