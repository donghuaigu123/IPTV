package com.iptv.app.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.iptv.app.data.Prefs
import com.iptv.app.model.Channel
import com.iptv.app.model.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将频道列表导出为多种通用格式，写入用户选择的目录（SAF）。
 */
class Exporter(private val context: Context) {

    suspend fun export(channels: List<Channel>, format: ExportFormat): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val treeUri = Prefs.getTreeUri(context)
                ?: return@withContext Result.failure(IllegalStateException("尚未选择下载目录"))
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                ?: return@withContext Result.failure(IllegalStateException("目录不可用，请重新选择"))

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val base = "IPTV_${stamp}"
            val fileName = when (format) {
                ExportFormat.TVBOX -> "${base}_tvbox.json"
                else -> "$base.${format.ext}"
            }
            val content = when (format) {
                ExportFormat.M3U, ExportFormat.M3U8 -> buildM3u(channels, format == ExportFormat.M3U8)
                ExportFormat.TXT -> buildTxt(channels)
                ExportFormat.JSON -> buildJson(channels)
                ExportFormat.TVBOX -> buildTvBox(channels)
                ExportFormat.XSPF -> buildXspf(channels)
                ExportFormat.CSV -> buildCsv(channels)
            }

            val existing = tree.findFile(fileName)
            existing?.delete()
            val file = tree.createFile(format.mime, fileName)
                ?: return@withContext Result.failure(IllegalStateException("无法创建文件"))
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return@withContext Result.failure(IllegalStateException("无法写入文件"))

            Result.success(file.uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun buildM3u(channels: List<Channel>, hls: Boolean): String = buildString {
        append("#EXTM3U")
        if (hls) append(" m3u8")
        append(" x-tvg-url=\"https://epg.pw/xmltv/epg_zh.xml\"\n")
        channels.forEach { ch ->
            val logo = ch.logo.ifBlank { "" }
            val id = ch.tvgId.ifBlank { ch.name }
            append("#EXTINF:-1 tvg-id=\"$id\" tvg-name=\"${ch.name}\" tvg-logo=\"$logo\" group-title=\"${ch.group}\",${ch.name}\n")
            append("${ch.url}\n")
        }
    }

    fun buildTxt(channels: List<Channel>): String = buildString {
        append("#IPTV 直播源 TXT 格式\n")
        append("#频道名,地址\n")
        channels.forEach { ch -> append("${ch.name},${ch.url}\n") }
    }

    fun buildJson(channels: List<Channel>): String {
        val data = channels.map {
            mapOf(
                "name" to it.name,
                "url" to it.url,
                "group" to it.group,
                "logo" to it.logo,
                "source" to it.source,
                "status" to it.status,
                "latency_ms" to it.latencyMs,
                "speed_kbps" to it.speedKBps
            )
        }
        return Gson().toJson(mapOf("generated_at" to System.currentTimeMillis(), "count" to data.size, "channels" to data))
    }

    /** 生成 TVBox 可用的订阅配置：把有效频道直接写入 lives，导入后即可播放 */
    fun buildTvBox(channels: List<Channel>): String {
        // 调用方已过滤 FAIL，这里再兜底一次，确保只导出有效源
        val valid = channels.filter { it.status != Channel.STATUS_FAIL }
        val lives = valid.take(2000).map { ch ->
            mapOf(
                "name" to ch.name,
                "url" to ch.url,
                "group" to ch.group.ifBlank { "IPTV" },
                "type" to 0
            )
        }
        val urls = valid.map { it.source }
            .filter { it.contains("://") }
            .distinct()
            .take(30)
            .mapIndexed { i, u ->
                mapOf("name" to "IPTV线路${i + 1}", "url" to u, "type" to 0, "searchable" to 0)
            }
        return Gson().toJson(
            mapOf(
                "name" to "IPTV 聚合（有效源）",
                "sites" to urls,
                "lives" to lives
            )
        )
    }

    fun buildXspf(channels: List<Channel>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\">\n<title>IPTV</title>\n<trackList>\n")
        channels.forEach { ch ->
            append("  <track>\n")
            append("    <title>${escapeXml(ch.name)}</title>\n")
            append("    <album>${escapeXml(ch.group)}</album>\n")
            append("    <location>${escapeXml(ch.url)}</location>\n")
            append("  </track>\n")
        }
        append("</trackList>\n</playlist>\n")
    }

    fun buildCsv(channels: List<Channel>): String = buildString {
        append("频道名称,分组,地址,状态,延迟(ms),速度(KB/s),来源\n")
        channels.forEach { ch ->
            val row = listOf(ch.name, ch.group, ch.url,
                if (ch.isOk) "可用" else if (ch.status == Channel.STATUS_FAIL) "不可用" else "未检测",
                ch.latencyMs.toString(), ch.speedKBps.toString(), ch.source)
            append(row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }).append("\n")
        }
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
