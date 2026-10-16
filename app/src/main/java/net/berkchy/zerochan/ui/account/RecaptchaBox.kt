package net.berkchy.zerochan.ui.account

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * zerochan guards `/register` with a reCAPTCHA v2 checkbox.
 *
 * The widget is rendered from Google's own script inside a WebView and the user ticks it
 * personally, exactly like on the website; the resulting `g-recaptcha-response` token is read
 * back out and posted with the form. Nothing here guesses, solves or works around the check -
 * the site's public site key is used verbatim, so Google sees a normal user interaction.
 */
object RecaptchaKeys {
    /** zerochan's public reCAPTCHA v2 site key, as served inside /register. */
    const val SITE_KEY = "6LdE0DIUAAAAACM2v1ghaEG_H_yv15F1SoXD0L8E"
}

@Composable
fun RecaptchaBox(
    siteKey: String,
    onToken: (String) -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 78.dp,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    val html = remember(siteKey) {
        """
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
                grecaptcha.render('holder', {
                  sitekey: '$siteKey',
                  callback: function (token) { Android.onToken(token); },
                  'expired-callback': function () { Android.onToken(''); },
                  'error-callback': function () { Android.onToken(''); }
                });
                document.getElementById('status').textContent = '';
              }
              window.addEventListener('load', renderCaptcha);
              setTimeout(renderCaptcha, 150);
            </script>
          </body>
        </html>
        """.trimIndent()
    }

    Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(
                                "(function(){try{grecaptcha.render('holder',{sitekey:'$siteKey'," +
                                    "callback:function(t){Android.onToken(t);}," +
                                    "'expired-callback':function(){Android.onToken('');}," +
                                    "'error-callback':function(){Android.onToken('');}})}" +
                                    "catch(e){}})()",
                                null,
                            )
                        }
                    }
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onToken(token: String) {
                                onToken(token)
                            }
                        },
                        "Android",
                    )
                    webView = this
                    loadDataWithBaseURL("https://www.zerochan.net", html, "text/html", "utf-8", null)
                }
            },
            onRelease = { view -> view.destroy() },
        )
    }
}
