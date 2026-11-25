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
    val userAgent: String = "",
    /** 来源大类：网络采集源 / 网络直播源 / TVBox源 */
    val originKind: String = "",
    /** 来源实例标识：区分同一来源类型下的多个订阅（如多个 TVBox 配置） */
    val originKey: String = "",
    val originLabel: String = "",
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

        // 来源大类
        const val ORIGIN_COLLECT = "collect"   // 网络采集源（采集页抓取）
        const val ORIGIN_LIVE = "live"         // 网络直播源（直播源导入）
        const val ORIGIN_TVBOX = "tvbox"       // TVBox 源（TVBox 导入）

        const val ORIGIN_NONE = "其他"
    }

    val isOk get() = status == STATUS_OK
    /** 频道唯一标识：名称+地址，用于删除/收藏/去重的稳定判断 */
    val identity get() = "$name\u0000$url"
}

/**
 * 频道组：同一来源下、名称规范化后相同的若干频道（线路）聚合而成。
 * 底层存储仍是扁平的 [Channel] 列表，本模型只在展示层与播放层使用，
 * 从而不影响采集、检测、导出、回收站等既有逻辑。
 */
data class ChannelGroup(
    /** 合并键：来源实例 + 规范化频道名 */
    val key: String,
    /** 显示名称（取组内出现频次最高/首次出现的规范写法） */
    val name: String,
    val group: String = "",
    val logo: String = "",
    val epg: String = "",
    val tvgId: String = "",
    val originKind: String = "",
    val originKey: String = "",
    val originLabel: String = "",
    /** 组内全部线路，已按可用性/延迟排序，索引 0 为最佳线路 */
    val channels: List<Channel> = emptyList()
) {
    val lineCount: Int get() = channels.size
    /** 最佳线路（可用性最高、延迟最低） */
    val best: Channel? get() = channels.firstOrNull()
    /** 组是否至少有一条可用线路 */
    val hasOk: Boolean get() = channels.any { it.isOk }
    /** 组状态：任一线条可用即视为可用 */
    val status: Int
        get() = if (channels.any { it.isOk }) Channel.STATUS_OK
                else if (channels.all { it.status == Channel.STATUS_FAIL }) Channel.STATUS_FAIL
                else Channel.STATUS_UNKNOWN
    /** 最小延迟（仅统计可用/未检测线路） */
    val bestLatencyMs: Long get() = channels.filter { it.status != Channel.STATUS_FAIL }.minOfOrNull { it.latencyMs } ?: 0L
}

/** 播放器内分组的线路状态文本，复用统一定义避免多处硬编码 */
fun Channel.lineStatusText(): String = when (status) {
    Channel.STATUS_OK -> if (latencyMs > 0) "可用 ${latencyMs}ms" else "可用"
    Channel.STATUS_FAIL -> "失效"
    else -> "未检测"
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
        const val TYPE_TVBOX = "tvbox"
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
    TVBOX("json", "TVBox 配置", "application/json"),
    XSPF("xspf", "XSPF 播放列表", "application/xspf+xml"),
    CSV("csv", "CSV 表格", "text/csv")
}

/** TVBox 配置中的一条直播订阅（url 可能为直接流地址，也可能是需展开的订阅列表） */
data class TvBoxLive(
    val name: String,
    val url: String,
    val ua: String = "",
    val logo: String = "",
    val epg: String = "",
    val group: String = ""
)

/** TVBox 站点配置 */
data class TvBoxSite(
    val name: String,
    val url: String,
    val type: Int = 0,
    val api: String = "",
    val searchable: Int = 0,
    val categories: List<String> = emptyList()
)
