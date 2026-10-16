package net.berkchy.zerochan.ui.account

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.berkchy.zerochan.core.auth.SiteGoogleSignIn
import net.berkchy.zerochan.core.net.AppLog

/**
 * Runs zerochan's real "Sign in with Google" button.
 *
 * This is not a stand in for the website, it *is* the website: the actual `/login` document with
 * the actual Google Identity Services button, against the actual client id zerochan registered.
 * Only the browser engine is the app's. See [SiteGoogleSignIn] for why there is no other way to
 * obtain that credential without Google's Android SDK.
 *
 * The sign in itself is completed by the server, in the site's own `/oauth` handler, which binds
 * the account to a `PHPSESSID` inside the WebView's cookie jar. That cookie is handed back
 * through [onSession] and installed in the app's OkHttp jar - so from there on the app is signed
 * in exactly as it would be after a username / password sign in, and the WebView can go away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleSignInSheet(
    seedCookies: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loading by remember { mutableStateOf(true) }
    var handoffError by remember { mutableStateOf<String?>(null) }

    val session by rememberUpdatedState(onSession)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sign in with Google", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "zerochan.net",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(SiteGoogleSignIn.LOGIN_URL),
                        )
                        runCatching { context.startActivity(intent) }
                    },
                ) {
                    Icon(
                        Icons.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Browser")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            // Three sources, one line: the WebView refusing to load, zerochan refusing the
            // credential, and the app refusing the session that came back.
            val notice = handoffError ?: error
            if (notice != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            } else if (busy) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Signing you in…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            if (loading || busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(4.dp))
            }

            GoogleSignInWebView(
                seedCookies = seedCookies,
                loading = { loading = it },
                onSession = { header ->
                    loading = false
                    handoffError = null
                    session(header)
                },
                onFailure = { reason ->
                    loading = false
                    handoffError = reason
                },
                onDismiss = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 420.dp, max = 620.dp),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GoogleSignInWebView(
    seedCookies: String,
    onSession: (String) -> Unit,
    onFailure: (String) -> Unit,
    onDismiss: () -> Unit,
    loading: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val seed = remember(seedCookies) { seedCookies }
    val session by rememberUpdatedState(onSession)
    val failed by rememberUpdatedState(onFailure)
    val dismiss by rememberUpdatedState(onDismiss)
    val progress by rememberUpdatedState(loading)

    // Handed over from a WebView worker thread, so plain volatile flags rather than snapshot state.
    val flags = remember { SignInFlags() }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.configure(SiteGoogleSignIn.userAgent)
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webChromeClient = WebChromeClient()
        }
    }

    val sessionCallback: (String) -> Unit = { header -> session(header) }
    val failureCallback: (String) -> Unit = { reason -> failed(reason) }
    val progressCallback: (Boolean) -> Unit = { value -> progress(value) }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else dismiss()
    }

    AndroidView(
        modifier = modifier,
        factory = { view ->
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            manager.setAcceptThirdPartyCookies(view, true)
            // Reuse the bot check the app already solved, so the interstitial is skipped. A
            // PHPSESSID is deliberately not seeded: the session that matters is the one the
            // Google sign in is about to create.
            SiteGoogleSignIn.seed(seed)
            manager.flush()
            view.webViewClient = SignInClient(
                flags,
                progressCallback,
                sessionCallback,
                failureCallback,
            )
            view.loadUrl(SiteGoogleSignIn.LOGIN_URL)
            view
        },
        onRelease = { view ->
            SiteGoogleSignIn.clear()
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        },
    )
}

/** Cross thread hand off state between the WebView callbacks and the sheet. */
private class SignInFlags {
    @Volatile var credentialPosted = false
    @Volatile var handingOff = false
    @Volatile var handedOver = false
}

private class SignInClient(
    private val flags: SignInFlags,
    private val loading: (Boolean) -> Unit,
    private val session: (String) -> Unit,
    private val failure: (String) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url
        if (SiteGoogleSignIn.isCredentialPost(url.host, url.encodedPath, request.method)) {
            AppLog.d(TAG, "google posted the credential to /oauth")
            flags.credentialPosted = true
        }
        return null
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        loading(true)
        val parsed = Uri.parse(url)
        // `shouldInterceptRequest` is skipped for some navigations; the path on its own is enough
        // of a signal, the session cookie is what actually decides success.
        if (SiteGoogleSignIn.isCredentialPost(parsed.host, parsed.encodedPath, "POST")) {
            flags.credentialPosted = true
        }
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url
        if (SiteGoogleSignIn.isSignInHost(url.host)) return false
        // Anything off the sign in path (a "privacy policy" link, say) belongs in the browser,
        // not in a WebView that is holding a live zerochan session.
        return runCatching {
            view.context.startActivity(Intent(Intent.ACTION_VIEW, url))
            true
        }.getOrDefault(false)
    }

    override fun onPageFinished(view: WebView, url: String) {
        loading(false)
        AppLog.d(TAG, "web view at $url (credential posted: ${flags.credentialPosted})")
        if (!flags.credentialPosted || flags.handingOff) return
        // Give the response a moment to land in the cookie jar before reading it.
        view.postDelayed({ handOver(view) }, HARVEST_DELAY_MS)
    }

    private fun handOver(view: WebView) {
        if (flags.handingOff) return
        flags.handingOff = true
        // `/oauth` answers `200` even when it refuses the credential, with a php var_dump as the
        // page body, so the rendered text is the only place the reason is written down. Reading
        // it is asynchronous; the cookie header is not, and is the fallback if the read stalls.
        view.pageText { text -> complete(SiteGoogleSignIn.unlinkedReason(text)) }
        view.postDelayed({ complete(null) }, TEXT_TIMEOUT_MS)
    }

    /** Runs at most once, whichever of the two hand offs above gets there first. */
    private fun complete(unlinked: String?) {
        if (flags.handedOver) return
        flags.handedOver = true
        if (unlinked != null) {
            failure(unlinked)
            return
        }
        val header = SiteGoogleSignIn.harvest()
        if (header == null) {
            failure("Google signed you in but zerochan issued no session")
            return
        }
        session(header)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (!request.isForMainFrame) return
        failure("Could not reach zerochan (${error.errorCode})")
    }
}

private fun WebSettings.configure(userAgent: String) {
    javaScriptEnabled = true
    domStorageEnabled = true
    this.userAgentString = userAgent
    loadWithOverviewMode = true
    useWideViewPort = true
    // The login document is the only thing this ever loads: no local files, no content provider,
    // no mixed content, and no second window for anything to escape into.
    allowFileAccess = false
    allowContentAccess = false
    javaScriptCanOpenWindowsAutomatically = false
    setSupportMultipleWindows(false)
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
}

/** The rendered text of the current page, for reading the text of an error response. */
private fun WebView.pageText(onReady: (String) -> Unit) {
    evaluateJavascript("document.body ? document.body.innerText : ''") { value ->
        onReady(value?.trim('"').orEmpty())
    }
}

private const val TAG = "gsi"
private const val HARVEST_DELAY_MS = 400L
private const val TEXT_TIMEOUT_MS = 1_200L