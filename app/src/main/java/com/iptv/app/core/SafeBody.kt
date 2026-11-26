package com.iptv.app.core

import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 安全的响应体读取工具。
 *
 * 背景：直播源采集/检测面对大量不可控的第三方地址，个别源可能返回几十 MB 甚至更大的
 * 响应。直接调用 ResponseBody.string() 会把整个响应一次性读入内存，是采集/检测阶段
 * OOM 闪退的主要根因。本工具统一限制最大读取字节数，超限即停止并标记截断。
 */
object SafeBody {

    /** 默认最大读取 2MB，足够解析播放列表与探测样本 */
    const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024

    /**
     * 以 UTF-8 读取响应体文本，最多 [maxBytes] 字节。
     * @return 文本与是否被截断
     */
    fun readText(resp: Response, maxBytes: Int = DEFAULT_MAX_BYTES): Result {
        val body = resp.body ?: return Result("", false)
        // 先按原始字节收集，最后一次性解码：
        // 分块 String(buf,...) 会在多字节字符恰好跨块时被截断成乱码，这是频道名乱码的元凶之一。
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192))
        var truncated = false
        try {
            body.byteStream().use { stream ->
                val buf = ByteArray(8192)
                while (true) {
                    val want = minOf(buf.size, maxBytes - out.size())
                    if (want <= 0) { truncated = true; break }
                    val n = stream.read(buf, 0, want)
                    if (n < 0) break
                    if (n == 0) break
                    out.write(buf, 0, n)
                }
                // 探测是否还有更多数据（只读 1 字节，不真正加载全文）
                if (!truncated && out.size() >= maxBytes) {
                    truncated = try { stream.read() >= 0 } catch (_: Throwable) { false }
                }
            }
        } catch (e: IOException) {
            // 读取中断：返回已读部分，交由上层按内容判定
        } catch (_: OutOfMemoryError) {
            return Result("", true)
        }
        val text = try { TextEncoding.decode(out.toByteArray()) } catch (_: Throwable) { String(out.toByteArray(), Charsets.UTF_8) }
        return Result(text, truncated)
    }

    /**
     * 读取探测样本（用于判断内容特征的 ASCII 头部）。
     * v2.0.2：改为与正文一致的智能解码 —— 该样本在样本足够大时会被直接当作正文解析，
     * 若继续用 Latin-1 解码，中文频道名会变成「GOé å」乱码。
     */
    fun readAscii(resp: Response, maxBytes: Int): String {
        val body = resp.body ?: return ""
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192))
        try {
            body.byteStream().use { stream ->
                val buf = ByteArray(8192)
                while (out.size() < maxBytes) {
                    val want = minOf(buf.size, maxBytes - out.size())
                    val n = stream.read(buf, 0, want)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
        } catch (_: Throwable) {}
        return try { TextEncoding.decode(out.toByteArray()) } catch (_: Throwable) { "" }
    }

    data class Result(val text: String, val truncated: Boolean)
}
