package net.berkchy.zerochan.ui.account

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * zerochan guards `/register` with a reCAPTCHA v2 checkbox.
 *
 * The widget is rendered from Google's own script inside a WebView and the user ticks it
 * personally, exactly like on the website; the resulting `g-recaptcha-response` token is read
 * back out and posted with the form. Nothing here guesses, solves or works around the check -
 * the site's public site key is used verbatim, so Google sees a normal user interaction.
 *
 * v2 sometimes escalates to an image challenge ("select all the buses") instead of accepting the
 * tick, and that challenge is a tall iframe Google injects into the document after the tap, with
 * no callback to hook. So the box cannot have a fixed height: it measures itself and grows to fit
 * the challenge, capped, and scrolls past the cap.
 */
object RecaptchaKeys {
    /** zerochan's public reCAPTCHA v2 site key, as served inside /register. */
    const val SITE_KEY = "6LdE0DIUAAAAACM2v1ghaEG_H_yv15F1SoXD0L8E"
}

/** The bare checkbox is ~84dp; an image challenge needs several times that. */
private val CHECKBOX_HEIGHT = 84.dp
private val CHALLENGE_MAX_HEIGHT = 560.dp
private const val MEASURE_INTERVAL_MS = 400L

/** Tallest rendered element in css pixels - the challenge iframe included. */
private const val HEIGHT_PROBE =
    "Math.max(" +
        "document.body ? document.body.scrollHeight : 0," +
        "document.documentElement ? document.documentElement.scrollHeight : 0," +
        "document.documentElement ? document.documentElement.offsetHeight : 0)"

@Composable
fun RecaptchaBox(
    siteKey: String,
    onToken: (String) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = CHECKBOX_HEIGHT,
) {
    val context = LocalContext.current
    val tokenState = rememberUpdatedState(onToken)

    val html = remember(siteKey) { recaptchaDocument(siteKey) }
    val webView = remember {
        WebView(context).apply {
            configureRecaptcha(html, siteKey) { token -> tokenState.value(token) }
        }
    }

    var measured by remember { mutableIntStateOf(minHeight.value.toInt()) }
    val floor = minHeight.value
    val ceiling = CHALLENGE_MAX_HEIGHT.value

    LaunchedEffect(webView, floor, ceiling) {
        val density = webView.resources.displayMetrics.density
        while (true) {
            delay(MEASURE_INTERVAL_MS)
            val px = webView.contentHeightPx() * density
            if (px <= 0f) continue
            measured = px.toInt().coerceIn((floor * density).toInt(), (ceiling * density).toInt())
        }
    }

    Box(modifier.fillMaxWidth().height(measured.dp), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { webView },
            onRelease = { view -> view.stopLoading(); view.destroy() },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureRecaptcha(html: String, siteKey: String, onBridge: (String) -> Unit) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = false
    // A challenge is taller than the phone, so the box scrolls rather than clipping it.
    isVerticalScrollBarEnabled = true
    isHorizontalScrollBarEnabled = false
    setBackgroundColor(Color.TRANSPARENT)

    addJavascriptInterface(
        object {
            @JavascriptInterface
            fun onToken(token: String) = onBridge(token)
        },
        "Android",
    )
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            // The script loads async, so the explicit render is retried from onPageFinished too.
            view.evaluateJavascript(renderCall(siteKey), null)
        }
    }
    loadDataWithBaseURL("https://www.zerochan.net", html, "text/html", "utf-8", null)
}

private fun recaptchaDocument(siteKey: String): String = """
    <!DOCTYPE html>
    <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html,body{margin:0;padding:0;background:transparent;
            font-family:system-ui,-apple-system,Roboto,sans-serif;}
          #holder{display:flex;justify-content:center;padding:4px 0;}
          #status{font-size:12px;color:#666;padding:4px 8px;text-align:center;}
        </style>
        <script src="https://www.google.com/recaptcha/api.js?render=explicit" async defer></script>
      </head>
      <body>
        <div id="holder"></div>
        <div id="status">Loading…</div>
        <script>
          function renderCaptcha() {
            if (!window.grecaptcha || !grecaptcha.render) { setTimeout(renderCaptcha, 120); return; }
            ${renderCall(siteKey)}
            document.getElementById('status').textContent = '';
          }
          window.addEventListener('load', renderCaptcha);
          setTimeout(renderCaptcha, 150);
        </script>
      </body>
    </html>
""".trimIndent()

/** `grecaptcha.render` with the site's own key; the token goes straight to the Java bridge. */
private fun renderCall(siteKey: String) =
    "(function(){try{grecaptcha.render('holder',{sitekey:'$siteKey'," +
        "callback:function(t){Android.onToken(t);}," +
        "'expired-callback':function(){Android.onToken('');}," +
        "'error-callback':function(){Android.onToken('');}})}catch(e){}})()"

private suspend fun WebView.contentHeightPx(): Int = suspendCancellableCoroutine { cont ->
    evaluateJavascript(HEIGHT_PROBE) { value ->
        if (cont.isActive) cont.resume(value?.trim('"')?.toDoubleOrNull()?.toInt() ?: 0)
    }
}