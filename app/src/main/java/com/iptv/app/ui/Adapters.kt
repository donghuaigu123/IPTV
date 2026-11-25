package com.iptv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.core.content.ContextCompat
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.iptv.app.R
import com.iptv.app.model.Channel
import com.iptv.app.model.SourceConfig

class ChannelAdapter(
    private val items: MutableList<Channel>,
    private val onPlay: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit = {}
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var darkTheme = false

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val group: TextView = v.findViewById(R.id.tvGroup)
        val url: TextView = v.findViewById(R.id.tvUrl)
        val status: TextView = v.findViewById(R.id.tvStatus)
        val play: ImageButton = v.findViewById(R.id.btnPlay)
        val logoText: TextView? = v.findViewById(R.id.tvLogoText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        holder.group.text = buildString {
            append(ch.group)
            if (ch.latencyMs > 0) append(" · ${ch.latencyMs}ms")
            if (ch.speedKBps > 0) append(" · ${ch.speedKBps}KB/s")
        }
        holder.url.text = ch.url
        val ctx = holder.itemView.context
        // 状态徽章
        val (statusText, statusColor, statusBg) = when (ch.status) {
            Channel.STATUS_OK -> Triple(
                if (ch.latencyMs > 0) "可用 ${ch.latencyMs}ms" else "可用",
                ContextCompat.getColor(ctx, R.color.success), R.drawable.bg_status_ok
            )
            Channel.STATUS_FAIL -> Triple("失效", ContextCompat.getColor(ctx, R.color.error), R.drawable.bg_status_fail)
            else -> Triple("未检测", ContextCompat.getColor(ctx, R.color.text_secondary), R.drawable.bg_status_unknown)
        }
        holder.status.text = statusText
        holder.status.setTextColor(statusColor)
        holder.status.setBackgroundResource(statusBg)
        // 台标首字
        holder.logoText?.text = ch.name.trim().take(2).ifBlank { "TV" }
        // 深色主题（播放器抽屉）下调整文字
        if (darkTheme) {
            val white = ContextCompat.getColor(ctx, android.R.color.white)
            holder.name.setTextColor(white)
            holder.url.setTextColor(white)
        }
        holder.play.setOnClickListener { onPlay(ch) }
        holder.itemView.setOnClickListener { onPlay(ch) }
        holder.itemView.setOnLongClickListener { onLongClick(ch); true }
    }

    override fun getItemCount() = items.size

    fun submit(list: List<Channel>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }
}

class SourceAdapter(
    private val items: MutableList<SourceConfig>,
    private val onChanged: () -> Unit,
    private val onDelete: (SourceConfig) -> Unit,
    private val onEdit: (SourceConfig) -> Unit
) : RecyclerView.Adapter<SourceAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cb: CheckBox = v.findViewById(R.id.cbEnabled)
        val name: TextView = v.findViewById(R.id.tvName)
        val url: TextView = v.findViewById(R.id.tvUrl)
        val del: ImageButton = v.findViewById(R.id.btnDelete)
        val edit: ImageButton = v.findViewById(R.id.btnEdit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.name.text = s.name
        holder.url.text = s.url
        // 先移除旧监听，避免复用时 setChecked 触发上一次绑定的监听
        holder.cb.setOnCheckedChangeListener(null)
        holder.cb.isChecked = s.enabled
        holder.cb.setOnCheckedChangeListener { _, b ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                items[pos] = s.copy(enabled = b)
                onChanged()
            }
        }
        holder.del.setOnClickListener { onDelete(s) }
        holder.edit.setOnClickListener { onEdit(s) }
    }

    override fun getItemCount() = items.size

    fun submit(list: List<SourceConfig>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }
}
