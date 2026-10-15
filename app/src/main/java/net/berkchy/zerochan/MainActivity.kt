package net.berkchy.zerochan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import net.berkchy.zerochan.ui.ZerochanNavHost
import net.berkchy.zerochan.ui.theme.ZerochanTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val repository = (application as ZerochanApp).repository

        setContent {
            ZerochanTheme {
                ZerochanNavHost(
                    repository = repository,
                    onShare = ::share,
                    onOpenUrl = ::openUrl,
                )
            }
        }
    }

    private fun share(url: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "zerochan")
            putExtra(Intent.EXTRA_TEXT, "$text\n$url")
        }
        startActivity(Intent.createChooser(intent, "Share post"))
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}