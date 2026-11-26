package com.littlebit.chat.web

import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object Search {
    fun https(raw: String): URI? = runCatching { URI(raw.trim()).takeIf {
        it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null
    } }.getOrNull()

    fun fetch(query: String, provider: String, instance: String): List<Pair<String,String>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searx = provider == "SearXNG"
        val url = if (searx) {
            val base = requireNotNull(https(instance)) { "Enter a valid HTTPS SearXNG instance in Plugins & Tools." }
            require(base.fragment == null && base.query == null)
            URI(base.toString().trimEnd('/') + "/search?format=json&q=" + encoded)
        } else URI("https://html.duckduckgo.com/html/?q=$encoded")
        val conn = url.toURL().openConnection() as HttpURLConnection
        val body = try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "LittleBitChat/1.2")
            check(conn.responseCode == 200) { "Search provider returned HTTP ${conn.responseCode}" }
            val limit = 512 * 1024
            conn.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    require(text.length + n <= limit) { "Search response too large." }
                    text.append(buf, 0, n)
                }
                text.toString()
            }
        } finally { conn.disconnect() }
        val entries = if (searx) {
            val rows = JSONObject(body).getJSONArray("results")
            (0 until minOf(30, rows.length())).map { rows.getJSONObject(it) }.map {
                it.optString("url") to Jsoup.parse(it.optString("content")).text()
            }
        } else Jsoup.parse(body, url.toString()).select(".result").mapNotNull { row ->
            val href = row.selectFirst("a.result__a")?.absUrl("href") ?: return@mapNotNull null
            val target = https(href) ?: return@mapNotNull null
            val redirect = target.rawQuery.orEmpty().split("&").firstOrNull { it.startsWith("uddg=") }
            val link = if (target.host.endsWith("duckduckgo.com") && redirect != null)
                URLDecoder.decode(redirect.substringAfter('='), "UTF-8") else href
            link to row.select(".result__snippet").text()
        }
        return entries.filter { (url, text) -> url.length <= 220 && https(url) != null && text.isNotBlank() }
            .distinctBy { it.first }.take(3).map { it.first to it.second.take(180) }
            .also { check(it.isNotEmpty()) { "No usable search results. You can retry without web search." } }
    }
}
