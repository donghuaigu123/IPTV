package com.iptv.app.core

import okhttp3.Response
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
        val out = StringBuilder()
        var total = 0
        var truncated = false
        try {
            body.byteStream().use { stream ->
                val buf = ByteArray(8192)
                while (true) {
                    val want = minOf(buf.size, maxBytes - total)
                    if (want <= 0) { truncated = true; break }
                    val n = stream.read(buf, 0, want)
                    if (n < 0) break
                    if (n == 0) break
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                    total += n
                }
                // 探测是否还有更多数据（只读 1 字节，不真正加载全文）
                if (!truncated && total >= maxBytes) {
                    truncated = try { stream.read() >= 0 } catch (_: Throwable) { false }
                }
            }
        } catch (e: IOException) {
            // 读取中断：返回已读部分，交由上层按内容判定
        } catch (_: OutOfMemoryError) {
            return Result("", true)
        }
        return Result(out.toString(), truncated)
    }

    /** 以 Latin-1 读取探测样本（用于判断内容特征的 ASCII 头部） */
    fun readAscii(resp: Response, maxBytes: Int): String {
        val body = resp.body ?: return ""
        val out = StringBuilder()
        var total = 0
        try {
            body.byteStream().use { stream ->
                val buf = ByteArray(8192)
                while (total < maxBytes) {
                    val want = minOf(buf.size, maxBytes - total)
                    val n = stream.read(buf, 0, want)
                    if (n <= 0) break
                    out.append(String(buf, 0, n, Charsets.ISO_8859_1))
                    total += n
                }
            }
        } catch (_: Throwable) {}
        return out.toString()
    }

    data class Result(val text: String, val truncated: Boolean)
}
