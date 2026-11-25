package com.iptv.app.model

/** 一个直播频道 / 播放地址 */
data class Channel(
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String = "",
    val epg: String = "",
    val source: String = "",
    val tvgId: String = "",
    val rawLine: String = "",
    var status: Int = STATUS_UNKNOWN,
    var latencyMs: Long = 0,
    var speedKBps: Long = 0,
    var resolution: String = "",
    var failReason: String = ""
) {
    companion object {
        const val STATUS_UNKNOWN = 0
        const val STATUS_OK = 1
        const val STATUS_FAIL = 2
    }

    val isOk get() = status == STATUS_OK
}

/** 内置 / 用户自定义的订阅源 */
data class SourceConfig(
    val name: String,
    val url: String,
    val type: String = TYPE_M3U,
    val enabled: Boolean = true,
    val userAgent: String = "",
    val builtIn: Boolean = true
) {
    companion object {
        const val TYPE_M3U = "m3u"
        const val TYPE_TXT = "txt"
        const val TYPE_JSON = "json"
    }
}

/** 采集进度与统计 */
data class CollectProgress(
    val stage: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val message: String = ""
)

/** 导出格式 */
enum class ExportFormat(val ext: String, val label: String, val mime: String) {
    M3U("m3u", "M3U 播放列表", "application/vnd.apple.mpegurl"),
    M3U8("m3u8", "M3U8 播放列表", "application/vnd.apple.mpegurl"),
    TXT("txt", "TXT 通用格式", "text/plain"),
    JSON("json", "JSON 数据", "application/json"),
    XSPF("xspf", "XSPF 播放列表", "application/xspf+xml"),
    CSV("csv", "CSV 表格", "text/csv")
}
