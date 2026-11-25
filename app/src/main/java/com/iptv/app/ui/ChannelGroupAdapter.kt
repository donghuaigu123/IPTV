package com.iptv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.iptv.app.R
import com.iptv.app.core.SourceOrigin
import com.iptv.app.model.Channel
import com.iptv.app.model.ChannelGroup
import com.iptv.app.model.lineStatusText

/**
 * 频道组列表 Adapter：一个条目 = 一个频道（含 N 条线路）。
 * 点击条目/播放按钮 → 播放该频道（从最佳线路开始，失败自动切下一条）。
 */
class ChannelGroupAdapter(
    private val items: MutableList<ChannelGroup>,
    private val onPlay: (ChannelGroup) -> Unit,
    private val onLongClick: (ChannelGroup) -> Unit = {},
    private val onDelete: (ChannelGroup) -> Unit = {}
) : RecyclerView.Adapter<ChannelGroupAdapter.VH>() {

    var darkTheme = false
    /** 是否显示删除按钮（播放器抽屉里隐藏） */
    var showDelete = true
    /** 管理模式：显示勾选框，勾选以 group.key 为单位，删除时作用于整组所有线路 */
    private var manageMode = false
    private val selected = mutableSetOf<String>()

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val group: TextView = v.findViewById(R.id.tvGroup)
        val origin: TextView = v.findViewById(R.id.tvOrigin)
        val url: TextView = v.findViewById(R.id.tvUrl)
        val status: TextView = v.findViewById(R.id.tvStatus)
        val lineCount: TextView = v.findViewById(R.id.tvLineCount)
        val play: ImageButton = v.findViewById(R.id.btnPlay)
        val del: ImageButton = v.findViewById(R.id.btnDelete)
        val keep: CheckBox = v.findViewById(R.id.cbKeep)
        val logoText: TextView = v.findViewById(R.id.tvLogoText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel_group, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = items[position]
        val best = g.best
        holder.name.text = g.name

        // 分组 + 最佳延迟
        holder.group.text = buildString {
            append(g.group)
            if (g.bestLatencyMs > 0) append(" · ${g.bestLatencyMs}ms")
        }

        // 线路数徽章
        if (g.lineCount > 1) {
            holder.lineCount.visibility = View.VISIBLE
            holder.lineCount.text = "${g.lineCount}线路"
        } else {
            holder.lineCount.visibility = View.GONE
        }

        // 来源标签
        val originText = g.originLabel.ifBlank { SourceOrigin.kindName(g.originKind) }
        holder.origin.text = originText
        holder.origin.visibility = if (originText.isBlank() || originText == Channel.ORIGIN_NONE)
            View.GONE else View.VISIBLE

        // 摘要行：显示最佳线路地址 + 可用线路数
        val okCount = g.channels.count { it.isOk }
        holder.url.text = if (g.lineCount > 1) {
            "${best?.url ?: ""}  ·  ${g.lineCount}条线路${if (okCount > 0) "（$okCount 条可用）" else ""}"
        } else {
            best?.url ?: ""
        }

        // 状态徽章
        val ctx = holder.itemView.context
        val (statusText, statusColor, statusBg) = when (g.status) {
            Channel.STATUS_OK -> Triple(
                if (okCount > 0) "${okCount}条可用" else "可用",
                ContextCompat.getColor(ctx, R.color.success), R.drawable.bg_status_ok
            )
            Channel.STATUS_FAIL -> Triple("全部失效", ContextCompat.getColor(ctx, R.color.error), R.drawable.bg_status_fail)
            else -> Triple("未检测", ContextCompat.getColor(ctx, R.color.text_secondary), R.drawable.bg_status_unknown)
        }
        holder.status.text = statusText
        holder.status.setTextColor(statusColor)
        holder.status.setBackgroundResource(statusBg)

        holder.logoText.text = g.name.trim().take(2).ifBlank { "TV" }

        if (darkTheme) {
            val white = ContextCompat.getColor(ctx, android.R.color.white)
            holder.name.setTextColor(white)
            holder.url.setTextColor(white)
        }

        // 管理模式
        holder.keep.visibility = if (manageMode) View.VISIBLE else View.GONE
        holder.play.visibility = if (manageMode) View.GONE else View.VISIBLE
        holder.del.visibility = if (!manageMode && showDelete) View.VISIBLE else View.GONE
        holder.keep.setOnCheckedChangeListener(null)
        holder.keep.isChecked = selected.contains(g.key)
        holder.keep.setOnCheckedChangeListener { _, checked ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                val key = items[pos].key
                if (checked) selected.add(key) else selected.remove(key)
            }
        }

        holder.play.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) onPlay(items[pos])
        }
        holder.del.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) onDelete(items[pos])
        }
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos >= items.size) return@setOnClickListener
            if (manageMode) holder.keep.isChecked = !holder.keep.isChecked
            else onPlay(items[pos])
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) onLongClick(items[pos])
            true
        }
    }

    override fun getItemCount() = items.size

    fun submit(list: List<ChannelGroup>) {
        items.clear(); items.addAll(list)
        val valid = list.map { it.key }.toSet()
        selected.retainAll(valid)
        notifyDataSetChanged()
    }

    fun setManageMode(on: Boolean) {
        manageMode = on
        if (!on) selected.clear()
        notifyDataSetChanged()
    }

    fun selectedCount() = selected.size
    fun selectedGroups(): List<ChannelGroup> = items.filter { selected.contains(it.key) }
    fun unselectedGroups(): List<ChannelGroup> = items.filter { !selected.contains(it.key) }
    fun selectAll() { selected.addAll(items.map { it.key }); notifyDataSetChanged() }
    fun selectNone() { selected.clear(); notifyDataSetChanged() }
    fun invertSelection() {
        val next = items.map { it.key }.filter { !selected.contains(it) }.toSet()
        selected.clear(); selected.addAll(next); notifyDataSetChanged()
    }
}
