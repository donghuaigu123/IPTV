package com.iptv.app.core

import android.content.Context
import com.iptv.app.data.Prefs
import com.iptv.app.model.Channel
import com.iptv.app.model.CollectProgress
import com.iptv.app.model.TvBoxLive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.IDN
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * TVBox 配置导入器（v1.6 核心修复）。
 *
 * 旧版失败根因：把 lives[].url（订阅列表地址）直接当成可播放频道塞给播放器。
 * 本类正确做法：抓取每个订阅地址 → 解析出真实频道 → 继承该线路 UA/分组/台标，
 * 这样导入后的频道才是真正可播放的媒体流地址。
 */
class TvBoxImporter(private val context: Context) {

    data class ImportResult(
        val channels: List<Channel>,
        val liveCount: Int,          // 配置中 lives 条数
        val expandedCount: Int,      // 成功展开的订阅数
        val directCount: Int,        // 直接流地址数
        val failedUrls: List<String>
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 从配置文本导入。configUrl 为配置来源地址（可能为空，如手动粘贴），用于解析相对路径。
     */
    suspend fun importFromText(
        text: String,
        configUrl: String?,
        label: String,
        onProgress: (CollectProgress) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        // v2.0.2：粘贴内容也可能带历史乱码，先做兜底修复
        val fixed = TextEncoding.fixMojibake(text)
        val lives = try { TvBoxParser.parseLives(fixed) } catch (_: Throwable) { emptyList() }
        if (lives.isEmpty()) {
            return@withContext ImportResult(emptyList(), 0, 0, 0, emptyList())
        }
        expandLives(lives, configUrl, label, onProgress)
    }

    /**
     * 从配置 URL 导入：先抓取配置文本，再展开。
     */
    suspend fun importFromUrl(
        configUrl: String,
        label: String,
        onProgress: (CollectProgress) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        val text = fetch(configUrl, SourceCollector.DEFAULT_UA)
            ?: return@withContext ImportResult(emptyList(), 0, 0, 0, listOf(configUrl))
        if (!TvBoxParser.isTvBoxConfig(text)) {
            // 也可能用户填的本身就是订阅列表或 m3u，直接当普通源解析
            val chans = try { Parsers.parseAny(text, label) } catch (_: Throwable) { emptyList() }
            return@withContext ImportResult(chans, 0, 0, 0, emptyList())
        }
        val lives = try { TvBoxParser.parseLives(text) } catch (_: Throwable) { emptyList() }
        if (lives.isEmpty()) {
            return@withContext ImportResult(emptyList(), 0, 0, 0, emptyList())
        }
        expandLives(lives, configUrl, label, onProgress)
    }

    /** 展开 lives：并发抓取每个订阅地址，解析成真实频道 */
    private suspend fun expandLives(
        lives: List<TvBoxLive>,
        configUrl: String?,
        label: String,
        onProgress: (CollectProgress) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        val total = lives.size
        if (total == 0) return@withContext ImportResult(emptyList(), 0, 0, 0, emptyList())
        val concurrency = minOf(8, total)
        val sem = Semaphore(concurrency)
        val done = AtomicInteger(0)
        val failed = java.util.Collections.synchronizedList(mutableListOf<String>())

        // each: 第一条 = 频道列表，第二条 = 是否为展开的订阅(true)/直接流(false)
        val results = arrayOfNulls<Pair<List<Channel>, Boolean>>(total)
        val workers = (0 until concurrency).map { workerId ->
            async {
                var index = workerId
                while (index < total) {
                    val live = lives[index]
                    sem.withPermit {
                        val pair: Pair<List<Channel>, Boolean> = try {
                            val resolved = TvBoxParser.resolveUrl(live.url, configUrl)
                            if (resolved == null) {
                                failed.add(live.url)
                                Pair(emptyList<Channel>(), false)
                            } else {
                                val ua = live.ua.ifBlank { SourceCollector.DEFAULT_UA }
                                val sample = fetchSample(resolved, ua)
                                val kind = TvBoxParser.detectContentKind(resolved, sample)
                                when (kind) {
                                    TvBoxParser.ContentKind.DIRECT ->
                                        Pair(listOf(toChannel(live, resolved, label, ua)), false)
                                    TvBoxParser.ContentKind.PLAYLIST -> {
                                        val full = sample?.let { if (it.length >= 4096) it else fetch(resolved, ua) } ?: fetch(resolved, ua)
                                        if (full.isNullOrBlank()) {
                                            failed.add(live.url)
                                            Pair(emptyList(), true)
                                        } else {
                                            val chans = try {
                                                Parsers.parseAny(full, label).map { ch ->
                                                    ch.copy(
                                                        group = ch.group.ifBlank { live.group.ifBlank { live.name } },
                                                        logo = ch.logo.ifBlank { live.logo },
                                                        epg = ch.epg.ifBlank { live.epg },
                                                        source = "$label·${live.name}",
                                                        userAgent = ua
                                                    )
                                                }
                                            } catch (_: Throwable) { emptyList() }
                                            Pair(chans, true)
                                        }
                                    }
                                    TvBoxParser.ContentKind.UNKNOWN -> {
                                        if (resolved.lowercase().let {
                                                it.contains(".m3u8") || it.contains(".flv") ||
                                                it.contains(".mp4") || it.startsWith("rtmp")
                                            }) {
                                            Pair(listOf(toChannel(live, resolved, label, ua)), false)
                                        } else {
                                            failed.add(live.url)
                                            Pair(emptyList(), true)
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                            failed.add(live.url)
                            Pair(emptyList(), false)
                        }
                        results[index] = pair
                        val d = done.incrementAndGet()
                        if (d % 2 == 0 || d == total) {
                            safeProgress(onProgress, CollectProgress("展开 TVBox 订阅", d, total, "${live.name} · ${pair.first.size} 个频道"))
                        }
                    }
                    index += concurrency
                }
            }
        }
        workers.awaitAll()

        val flat = results.filterNotNull().flatMap { it.first }
            .filter { it.url.contains("://") }
            .distinctBy { it.url }

        safeProgress(onProgress, CollectProgress("导入完成", total, total, "共 ${flat.size} 个可播放频道"))
        val validResults = results.filterNotNull()
        ImportResult(
            channels = flat,
            liveCount = total,
            expandedCount = validResults.count { it.second && it.first.isNotEmpty() },
            directCount = validResults.count { !it.second && it.first.isNotEmpty() },
            failedUrls = failed.toList()
        )
    }

    private fun toChannel(live: TvBoxLive, url: String, label: String, ua: String): Channel = Channel(
        name = live.name,
        url = url,
        group = live.group.ifBlank { "TVBox" },
        logo = live.logo,
        epg = live.epg,
        source = "$label·${live.name}",
        userAgent = ua
    )

    /** 抓取前若干字节做类型判断（避免整篇下载） */
    private fun fetchSample(url: String, ua: String): String? = try {
        val req = Request.Builder()
            .url(normalizeUrl(url))
            .header("User-Agent", ua)
            .header("Range", "bytes=0-4095")
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            SafeBody.readAscii(resp, 4096)
        }
    } catch (_: Throwable) { null }

    /** 抓取完整内容（安全限制大小） */
    private fun fetch(url: String, ua: String): String? = try {
        val req = Request.Builder()
            .url(normalizeUrl(url))
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else SafeBody.readText(resp).text
        }
    } catch (_: Throwable) { null }

    private fun safeProgress(cb: (CollectProgress) -> Unit, p: CollectProgress) {
        try { cb(p) } catch (_: Throwable) {}
    }

    /** 中文域名转 punycode，避免 okhttp 解析失败 */
    private fun normalizeUrl(url: String): String = try {
        val u = java.net.URL(url)
        val host = u.host
        val asciiHost = IDN.toASCII(host)
        if (host == asciiHost) url
        else url.replace(host, asciiHost, ignoreCase = true)
    } catch (_: Exception) {
        url
    }
}
