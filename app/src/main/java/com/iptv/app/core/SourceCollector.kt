package com.iptv.app.core

import android.content.Context
import com.iptv.app.data.Prefs
import com.iptv.app.data.SourceCatalog
import com.iptv.app.model.Channel
import com.iptv.app.model.CollectProgress
import com.iptv.app.model.SourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SourceCollector(private val context: Context) {

    /** 单源采集结果，用于诊断反馈 */
    data class SourceResult(
        val name: String,
        val count: Int,
        val success: Boolean,
        val detail: String,
        val usedMirror: String
    )

    private val lastLock = Any()
    private var lastResults = mutableListOf<SourceResult>()
    fun getLastResults(): List<SourceResult> = synchronized(lastLock) { lastResults.toList() }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 并发抓取所有启用的订阅源，GitHub Raw 源自动尝试镜像回退。
     *
     * 稳定性（v2.0.1）：有界并发（最多 8 个源同时进行），进度计数用原子类，
     * 进度回调异常隔离，响应体安全读取防 OOM。
     */
    suspend fun collect(
        sources: List<SourceConfig>,
        onProgress: (CollectProgress) -> Unit
    ): List<Channel> = withContext(Dispatchers.IO) {
        val enabled = sources.filter { it.enabled }
        val total = enabled.size
        if (total == 0) return@withContext emptyList()

        val done = AtomicInteger(0)
        synchronized(lastLock) { lastResults = mutableListOf() }
        safeProgress(onProgress, CollectProgress("正在连接数据源", 0, total, "共 $total 个订阅源"))

        val sem = Semaphore(8)
        val results = arrayOfNulls<List<Channel>>(total)
        val workers = (0 until minOf(8, total)).map { workerId ->
            async {
                var index = workerId
                while (index < total) {
                    val src = enabled[index]
                    sem.withPermit {
                        val pair = try {
                            fetchWithFallback(src)
                        } catch (e: Throwable) {
                            SourceResultWithChannels(
                                SourceResult(src.name, 0, false, safeReason(e), ""), emptyList()
                            )
                        }
                        synchronized(lastLock) { lastResults.add(pair.result) }
                        val d = done.incrementAndGet()
                        val info = pair.result
                        val short = if (info.success) "+${info.count}" else "失败"
                        safeProgress(
                            onProgress,
                            CollectProgress(
                                "正在抓取订阅源", d, total,
                                "${src.name} $short${if (info.usedMirror.isNotBlank()) " · ${info.usedMirror}" else ""}"
                            )
                        )
                        val kind = if (src.type == SourceConfig.TYPE_TVBOX) Channel.ORIGIN_TVBOX else Channel.ORIGIN_COLLECT
                        results[index] = SourceOrigin.tag(pair.channels, kind, "${src.name}|${src.url}")
                    }
                    index += minOf(8, total)
                }
            }
        }
        workers.awaitAll()

        val all = results.filterNotNull().flatten()
        val okSources = synchronized(lastLock) { lastResults.count { it.success } }
        safeProgress(onProgress, CollectProgress("正在整理频道", total, total, "成功源 $okSources/$total · 原始条目 ${all.size} 条"))

        val merged = try {
            Parsers.mergeAndNormalize(all, Prefs.getBool(context, Prefs.KEY_RETAIN_DUP, true))
        } catch (e: Throwable) {
            // 归一化异常时至少返回去重后的原始列表，不丢数据
            all.distinctBy { it.url }
        }
        safeProgress(onProgress, CollectProgress("整理完成", total, total, "整理后 ${merged.size} 个频道"))
        merged
    }

    /** 带镜像回退的单源抓取：依次尝试原始地址与各加速镜像 */
    private fun fetchWithFallback(src: SourceConfig): SourceResultWithChannels {
        val candidates = try { SourceCatalog.candidateUrls(src.url.trim()) } catch (_: Throwable) { listOf(src.url.trim()) }
        var lastError = ""
        candidates.forEachIndexed { index, url ->
            val label = if (index == 0) "直连" else "镜像$index"
            try {
                val channels = fetchOne(src.copy(url = url))
                if (channels.isNotEmpty()) {
                    return SourceResultWithChannels(
                        SourceResult(src.name, channels.size, true, "OK", label), channels
                    )
                }
                lastError = "空内容"
            } catch (e: Throwable) {
                lastError = safeReason(e)
            }
        }
        return SourceResultWithChannels(SourceResult(src.name, 0, false, lastError, ""), emptyList())
    }

    private data class SourceResultWithChannels(val result: SourceResult, val channels: List<Channel>)

    /** 抓取并解析单个源（具体 URL） */
    private fun fetchOne(src: SourceConfig): List<Channel> {
        val url = src.url.trim()
        if (url.isBlank() || !url.contains("://")) return emptyList()
        val ua = src.userAgent.ifBlank { Prefs.getString(context, Prefs.KEY_UA, DEFAULT_UA) }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            // 安全读取，限制大小，杜绝异常源 OOM
            val read = SafeBody.readText(resp)
            val body = read.text
            if (body.isBlank() || body.length < 50) return emptyList()

            return when (src.type) {
                SourceConfig.TYPE_TXT -> Parsers.parseTxt(body, src.name)
                SourceConfig.TYPE_TVBOX -> expandTvBoxSync(body, url, src.name, ua)
                else -> Parsers.parseAny(body, src.name)
            }
        }
    }

    /**
     * 同步展开 TVBox 配置（采集流程在 IO 线程调用）。
     * lives[].url 是订阅列表地址时必须二次抓取解析；直接流地址则直接建频道。
     */
    private fun expandTvBoxSync(
        text: String, configUrl: String, label: String, defaultUa: String
    ): List<Channel> {
        val lives = TvBoxParser.parseLives(text)
        if (lives.isEmpty()) return emptyList()
        val out = mutableListOf<Channel>()
        lives.forEach { live ->
            try {
                val resolved = TvBoxParser.resolveUrl(live.url, configUrl) ?: return@forEach
                val ua = live.ua.ifBlank { defaultUa }
                val sample = fetchHead(resolved, ua)
                when (TvBoxParser.detectContentKind(resolved, sample)) {
                    TvBoxParser.ContentKind.DIRECT ->
                        out += Channel(live.name, resolved, live.group.ifBlank { "TVBox" },
                            logo = live.logo, epg = live.epg, source = "$label·${live.name}", userAgent = ua)
                    TvBoxParser.ContentKind.PLAYLIST -> {
                        val full = if (sample != null && sample.length >= 4096) sample else fetchFull(resolved, ua)
                        if (!full.isNullOrBlank()) {
                            Parsers.parseAny(full, label).forEach { ch ->
                                out += ch.copy(
                                    group = ch.group.ifBlank { live.group.ifBlank { live.name } },
                                    logo = ch.logo.ifBlank { live.logo },
                                    epg = ch.epg.ifBlank { live.epg },
                                    source = "$label·${live.name}",
                                    userAgent = ua
                                )
                            }
                        }
                    }
                    TvBoxParser.ContentKind.UNKNOWN -> {
                        if (resolved.lowercase().let {
                                it.contains(".m3u8") || it.contains(".flv") ||
                                it.contains(".mp4") || it.startsWith("rtmp")
                            }) {
                            out += Channel(live.name, resolved, live.group.ifBlank { "TVBox" },
                                logo = live.logo, epg = live.epg, source = "$label·${live.name}", userAgent = ua)
                        }
                    }
                }
            } catch (_: Throwable) { }
        }
        return out.distinctBy { it.url }
    }

    /** 抓取头部样本（Range 请求），返回前 4096 字节文本 */
    private fun fetchHead(url: String, ua: String): String? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua).header("Range", "bytes=0-4095").header("Accept", "*/*").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            SafeBody.readAscii(resp, 4096)
        }
    } catch (_: Throwable) { null }

    /** 抓取完整文本（安全限制大小） */
    private fun fetchFull(url: String, ua: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", ua).header("Accept", "*/*").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else SafeBody.readText(resp).text
        }
    } catch (_: Throwable) { null }

    /** 导入用户提供的文本（本地文件或粘贴内容）；TVBox 配置会走二次展开 */
    fun parseImported(text: String, label: String, configUrl: String? = null): List<Channel> {
        return try {
            if (TvBoxParser.isTvBoxConfig(text)) {
                expandTvBoxSync(text, configUrl ?: "", label, DEFAULT_UA)
            } else Parsers.parseAny(text, label)
        } catch (_: Throwable) { emptyList() }
    }

    private fun safeReason(e: Throwable): String = when (e) {
        is java.io.IOException -> "连接失败"
        is OutOfMemoryError -> "响应过大"
        else -> e.message?.take(40)?.ifBlank { "连接失败" } ?: "连接失败"
    }

    private fun safeProgress(cb: (CollectProgress) -> Unit, p: CollectProgress) {
        try { cb(p) } catch (_: Throwable) {}
    }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 IPTV/1.1"
    }
}
