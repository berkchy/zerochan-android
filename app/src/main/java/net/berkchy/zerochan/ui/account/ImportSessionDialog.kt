package net.berkchy.zerochan.ui.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.berkchy.zerochan.core.net.ZerochanApi

/**
 * The escape hatch that needs nobody's cooperation: sign in on the website in your own browser,
 * copy the session cookie, paste it here. The app then makes exactly the same authenticated
 * requests it would after an in-app sign in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSessionDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
    busy: Boolean,
    error: String?,
) {
    val context = LocalContext.current
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import session") },
        text = {
            Column {
                Text(
                    text = "Sign in on zerochan.net in your browser, then copy the Cookie " +
                        "header and paste it below. The full \"PHPSESSID=...\" value is enough.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            runCatching { context.startActivity(openLoginIntent(context)) }
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Open login")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { value = clipboard(context) }) {
                        Icon(
                            androidx.compose.material.icons.Icons.Rounded.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Paste")
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Cookie") },
                    placeholder = { Text("PHPSESSID=a1b2c3…") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(value.trim()) },
                enabled = !busy && value.isNotBlank(),
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun openLoginIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("${ZerochanApi.BASE}/login"))

private fun clipboard(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return ""
    val clip = manager.primaryClip ?: return ""
    return (0 until clip.itemCount).joinToString("; ") { clip.getItemAt(it).text?.toString().orEmpty() }
}
