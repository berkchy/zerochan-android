package net.berkchy.zerochan.core.net

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class ZerochanCookieJar(context: Context) : CookieJar {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zerochan_cookies", Context.MODE_PRIVATE)

    private val store = LinkedHashMap<String, Cookie>()

    init {
        val host = HttpUrl.parse(BASE)!!
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

    private fun persist() {
        val value = store.values
            .filter { it.expiresAt > System.currentTimeMillis() }
            .joinToString("; ") { "${it.name}=${it.value}" }
        prefs.edit().putString(HOST_COOKIE, value).apply()
    }

    private companion object {
        const val HOST_COOKIE = "cookies"
        const val BASE = "https://www.zerochan.net/"
    }
}