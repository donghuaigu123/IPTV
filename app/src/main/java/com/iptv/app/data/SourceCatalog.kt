package com.iptv.app.data

import com.iptv.app.model.SourceConfig

/**
 * 内置直播源清单。
 * 参考 HerbertHe/iptv-sources 与 Guovin/iptv-api 的上游聚合清单整理。
 * 这些地址均为公开可直接访问的 m3u / txt 订阅。
 */
object SourceCatalog {

    val builtInSources = listOf(
        SourceConfig("iptv-sources 综合", "https://m3u.ibert.me/", SourceConfig.TYPE_M3U),
        SourceConfig("fanmingming IPv4", "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv4.m3u"),
        SourceConfig("fanmingming IPv6", "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv6.m3u"),
        SourceConfig("YueChan/Live", "https://raw.githubusercontent.com/YueChan/Live/main/IPTV.m3u"),
        SourceConfig("YanG-1989/m3u", "https://raw.githubusercontent.com/YanG-1989/m3u/main/tv/iptv4.m3u"),
        SourceConfig("iptv-org 央视", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/cntv.m3u"),
        SourceConfig("iptv-org 中国", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/china.m3u"),
        SourceConfig("iptv-org 香港", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/hongkong.m3u"),
        SourceConfig("iptv-org 台湾", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/taiwan.m3u"),
        SourceConfig("iptv-org 体育", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/sports.m3u"),
        SourceConfig("iptv-org 电影", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/movies.m3u"),
        SourceConfig("joevess/IPTV", "https://raw.githubusercontent.com/joevess/IPTV/main/iptv.m3u"),
        SourceConfig("qwerttvv 北京IPTV", "https://raw.githubusercontent.com/qwerttvv/Beijing-IPTV/master/iptv.m3u"),
        SourceConfig("cymz6 Hotel", "https://raw.githubusercontent.com/cymz6/AutoIPTV-Hotel/main/iptv.m3u"),
        SourceConfig("diyp 接口", "https://diyp1.112114.xyz/txt", SourceConfig.TYPE_TXT),
        SourceConfig("51zmt 接口", "http://epg.51zmt.top:8000/txt", SourceConfig.TYPE_TXT)
    )

    /** 常见 EPG 数据源，导出 M3U 时可写入 tvg-url */
    val epgSources = listOf(
        "https://epg.pw/xmltv/epg_zh.xml",
        "https://diyp1.112114.xyz/epg.xml"
    )

    /** 频道别名归一化词表：把同一频道的不同写法统一 */
    val aliasMap: Map<String, String> = buildMap {
        val groups = listOf(
            listOf("CCTV1", "CCTV-1", "央视一套", "CCTV1综合", "CCTV 1", "中央1台"),
            listOf("CCTV2", "CCTV-2", "央视二套", "CCTV2财经", "中央2台"),
            listOf("CCTV3", "CCTV-3", "央视三套", "CCTV3综艺", "中央3台"),
            listOf("CCTV4", "CCTV-4", "央视四套", "CCTV4国际", "中央4台"),
            listOf("CCTV5", "CCTV-5", "央视五套", "CCTV5体育", "中央5台"),
            listOf("CCTV5+", "CCTV-5+", "CCTV5plus", "央视体育赛事"),
            listOf("CCTV6", "CCTV-6", "央视六套", "CCTV6电影", "中央6台"),
            listOf("CCTV7", "CCTV-7", "央视七套", "CCTV7国防军事"),
            listOf("CCTV8", "CCTV-8", "央视八套", "CCTV8电视剧"),
            listOf("CCTV9", "CCTV-9", "央视九套", "CCTV9纪录"),
            listOf("CCTV10", "CCTV-10", "央视十套", "CCTV10科教"),
            listOf("CCTV11", "CCTV-11", "央视十一套", "CCTV11戏曲"),
            listOf("CCTV12", "CCTV-12", "央视十二套", "CCTV12社会与法"),
            listOf("CCTV13", "CCTV-13", "央视十三套", "CCTV13新闻"),
            listOf("CCTV14", "CCTV-14", "央视十四套", "CCTV14少儿"),
            listOf("CCTV15", "CCTV-15", "央视十五套", "CCTV15音乐"),
            listOf("湖南卫视", "湖南TV", "HunanTV"),
            listOf("浙江卫视", "浙江TV", "ZhejiangTV"),
            listOf("江苏卫视", "江苏TV", "JiangsuTV"),
            listOf("东方卫视", "上海东方卫视", "DragonTV"),
            listOf("广东卫视", "广东TV", "GuangdongTV"),
            listOf("北京卫视", "北京TV", "BeijingTV"),
            listOf("山东卫视", "山东TV", "ShandongTV"),
            listOf("凤凰卫视", "凤凰卫视中文台", "PhoenixTV")
        )
        groups.forEach { names ->
            val canonical = names.first()
            names.forEach { put(normalizeKey(it), canonical) }
        }
    }

    private fun normalizeKey(s: String): String =
        s.trim().uppercase().replace(Regex("[\\s\\-_·.（）()【】\\[\\]]"), "")

    /** 返回频道的标准名称，用于合并去重 */
    fun canonicalName(raw: String): String {
        val key = normalizeKey(raw)
        return aliasMap[key] ?: key.ifEmpty { raw.trim() }.let { raw.trim() }
    }

    /** 频道分类，用于列表分组与导出分组 */
    fun categoryOf(name: String, group: String): String {
        val n = normalizeKey(name)
        val g = normalizeKey(group)
        return when {
            n.contains("CCTV") || g.contains("央视") || g.contains("CCTV") -> "央视"
            n.contains("卫视") || g.contains("卫视") -> "卫视"
            n.contains("凤凰") -> "港澳台"
            g.contains("港") || n.contains("香港") || g.contains("HK") -> "港澳台"
            g.contains("台") || n.contains("台湾") -> "港澳台"
            n.contains("体育") || g.contains("体育") -> "体育"
            n.contains("电影") || g.contains("电影") -> "电影"
            n.contains("纪录") || g.contains("纪录") -> "纪录"
            n.contains("少儿") || n.contains("卡通") || g.contains("少儿") -> "少儿"
            n.contains("新闻") -> "新闻"
            else -> group.ifBlank { "其他" }
        }
    }
}
