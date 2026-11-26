package com.iptv.app.core

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地直播流代理：把电视无法直接播放的直播源，经手机（带正确 UA / Referer）转发给电视。
 *
 * 关键点：
 * 1. m3u8 会被改写——主播放列表、子列表、分片、AES Key、EXT-X-MAP 的 URL 全部重写为代理地址，
 *    电视的每一次分片请求都回到手机，从而绕开防盗链与 UA 限制。
 * 2. 流式透传，不缓冲整段直播（直播为无限流）。
 * 3. 每次切台生成独立 token 路径，避免电视/接收端缓存旧的播放列表。
 */
class StreamProxy {

    private data class Config(val url: String, val ua: String, val referer: String)

    private val configs = ConcurrentHashMap<String, Config>()
    @Volatile private var currentToken: String = ""
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private val workers = Collections.newSetFromMap(ConcurrentHashMap<Thread, Boolean>())

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // 直播长连接不设读超时
        .writeTimeout(0, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile var port: Int = 0
        private set

    /** 已在后台线程启动，返回是否成功 */
    fun start(): Boolean = try {
        val socket = ServerSocket(0, 64, InetAddress.getByName("0.0.0.0"))
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        acceptThread = Thread({
            while (running.get()) {
                try {
                    val client = socket.accept() ?: break
                    val worker = Thread({ handle(client) }).apply { isDaemon = true }
                    workers.add(worker)
                    worker.start()
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }, "StreamProxy-Accept").apply { isDaemon = true; start() }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 配置一路直播流并生成专属播放地址。
     * @return 供电视访问的完整 http URL
     */
    fun configure(url: String, ua: String): String? {
        val ip = DlnaManager.getLocalIp() ?: return null
        val token = UUID.randomUUID().toString().take(8)
        val referer = try {
            val u = URL(url)
            "${u.protocol}://${u.host}/"
        } catch (_: Exception) { "" }
        // 释放上一路配置，避免切台后旧 token 累积
        if (currentToken.isNotBlank()) configs.remove(currentToken)
        configs[token] = Config(url, ua.ifBlank { "Mozilla/5.0" }, referer)
        currentToken = token
        return "http://$ip:$port/s/$token/live"
    }

    /** 释放一路流配置 */
    fun release(token: String) { configs.remove(token) }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        configs.clear()
        workers.forEach { try { it.interrupt() } catch (_: Exception) {} }
        workers.clear()
    }

    // -------------------- HTTP 处理 --------------------

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 20000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { writeError(out, 400, "Bad Request"); return }
            val pathAndQuery = parts[1]
            val qIndex = pathAndQuery.indexOf('?')
            val path = if (qIndex >= 0) pathAndQuery.substring(0, qIndex) else pathAndQuery
            val query = if (qIndex >= 0) pathAndQuery.substring(qIndex + 1) else ""

            when {
                path.startsWith("/s/") && path.endsWith("/live") -> {
                    val token = path.removePrefix("/s/").removeSuffix("/live")
                    val cfg = configs[token] ?: configs[currentToken.takeIf { it.isNotBlank() }]
                    if (cfg == null) { writeError(out, 404, "Stream not configured"); return }
                    proxy(cfg, cfg.url, out, rewriteM3u8 = true)
                }
                path == "/raw" -> {
                    val b64 = param(query, "u")
                    val url = decode(b64)
                    if (url.isNullOrBlank()) { writeError(out, 400, "Missing url"); return }
                    val cfg = configs[currentToken]
                    proxy(cfg ?: Config(url, "Mozilla/5.0", ""), url, out, rewriteM3u8 = true)
                }
                else -> writeError(out, 404, "Not Found")
            }
        } catch (_: Exception) {
            // 客户端断开属正常
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** 拉取真实 URL 并转发；若是 m3u8 则改写其中所有子 URL 为代理地址 */
    private fun proxy(cfg: Config, url: String, out: java.io.OutputStream, rewriteM3u8: Boolean) {
        val request = Request.Builder().url(url)
            .header("User-Agent", cfg.ua)
            .apply { if (cfg.referer.isNotBlank()) header("Referer", cfg.referer) }
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            writeError(out, 502, "Upstream error: ${e.message?.take(60)}")
            return
        }
        response.use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null) {
                writeError(out, resp.code, "Upstream ${resp.code}")
                return
            }
            val contentType = resp.header("Content-Type") ?: guessContentType(url)
            val isM3u8 = rewriteM3u8 && (
                url.contains(".m3u8", true) ||
                    contentType.contains("mpegurl", true) ||
                    contentType.contains("m3u8", true)
                )

            if (isM3u8) {
                val text = try {
                    body.string()
                } catch (_: Exception) {
                    writeError(out, 502, "Read playlist failed"); return
                }
                val rewritten = rewritePlaylist(text, url)
                writeHeaders(out, 200, "application/vnd.apple.mpegurl; charset=utf-8", rewritten.toByteArray(Charsets.UTF_8).size)
                out.write(rewritten.toByteArray(Charsets.UTF_8))
                out.flush()
            } else {
                writeHeaders(out, 200, contentType, -1)
                val input = body.byteStream()
                val buffer = ByteArray(16 * 1024)
                try {
                    while (running.get()) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) {
                            out.write(buffer, 0, n)
                            out.flush()
                        }
                    }
                } catch (_: Exception) {
                    // 电视端停止拉流，正常结束
                } finally {
                    try { input.close() } catch (_: Exception) {}
                }
            }
        }
    }

    /** 把 m3u8 中的相对/绝对 URL 全部改写成走本代理的地址 */
    private fun rewritePlaylist(text: String, playlistUrl: String): String {
        val base = try {
            val u = URL(playlistUrl)
            "${u.protocol}://${u.host}${if (u.port > 0) ":${u.port}" else ""}${u.path.substringBeforeLast('/')}/"
        } catch (_: Exception) { "" }
        val result = StringBuilder()
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> result.append(line)
                trimmed.startsWith("#") -> result.append(rewriteTagWithBase(line, base))
                else -> {
                    val absolute = try {
                        URL(URL(base), trimmed).toString()
                    } catch (_: Exception) {
                        if (trimmed.startsWith("http")) trimmed else base + trimmed
                    }
                    result.append(proxyUrl(absolute))
                }
            }
            result.append('\n')
        }
        return result.toString()
    }

    /** 生成代理地址 */
    private fun proxyUrl(url: String): String {
        val ip = DlnaManager.getLocalIp() ?: return url
        val encoded = Base64.encodeToString(url.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        return "http://$ip:$port/raw?u=$encoded"
    }

    /** 修正标签内相对 URI：在 rewritePlaylist 里对 base 做二次处理 */
    private fun rewriteTagWithBase(line: String, base: String): String {
        if (!line.contains("URI=", true)) return line
        val regex = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        return regex.replace(line) { match ->
            val raw = match.groupValues[1]
            val absolute = try {
                URL(URL(base), raw).toString()
            } catch (_: Exception) {
                if (raw.startsWith("http")) raw else base + raw
            }
            "URI=\"${proxyUrl(absolute)}\""
        }
    }

    private fun guessContentType(url: String): String = when {
        url.contains(".m3u8", true) -> "application/vnd.apple.mpegurl"
        url.contains(".mp4", true) -> "video/mp4"
        url.contains(".ts", true) -> "video/mp2t"
        else -> "application/octet-stream"
    }

    private fun writeHeaders(out: java.io.OutputStream, code: Int, contentType: String, length: Int) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Cache-Control: no-cache\r\n")
        sb.append("Connection: close\r\n")
        if (length >= 0) sb.append("Content-Length: $length\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun writeError(out: java.io.OutputStream, code: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        writeHeaders(out, code, "text/plain; charset=utf-8", body.size)
        try {
            out.write(body)
            out.flush()
        } catch (_: Exception) {}
    }

    private fun param(query: String, name: String): String? {
        query.split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0 && pair.substring(0, idx) == name) return pair.substring(idx + 1)
        }
        return null
    }

    private fun decode(b64: String?): String? = try {
        if (b64.isNullOrBlank()) null
        else String(Base64.decode(b64, Base64.URL_SAFE), Charsets.UTF_8)
    } catch (_: Exception) { null }

    companion object {
        /** 供 UI 在后台线程安全启动 */
        suspend fun startOnIo(proxy: StreamProxy): Boolean = withContext(Dispatchers.IO) { proxy.start() }
    }
}
