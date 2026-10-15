package net.berkchy.zerochan.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.berkchy.zerochan.core.download.DownloadState
import net.berkchy.zerochan.core.model.PostDetail
import net.berkchy.zerochan.ui.components.AsyncPhotoFit
import java.util.Locale

/**
 * Tapping a picture anywhere in the app opens this viewer: the full preview with its WxH
 * dimensions underneath and a download button in the lower right corner of the image.
 */
@Composable
fun ImageViewerDialog(
    detail: PostDetail,
    downloadState: DownloadState?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black)
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncPhotoFit(
                        url = detail.previewUrl,
                        contentDescription = detail.displayName,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Download button, inside the picture, bottom right.
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp)
                            .clickable(onClick = onDownload),
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.18f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.White)
                            Text("Download", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (downloadState is DownloadState.Running) {
                        LinearProgressIndicator(
                            progress = { downloadState.percent / 100f },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                        )
                    }
                }

                // Resolution line, right under the picture.
                Surface(color = Color(0xFF121014)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${detail.width} x ${detail.height}",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = listOfNotNull(
                                    detail.format.takeIf { it.isNotBlank() },
                                    formatBytes(detail.sizeBytes).takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                color = Color(0xFFB9B2B8),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (detail.displayName.isNotBlank()) {
                            Text(
                                text = detail.displayName,
                                color = Color(0xFFB9B2B8),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }

                // Download button, below the picture.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121014))
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 18.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clickable(onClick = onDownload),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.size(8.dp))
                            Text("Download image", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Surface(
                        modifier = Modifier.size(48.dp).clickable(onClick = onShare),
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.14f),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share", tint = Color.White)
                        }
                    }
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Surface(shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.5f)) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }
    }
}
