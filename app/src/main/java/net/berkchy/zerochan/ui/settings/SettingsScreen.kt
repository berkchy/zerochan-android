package net.berkchy.zerochan.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material3.Card
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.berkchy.zerochan.ui.downloads.DiagnosticsSection
import net.berkchy.zerochan.ui.downloads.LibraryViewModel

@Composable
fun SettingsScreen(
    viewModel: LibraryViewModel,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        AccountCard(account, onSignIn)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text("Downloads", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "How many images may download at the same time. Anything else waits " +
                        "in the queue, oldest first, and starts as soon as a slot frees up.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    (1..5).forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = settings.maxConcurrentDownloads == value,
                            onClick = { viewModel.setMaxConcurrent(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, 5),
                        ) { Text("$value") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${queue.running} running · ${queue.queued} queued",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column {
                SwitchRow(
                    title = "Wi-Fi only",
                    subtitle = "Keep the queue paused until the device is on Wi-Fi",
                    checked = settings.wifiOnly,
                    onChange = viewModel::setWifiOnly,
                )
                SwitchRow(
                    title = "Start the queue automatically",
                    subtitle = "Pick up unfinished downloads the next time the app opens",
                    checked = settings.autoStartQueue,
                    onChange = viewModel::setAutoStart,
                )
                SwitchRow(
                    title = "Notify when finished",
                    subtitle = "Post a notification for each completed download",
                    checked = settings.notifyOnComplete,
                    onChange = viewModel::setNotifyOnComplete,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text("Logs", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Android/data/net.berkchy.zerochan/files/logs",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        DiagnosticsSection(Modifier.padding(horizontal = 0.dp))
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AccountCard(account: net.berkchy.zerochan.core.net.Account?, onSignIn: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSignIn).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(account)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = account?.username ?: "Account",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = account?.let { "Signed in to zerochan.net" }
                        ?: "Not signed in - members-only tags stay hidden",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.TextButton(onClick = onSignIn) {
                Text(if (account == null) "Sign in" else "Manage")
            }
        }
    }
}

@Composable
private fun Avatar(account: net.berkchy.zerochan.core.net.Account?) {
    val shape = androidx.compose.foundation.shape.CircleShape
    if (account == null) {
        androidx.compose.material3.Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(42.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Login,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        return
    }
    androidx.compose.material3.Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = account.initial,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}