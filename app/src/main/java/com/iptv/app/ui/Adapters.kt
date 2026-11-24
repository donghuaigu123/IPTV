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
        holder.status.text = when (ch.status) {
            Channel.STATUS_OK -> "可用"
            Channel.STATUS_FAIL -> "不可用"
            else -> "未检测"
        }
        val c = when (ch.status) {
            Channel.STATUS_OK -> R.color.green
            Channel.STATUS_FAIL -> R.color.red
            else -> R.color.text_secondary
        }
        holder.status.setTextColor(ContextCompat.getColor(holder.itemView.context, c))
        if (darkTheme) {
            val white = ContextCompat.getColor(holder.itemView.context, android.R.color.white)
            holder.name.setTextColor(white)
            holder.group.setTextColor(white)
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
        holder.cb.isChecked = s.enabled
        holder.cb.setOnCheckedChangeListener { _, b ->
            items[holder.adapterPosition] = s.copy(enabled = b)
            onChanged()
        }
        holder.del.setOnClickListener { onDelete(s) }
        holder.edit.setOnClickListener { onEdit(s) }
    }

    override fun getItemCount() = items.size

    fun submit(list: List<SourceConfig>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }
}
