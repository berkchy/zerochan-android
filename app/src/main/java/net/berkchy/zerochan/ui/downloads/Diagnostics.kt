package net.berkchy.zerochan.ui.downloads

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import net.berkchy.zerochan.core.net.AppLog

/**
 * Everything the app records lands in a plain text file inside the app's own external storage
 * directory, which needs no permission and can be pulled with `adb`:
 *
 *   adb pull /sdcard/Android/data/net.berkchy.zerochan/files/logs/zerochan.log
 */
@Composable
fun DiagnosticsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val file = remember(context) { AppLog.logFile(context) }

    Column(modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.BugReport, contentDescription = null)
                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text("Log file", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = file.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { shareLog(context) }) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                    OutlinedButton(onClick = { copyPath(context, file.absolutePath) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy path")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        openFolder(context)
                    }) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open folder")
                    }
                    OutlinedButton(onClick = {
                        AppLog.clear(context)
                        AppLog.d("diag", "log cleared by the user")
                    }) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear")
                    }
                }
            }
        }
    }
}

private fun shareLog(context: Context) {
    val file = AppLog.logFile(context)
    if (!file.exists()) return
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, AppLog.read(context).takeLast(20_000))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun copyPath(context: Context, path: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("zerochan log", path))
}

private fun openFolder(context: Context) {
    val uri = runCatching {
        val dir = AppLog.logFile(context).parentFile ?: return
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dir)
    }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "resource/folder")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(intent) }.isFailure) {
        copyPath(context, AppLog.logFile(context).absolutePath)
    }
}