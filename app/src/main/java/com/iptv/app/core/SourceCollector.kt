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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SourceCollector(private val context: Context) {

    /** 单源采集结果，用于诊断反馈 */
    data class SourceResult(
        val name: String,
        val count: Int,
        val success: Boolean,
        val detail: String,
        val usedMirror: String
    )

    private var lastResults = mutableListOf<SourceResult>()
    fun getLastResults(): List<SourceResult> = lastResults.toList()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 并发抓取所有启用的订阅源，GitHub Raw 源自动尝试镜像回退。
     */
    suspend fun collect(
        sources: List<SourceConfig>,
        onProgress: (CollectProgress) -> Unit
    ): List<Channel> = withContext(Dispatchers.IO) {
        val enabled = sources.filter { it.enabled }
        val total = enabled.size
        var done = 0
        lastResults.clear()
        onProgress(CollectProgress("正在连接数据源", 0, total, "共 $total 个订阅源"))

        val lists = enabled.map { src ->
            async {
                val result = fetchWithFallback(src)
                val info = result.result
                synchronized(lastResults) { lastResults.add(info) }
                done++
                val short = if (info.success) "+${info.count}" else "失败"
                onProgress(
                    CollectProgress(
                        "正在抓取订阅源", done, total,
                        "${src.name} $short${if (info.usedMirror.isNotBlank()) " · ${info.usedMirror}" else ""}"
                    )
                )
                result.channels
            }
        }.awaitAll()

        val all = lists.flatten()
        val okSources = lastResults.count { it.success }
        onProgress(CollectProgress("正在整理频道", total, total, "成功源 $okSources/$total · 原始条目 ${all.size} 条"))

        val merged = Parsers.mergeAndNormalize(
            all,
            Prefs.getBool(context, Prefs.KEY_RETAIN_DUP, true)
        )
        onProgress(CollectProgress("整理完成", total, total, "整理后 ${merged.size} 个频道"))
        merged
    }

    /** 带镜像回退的单源抓取：依次尝试原始地址与各加速镜像 */
    private fun fetchWithFallback(src: SourceConfig): SourceResultWithChannels {
        val candidates = SourceCatalog.candidateUrls(src.url.trim())
        var lastError = ""
        candidates.forEachIndexed { index, url ->
            val label = if (index == 0) "直连" else "镜像$index"
            try {
                val channels = fetchOne(src.copy(url = url))
                if (channels.isNotEmpty()) {
                    return SourceResultWithChannels(
                        SourceResult(src.name, channels.size, true, "OK", label),
                        channels
                    )
                }
                lastError = "空内容"
            } catch (e: Exception) {
                lastError = e.message?.take(40) ?: "连接失败"
            }
        }
        return SourceResultWithChannels(
            SourceResult(src.name, 0, false, lastError, ""),
            emptyList()
        )
    }

    private data class SourceResultWithChannels(val result: SourceResult, val channels: List<Channel>)

    /** 抓取并解析单个源（具体 URL） */
    private fun fetchOne(src: SourceConfig): List<Channel> {
        val url = src.url.trim()
        if (url.isBlank()) return emptyList()
        val ua = src.userAgent.ifBlank { Prefs.getString(context, Prefs.KEY_UA, DEFAULT_UA) }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body?.string() ?: return emptyList()
            if (body.isBlank() || body.length < 50) return emptyList()

            return when (src.type) {
                SourceConfig.TYPE_TXT -> Parsers.parseTxt(body, src.name)
                SourceConfig.TYPE_TVBOX -> {
                    val direct = Parsers.tvBoxDirectUrls(body)
                    direct.mapIndexed { i, (name, u) ->
                        Channel(name, u, group = "TVBox", source = src.name, rawLine = "TVBox#$i")
                    }
                }
                SourceConfig.TYPE_JSON -> Parsers.parseAny(body, src.name)
                else -> Parsers.parseAny(body, src.name)
            }
        }
    }

    /** 导入用户提供的文本（本地文件或粘贴内容） */
    fun parseImported(text: String, label: String): List<Channel> {
        val head = text.trimStart().take(200)
        return when {
            head.startsWith("{") && (text.contains("\"sites\"") || text.contains("\"lives\"")) -> {
                // lives = 可直接播放的频道；sites 中的 m3u/txt 地址作为线路补充
                val lives = Parsers.parseTvBoxLives(text, label)
                val direct = Parsers.tvBoxDirectUrls(text).mapIndexed { i, (name, url) ->
                    Channel(name, url, group = "TVBox", source = label, rawLine = "TVBox#$i")
                }
                (lives + direct).distinctBy { it.url }
            }
            else -> Parsers.parseAny(text, label)
        }
    }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 IPTV/1.1"
    }
}
