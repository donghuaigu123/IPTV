package com.iptv.app.core

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * DLNA / UPnP 投屏核心（不依赖 Google Play Services，兼容国内主流智能电视/机顶盒）。
 *
 * 流程：SSDP M-SEARCH 发现局域网 MediaRenderer -> 拉取设备描述 XML -> 解析出
 * AVTransport / RenderingControl 的 controlURL -> 用 SOAP 控制播放。
 *
 * 所有方法均为 suspend 并在 IO 线程执行，调用方需使用生命周期感知的协程。
 */
object DlnaManager {

    const val AV_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RC_SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"

    data class DlnaDevice(
        val name: String,
        val location: String,
        val baseUrl: String,
        val avControlUrl: String,
        val rcControlUrl: String,
        val udn: String = ""
    )

    private data class Description(
        val friendlyName: String,
        val udn: String,
        val avControl: String?,
        val rcControl: String?
    )

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    /** 发现局域网内的 DLNA 播放设备 */
    suspend fun discover(context: Context, timeoutMs: Int = 3200): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            // 组播锁：部分手机不加锁收不到 SSDP 应答
            var lock: WifiManager.MulticastLock? = null
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                lock = try {
                    val l = wm?.createMulticastLock("iptv_cast")
                    l?.acquire()
                    l
                } catch (_: Exception) { null }

                val socket = DatagramSocket().apply { broadcast = true; soTimeout = 900 }
                val request = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: $SEARCH_TARGET\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.UTF_8)
                val target = InetAddress.getByName(SSDP_ADDR)
                val packet = DatagramPacket(request, request.size, target, SSDP_PORT)
                try { socket.send(packet); socket.send(packet) } catch (_: Exception) {}

                val locations = LinkedHashSet<String>()
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val response = DatagramPacket(buf, buf.size)
                        socket.receive(response)
                        val text = String(response.data, 0, response.length, Charsets.UTF_8)
                        val location = headerValue(text, "LOCATION")
                        if (!location.isNullOrBlank()) locations.add(location)
                    } catch (_: SocketTimeoutException) {
                        // 继续等待，直到超时
                    } catch (_: Exception) {}
                }
                try { socket.close() } catch (_: Exception) {}

                // 并发拉取并解析设备描述
                val devices = Collections.synchronizedList(mutableListOf<DlnaDevice>())
                locations.map { loc ->
                    async(Dispatchers.IO) {
                        val desc = fetchDescription(loc) ?: return@async
                        if (desc.avControl.isNullOrBlank()) return@async // 不是可播放的渲染器
                        val base = baseOf(loc)
                        devices.add(
                            DlnaDevice(
                                name = desc.friendlyName.ifBlank { "DLNA 设备" },
                                location = loc,
                                baseUrl = base,
                                avControlUrl = absoluteUrl(base, desc.avControl),
                                rcControlUrl = desc.rcControl?.let { absoluteUrl(base, it) }
                                    ?: absoluteUrl(base, desc.avControl),
                                udn = desc.udn
                            )
                        )
                    }
                }.awaitAll()

                devices.distinctBy { it.udn.ifBlank { it.location } }
            } finally {
                try { lock?.release() } catch (_: Exception) {}
            }
        }

    private fun headerValue(response: String, name: String): String? {
        response.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals(name, ignoreCase = true)) {
                return line.substring(idx + 1).trim()
            }
        }
        return null
    }

    private fun fetchDescription(location: String): Description? = try {
        http.newCall(Request.Builder().url(location).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            parseDescription(body)
        }
    } catch (_: Exception) {
        null
    }

    private fun parseDescription(xml: String): Description {
        var friendlyName = ""
        var udn = ""
        var av: String? = null
        var rc: String? = null
        var serviceType = ""
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "friendlyName" -> if (friendlyName.isBlank()) friendlyName = parser.nextText().trim()
                        "UDN" -> if (udn.isBlank()) udn = parser.nextText().trim()
                        "serviceType" -> serviceType = parser.nextText().trim()
                        "controlURL" -> {
                            val url = parser.nextText().trim()
                            when {
                                serviceType.contains("AVTransport", true) -> if (av.isNullOrBlank()) av = url
                                serviceType.contains("RenderingControl", true) -> if (rc.isNullOrBlank()) rc = url
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {}
        return Description(friendlyName, udn, av, rc)
    }

    private fun baseOf(location: String): String = try {
        val u = java.net.URL(location)
        val port = if (u.port > 0) ":${u.port}" else ""
        "${u.protocol}://${u.host}$port"
    } catch (_: Exception) {
        location
    }

    private fun absoluteUrl(base: String, url: String): String {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
        return if (url.startsWith("/")) base + url else "$base/$url"
    }

    // -------------------- SOAP 控制 --------------------

    private fun soap(
        device: DlnaDevice,
        service: String,
        controlUrl: String,
        action: String,
        params: String
    ): Boolean {
        val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$service\">$params</u:$action></s:Body></s:Envelope>"
        return try {
            val request = Request.Builder()
                .url(controlUrl)
                .header("SOAPACTION", "\"$service#$action\"")
                .header("Content-Type", "text/xml; charset=utf-8")
                .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /** 设置播放地址（不自动播放） */
    suspend fun setUrl(device: DlnaDevice, url: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val meta = didl(title)
        soap(
            device, AV_SERVICE, device.avControlUrl, "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${escapeXml(url)}</CurrentURI>" +
                "<CurrentURIMetaData>${escapeXml(meta)}</CurrentURIMetaData>"
        )
    }

    suspend fun play(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        soap(device, AV_SERVICE, device.avControlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    suspend fun pause(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        soap(device, AV_SERVICE, device.avControlUrl, "Pause", "<InstanceID>0</InstanceID>")
    }

    suspend fun stop(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        soap(device, AV_SERVICE, device.avControlUrl, "Stop", "<InstanceID>0</InstanceID>")
    }

    /** 设置电视端音量 0-100 */
    suspend fun setVolume(device: DlnaDevice, volume: Int): Boolean = withContext(Dispatchers.IO) {
        val v = volume.coerceIn(0, 100)
        soap(
            device, RC_SERVICE, device.rcControlUrl, "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$v</DesiredVolume>"
        )
    }

    /** 一步到位：设置地址并播放 */
    suspend fun playUrl(device: DlnaDevice, url: String, title: String): Boolean = withContext(Dispatchers.IO) {
        if (!setUrl(device, url, title)) return@withContext false
        play(device)
    }

    private fun didl(title: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"0\">" +
            "<dc:title>${escapeXml(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "</item></DIDL-Lite>"

    private fun escapeXml(value: String): String = buildString {
        for (c in value) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '\"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    /** 获取本机局域网 IPv4 地址（供电视回连流代理） */
    fun getLocalIp(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
