package net.berkchy.zerochan.core.net

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class ZerochanCookieJar(context: Context) : CookieJar {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zerochan_cookies", Context.MODE_PRIVATE)

    private val store = LinkedHashMap<String, Cookie>()

    init {
        val host = BASE.toHttpUrl()
        prefs.getString(HOST_COOKIE, null)?.let { value ->
            for (pair in value.split("; ")) {
                val raw = Cookie.parse(host, "$pair; Path=/")
                if (raw != null) store[raw.name] = raw
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
        store.values.filter { it.matches(url) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            var changed = false
            for (cookie in cookies) {
                if (cookie.name == "xbotcheck") changed = true
                store[cookie.name] = cookie
            }
            if (changed) persist()
        }
    }

    fun clear() {
        synchronized(store) {
            store.clear()
            persist()
        }
    }

    /**
     * Signs out: the session cookies go, the bot-check cookie stays so the next request does
     * not have to solve the handshake again.
     */
    fun clearSession() {
        synchronized(store) {
            store.keys.removeAll(SESSION_COOKIES)
            persist()
        }
    }

    fun has(name: String): Boolean = synchronized(store) { store.containsKey(name) }

    fun cookieHeader(): String = synchronized(store) {
        store.values.filter { it.expiresAt > System.currentTimeMillis() }
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun persist() {
        val value = store.values
            .filter { it.expiresAt > System.currentTimeMillis() }
            .joinToString("; ") { "${it.name}=${it.value}" }
        prefs.edit().putString(HOST_COOKIE, value).apply()
    }

    private companion object {
        val SESSION_COOKIES = setOf("PHPSESSID", "guest_id", "z_theme", "user_id", "z_uid")
        const val HOST_COOKIE = "cookies"
        const val BASE = "https://www.zerochan.net/"
    }
}