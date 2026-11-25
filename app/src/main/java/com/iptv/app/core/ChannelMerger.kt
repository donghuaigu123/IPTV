package com.iptv.app.core

import com.iptv.app.data.SourceCatalog
import com.iptv.app.model.Channel
import com.iptv.app.model.ChannelGroup

/**
 * 频道合并器：把扁平的频道（线路）列表按「同一来源实例 + 规范化频道名」聚合为频道组。
 *
 * - 同一数据来源下名称相同的多个频道会合并成一个频道组，组内的每条地址作为一条「线路」。
 * - 不同来源（不同的 originKind / originKey）的同名频道不会互相合并，保持分来源显示语义。
 * - 组内线路排序：可用优先 → 延迟低优先 → 失效靠后，确保索引 0 是体验最好的线路，
 *   播放器可据此实现「失败自动切下一条」。
 *
 * 底层存储、采集、检测、导出均不感知本类，仅在展示层（LibraryFragment）与播放层
 * （PlayerActivity）调用，改动风险最低。
 */
object ChannelMerger {

    /**
     * 合并频道列表为频道组。
     * @param channels 扁平频道列表
     * @param mergeSameSource 为 true 时按来源隔离合并（推荐）；为 false 时忽略来源，同名全合并
     */
    fun merge(channels: List<Channel>, mergeSameSource: Boolean = true): List<ChannelGroup> {
        if (channels.isEmpty()) return emptyList()

        val buckets = LinkedHashMap<String, MutableList<Channel>>()
        channels.forEach { ch ->
            if (ch.url.isBlank()) return@forEach
            val canonical = SourceCatalog.canonicalName(ch.name)
            val scope = if (mergeSameSource) "${ch.originKind}|${ch.originKey}" else "all"
            val key = "$scope\u0000$canonical"
            val list = buckets.getOrPut(key) { mutableListOf() }
            // 完全相同地址只保留一条
            if (list.none { it.url == ch.url }) list += ch
        }

        return buckets.map { (key, rawList) ->
            val sorted = rawList.sortedWith(
                compareBy<Channel> { it.status != Channel.STATUS_OK } // 可用(OK)排前
                    .thenBy { if (it.status == Channel.STATUS_FAIL) 1 else 0 } // 未检测优于失效
                    .thenBy { if (it.latencyMs > 0) it.latencyMs else Long.MAX_VALUE }
                    .thenBy { it.url }
            )
            val first = sorted.first()
            // 显示名称：优先取出现次数最多的原始写法，其次取第一条
            val displayName = sorted.groupingBy { it.name.trim() }.eachCount()
                .maxByOrNull { it.value }?.key?.ifBlank { first.name }
                ?: first.name
            ChannelGroup(
                key = key,
                name = displayName,
                group = sorted.firstNotNullOfOrNull { it.group.takeIf { g -> g.isNotBlank() } } ?: "",
                logo = sorted.firstNotNullOfOrNull { it.logo.takeIf { l -> l.isNotBlank() } } ?: "",
                epg = sorted.firstNotNullOfOrNull { it.epg.takeIf { e -> e.isNotBlank() } } ?: "",
                tvgId = sorted.firstNotNullOfOrNull { it.tvgId.takeIf { t -> t.isNotBlank() } } ?: "",
                originKind = first.originKind,
                originKey = first.originKey,
                originLabel = first.originLabel,
                channels = sorted
            )
        }
    }

    /**
     * 把频道组按来源维度统计，供来源 chip 使用。
     * 返回 Map<"kind|key", 组数>。
     */
    fun countByOrigin(groups: List<ChannelGroup>): Map<String, Int> =
        groups.groupingBy { "${it.originKind}|${it.originKey.ifBlank { it.originKind }}" }.eachCount()
}
