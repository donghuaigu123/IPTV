package com.iptv.app.core

import android.content.Context
import com.iptv.app.data.Prefs
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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 并发抓取所有启用的订阅源并解析为频道。
     */
    suspend fun collect(
        sources: List<SourceConfig>,
        onProgress: (CollectProgress) -> Unit
    ): List<Channel> = withContext(Dispatchers.IO) {
        val enabled = sources.filter { it.enabled }
        val total = enabled.size
        var done = 0
        onProgress(CollectProgress("正在连接数据源", 0, total, "共 $total 个订阅源"))

        val lists = enabled.map { src ->
            async {
                val chans = try {
                    fetchOne(src)
                } catch (e: Exception) {
                    onProgress(CollectProgress("抓取失败", done + 1, total, "${src.name}: ${e.message}"))
                    emptyList()
                }
                done++
                onProgress(CollectProgress("正在抓取订阅源", done, total, src.name))
                chans
            }
        }.awaitAll()

        val all = lists.flatten()
        onProgress(CollectProgress("正在整理频道", total, total, "原始条目 ${all.size} 条"))
        val merged = Parsers.mergeAndNormalize(
            all,
            Prefs.getBool(context, Prefs.KEY_RETAIN_DUP, true)
        )
        onProgress(CollectProgress("整理完成", total, total, "整理后 ${merged.size} 个频道"))
        merged
    }

    /** 抓取并解析单个源 */
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
            if (body.isBlank()) return emptyList()

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
            head.startsWith("{") && text.contains("\"sites\"") -> {
                Parsers.tvBoxDirectUrls(text).mapIndexed { i, (name, url) ->
                    Channel(name, url, group = "TVBox", source = label, rawLine = "TVBox#$i")
                }
            }
            else -> Parsers.parseAny(text, label)
        }
    }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36 IPTV/1.0"
    }
}
