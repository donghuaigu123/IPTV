package com.iptv.app.core

import android.content.Context
import com.iptv.app.data.Prefs
import com.iptv.app.model.Channel
import com.iptv.app.model.CollectProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 直播源可用性检测：并发发起 Range 请求，记录延迟、下载速度与响应特征。
 *
 * 稳定性要点（v2.0.1）：
 * - 使用协程版 [Semaphore]+[withPermit]（挂起式、可响应取消），替代阻塞式 acquire，
 *   避免海量协程阻塞在信号量上导致线程耗尽 / 取消后无法退出。
 * - 采用有界并发 worker，而非「每条频道一个 async」，避免几千条频道一次性创建几千个 Deferred。
 * - 探测响应仅读取固定字节并用 [SafeBody] 限制，杜绝异常源超大响应导致 OOM。
 * - 进度计数用 [AtomicInteger]，消除数据竞争；进度回调异常被隔离，不影响检测主流程。
 */
class ChannelChecker(private val context: Context) {

    suspend fun check(
        channels: List<Channel>,
        onProgress: (CollectProgress) -> Unit
    ): List<Channel> = withContext(Dispatchers.IO) {
        if (channels.isEmpty()) return@withContext emptyList()

        val timeoutSec = Prefs.getInt(context, Prefs.KEY_TIMEOUT, 8).coerceIn(3, 30)
        val concurrency = Prefs.getInt(context, Prefs.KEY_CONCURRENCY, 24).coerceIn(4, 32)
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .callTimeout((timeoutSec + 3).toLong(), TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val sem = Semaphore(concurrency)
        val total = channels.size
        val done = AtomicInteger(0)
        val results = arrayOfNulls<Channel>(total)

        // 有界 worker：固定 concurrency 个协程消费索引，避免一次创建 total 个 Deferred
        val workers = (0 until concurrency).map { workerId ->
            async {
                var index = workerId
                while (index < total) {
                    val ch = channels[index]
                    sem.withPermit {
                        val checked = try {
                            checkOne(ch, client)
                        } catch (e: Throwable) {
                            // 单条检测异常绝不能中断整批（含 OOM 之外的 Error 也要兜住线程）
                            ch.copy(status = Channel.STATUS_FAIL, failReason = safeReason(e))
                        }
                        results[index] = checked
                        val d = done.incrementAndGet()
                        if (d % 5 == 0 || d == total) {
                            safeProgress(onProgress, CollectProgress("可用性检测", d, total, "${ch.name} · ${if (checked.isOk) "可用" else "不可用"}"))
                        }
                    }
                    index += concurrency
                }
            }
        }
        workers.awaitAll()

        val okCount = results.count { it?.isOk == true }
        safeProgress(onProgress, CollectProgress("检测完成", total, total, "可用 $okCount / $total"))
        results.toList().filterNotNull()
    }

    private fun checkOne(ch: Channel, client: OkHttpClient): Channel {
        val t0 = System.currentTimeMillis()
        val probeBytes = 8192
        try {
            val url = ch.url.trim()
            if (!url.contains("://")) {
                return ch.copy(status = Channel.STATUS_FAIL, failReason = "无效地址")
            }
            val ua = ch.userAgent.ifBlank { SourceCollector.DEFAULT_UA }
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-$probeBytes")
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .build()
            client.newCall(request).execute().use { resp ->
                val code = resp.code
                val contentType = resp.header("Content-Type") ?: ""
                // 只读探测所需的少量字节，且限制长度，避免异常源拖垮内存
                val bytes = SafeBody.readAscii(resp, probeBytes)
                val latency = System.currentTimeMillis() - t0
                val speed = if (latency > 0) (bytes.length * 1000L / latency) / 1024 else 0

                val ok = code in 200..299 &&
                    (bytes.length > 64 || contentType.contains("mpegurl", true) ||
                     contentType.contains("video", true) || contentType.contains("stream", true) ||
                     url.contains(".m3u8", true))

                return if (ok) {
                    ch.copy(status = Channel.STATUS_OK, latencyMs = latency, speedKBps = speed, failReason = "")
                } else {
                    ch.copy(
                        status = Channel.STATUS_FAIL,
                        latencyMs = latency,
                        failReason = when {
                            code !in 200..299 -> "HTTP $code"
                            bytes.length <= 64 -> "空响应"
                            else -> "无效内容"
                        }
                    )
                }
            }
        } catch (e: Throwable) {
            return ch.copy(
                status = Channel.STATUS_FAIL,
                latencyMs = System.currentTimeMillis() - t0,
                failReason = safeReason(e)
            )
        }
    }

    private fun safeReason(e: Throwable): String = when (e) {
        is IOException -> "连接失败"
        is OutOfMemoryError -> "响应过大"
        else -> e.message?.take(60)?.ifBlank { "检测失败" } ?: "检测失败"
    }

    private fun safeProgress(cb: (CollectProgress) -> Unit, p: CollectProgress) {
        try { cb(p) } catch (_: Throwable) {}
    }
}
