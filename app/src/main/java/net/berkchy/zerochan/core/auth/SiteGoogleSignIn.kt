package net.berkchy.zerochan.core.auth

import android.webkit.CookieManager
import net.berkchy.zerochan.core.net.AppLog
import net.berkchy.zerochan.core.net.BotCheckInterceptor
import net.berkchy.zerochan.core.net.ZerochanApi

/**
 * zerochan signs users in with Google Identity Services (GIS) against **its own** OAuth client:
 *
 * ```html
 * <div id="g_id_onload"
 *      data-client_id="280853676974-….apps.googleusercontent.com"
 *      data-ux_mode="redirect"
 *      data-login_uri="https://www.zerochan.net/oauth"></div>
 * ```
 *
 * `login_uri` makes GIS post a `credential=<jwt>` form straight to `/oauth`, and that endpoint
 * validates the jwt against zerochan's client id. A token minted for any *other* client is
 * rejected with `Invalid ID token, account unlinked?` - which is exactly why an OAuth code +
 * PKCE exchange against Google's own token endpoint can never sign in here, and why Google's
 * Android SDK (`play-services-auth`, Credential Manager) is the wrong tool: it is designed to
 * mint tokens for the calling app's client, not for someone else's.
 *
 * So the credential has to come out of a real browser engine. The site's own `/login` document is
 * loaded in a WebView, the user taps the real Google button, Google posts the credential to
 * `/oauth`, the server binds the account to a `PHPSESSID` - and that cookie is lifted into the
 * app's OkHttp jar, after which every request is authenticated exactly like any other sign in.
 */
object SiteGoogleSignIn {

    /** The real login document, GIS button and all. */
    const val LOGIN_URL = "${ZerochanApi.BASE}/login"

    /** Where GIS posts the credential. A `POST` here means the token exchange already happened. */
    const val OAUTH_PATH = "/oauth"

    /** Origin the WebView reports, and the one Google's client checks against. */
    const val WEB_ORIGIN = ZerochanApi.BASE

    /**
     * zerochan's public web OAuth client id, served in `<meta name="google-signin-client_id">`.
     * Kept as a constant only to document what the button on `/login` is bound to; the WebView
     * reads the live value off the page, so this is never wired into a request.
     */
    const val CLIENT_ID = "280853676974-pfa7tcmm6lb529u5b5fkb5aeue4i8j0u.apps.googleusercontent.com"

    /**
     * The same desktop Chrome agent every other request uses.
     *
     * Two reasons, both load bearing. zerochan's bot-check cookie is tied to the agent that
     * solved it, so a stock WebView agent (`; wv`) would invalidate the seeded `xbotcheck` and
     * force the interstitial again. And Google refuses OAuth from clients it recognises as
     * embedded WebViews, which it detects from exactly that token.
     */
    val userAgent: String get() = BotCheckInterceptor.USER_AGENT

    /** True for the `POST /oauth` that GIS performs once the user has picked an account. */
    fun isCredentialPost(host: String?, path: String?, method: String?): Boolean =
        host.equals(BotCheckInterceptor.HOST, ignoreCase = true) &&
            path == OAUTH_PATH &&
            method.equals("POST", ignoreCase = true)

    fun isZerochan(host: String?): Boolean =
        host.equals(BotCheckInterceptor.HOST, ignoreCase = true)

    /** Hosts the sign in itself is allowed to navigate to: zerochan, and Google. */
    fun isSignInHost(host: String?): Boolean =
        isZerochan(host) ||
            host?.endsWith(".google.com", ignoreCase = true) == true ||
            host?.endsWith(".googleapis.com", ignoreCase = true) == true ||
            host == "accounts.google.com"

    /**
     * Copies the app's cookies into the WebView jar so the bot check the app already solved is
     * reused instead of the WebView having to run the interstitial itself. `PHPSESSID` is
     * deliberately left out: a pre-existing session must never be mistaken for the one the
     * Google sign in is about to create.
     */
    fun seed(cookieHeader: String) {
        val keep = cookieHeader.split("; ")
            .map { it.trim() }
            .filter { pair ->
                val name = pair.substringBefore('=')
                name == "xbotcheck" || name == "guest_id" || name == "z_theme"
            }
        if (keep.isEmpty()) return
        CookieManager.getInstance().setCookie(WEB_ORIGIN, keep.joinToString("; "))
        AppLog.d(TAG, "seeded the web view with ${keep.size} cookie(s)")
    }

    /** The WebView's current cookie jar for zerochan, in `Cookie:` header form. */
    fun harvest(): String? =
        CookieManager.getInstance().getCookie(WEB_ORIGIN)?.takeIf { it.contains("PHPSESSID") }

    /**
     * Expires only what this flow put in the WebView jar, once the session has been lifted into
     * the app's own. Wiping every cookie instead would take the reCAPTCHA widget's jar with it.
     */
    fun clear() {
        val manager = CookieManager.getInstance()
        SESSION_COOKIES.forEach { manager.setCookie(WEB_ORIGIN, "$it=; Max-Age=0; Path=/") }
        manager.flush()
        AppLog.d(TAG, "cleared the web view session")
    }

    /**
     * `/oauth` answers `200` even when it refuses the credential, with a php `var_dump` of the
     * failure. Read the rendered text rather than the body so the exact reason survives.
     */
    fun unlinkedReason(pageText: String): String? =
        if (pageText.contains("unlinked", ignoreCase = true) ||
            pageText.contains("Invalid ID token", ignoreCase = true)
        ) {
            "zerochan does not know that Google account. Register on the website first, then " +
                "link Google under your account settings, then sign in here."
        } else {
            null
        }

    /**
     * Google refuses OAuth in an embedded WebView (`403 disallowed_useragent`, "this browser or
     * app may not be secure"). That verdict arrives as a redirect back onto `/login` with the
     * reason in the query, or as Google's own error document, so it is matched on both.
     */
    fun isRefusedByGoogle(url: String, pageText: String): Boolean =
        url.contains("disallowed_useragent", ignoreCase = true) ||
            (url.contains("error", ignoreCase = true) &&
                url.contains("secure", ignoreCase = true)) ||
            pageText.contains("may not be secure", ignoreCase = true) ||
            pageText.contains("disallowed_useragent", ignoreCase = true)

    /** Google blocked the sign in; there is nothing to retry in this WebView. */
    const val REFUSED_MESSAGE =
        "Google will not sign in from inside an app's browser. Tap Browser, sign in on the " +
            "website, then bring the session over with Settings → Account → Import."

    private val SESSION_COOKIES = listOf("PHPSESSID", "guest_id", "user_id", "z_uid")
    private const val TAG = "gsi"
}