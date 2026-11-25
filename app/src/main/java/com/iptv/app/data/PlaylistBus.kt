package com.iptv.app.data

import com.iptv.app.model.Channel
import com.iptv.app.model.ChannelGroup

/**
 * 进程内播放列表传递总线。
 *
 * 背景：频道列表采集后可达数千条，序列化为 JSON 后往往超过 1MB，
 * 若通过 Intent.putExtra 传递会触发 Binder 事务上限（TransactionTooLargeException），
 * 导致点击播放时直接闪退。这里改为进程内单例持有，Intent 只传一个下标。
 */
object PlaylistBus {
    private var channels: List<Channel> = emptyList()

    fun set(list: List<Channel>) {
        channels = list
    }

    fun get(): List<Channel> = channels

    fun clear() {
        channels = emptyList()
    }

    // ---------------- 频道组播放上下文（多线路） ----------------
    /** 播放器要播放的频道组列表（合并后的频道，每组含 N 条线路） */
    var groups: List<ChannelGroup> = emptyList()
    /** 起始频道组下标 */
    var groupIndex: Int = 0
    /** 起始频道组内起始线路下标（默认 0 = 最佳线路；手动选线路时传具体值） */
    var startLine: Int = 0

    fun setGroups(list: List<ChannelGroup>, groupIndex: Int = 0, startLine: Int = 0) {
        groups = list
        this.groupIndex = groupIndex
        this.startLine = startLine
        // 同步扁平列表，供旧逻辑/回退使用
        channels = list.flatMap { it.channels }
    }

    fun clearGroups() {
        groups = emptyList()
        groupIndex = 0
        startLine = 0
    }

    /** 外部以 m3u/m3u8 链接唤起时，待导入的地址 */
    var pendingImportUrl: String? = null
}
