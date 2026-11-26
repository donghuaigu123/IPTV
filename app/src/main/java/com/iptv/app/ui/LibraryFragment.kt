package com.iptv.app.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.iptv.app.R
import com.iptv.app.core.ChannelMerger
import com.iptv.app.core.SourceCollector
import com.iptv.app.core.SourceOrigin
import com.iptv.app.core.TvBoxImporter
import com.iptv.app.data.PlaylistBus
import com.iptv.app.data.Prefs
import com.iptv.app.model.Channel
import com.iptv.app.model.ChannelGroup
import com.iptv.app.model.lineStatusText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class LibraryFragment : Fragment() {

    private lateinit var etSearch: EditText
    private lateinit var recycler: RecyclerView
    private lateinit var chipGroups: ChipGroup
    private lateinit var chipOrigins: ChipGroup
    private lateinit var adapter: ChannelGroupAdapter
    private lateinit var emptyState: View
    private var allChannels: List<Channel> = emptyList()
    /** 合并后的频道组（同一来源下同名频道聚合为多线路） */
    private var allGroups: List<ChannelGroup> = emptyList()
    private var visibleGroups: List<ChannelGroup> = emptyList()
    private var currentGroup = "全部"
    private var currentOrigin = "all"   // all 或 SourceOrigin.OriginGroup.id
    private var manageMode = false

    /** 视图是否存活，异步回调中据此判断是否可安全刷新 UI */
    private val viewAlive: Boolean get() = isAdded && view != null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_library, container, false)
        etSearch = v.findViewById(R.id.etSearch)
        recycler = v.findViewById(R.id.recyclerChannels)
        chipGroups = v.findViewById(R.id.chipGroups)
        chipOrigins = v.findViewById(R.id.chipOrigins)
        emptyState = v.findViewById(R.id.emptyState)

        adapter = ChannelGroupAdapter(
            mutableListOf(),
            onPlay = { g ->
                val idx = visibleGroups.indexOfFirst { it.key == g.key }
                openPlayerGroup(visibleGroups, if (idx >= 0) idx else 0)
            },
            onLongClick = { g -> showChannelMenu(g) },
            onDelete = { g -> confirmDelete(g) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filter() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        v.findViewById<MaterialButton>(R.id.btnImport).setOnClickListener { showImportDialog() }
        v.findViewById<MaterialButton>(R.id.btnImportTvbox).setOnClickListener { showTvBoxImportDialog() }
        v.findViewById<View>(R.id.fabPlayAll).setOnClickListener {
            if (manageMode) { toggleManageMode(v); return@setOnClickListener }
            if (visibleGroups.isNotEmpty()) openPlayerGroup(visibleGroups, 0)
            else Toast.makeText(requireContext(), "当前筛选下暂无频道", Toast.LENGTH_SHORT).show()
        }

        v.findViewById<MaterialButton>(R.id.btnManage).setOnClickListener { toggleManageMode(v) }
        v.findViewById<MaterialButton>(R.id.btnRecycle).setOnClickListener { showRecycleBin() }
        v.findViewById<MaterialButton>(R.id.btnSelectAll).setOnClickListener { adapter.selectAll() }
        v.findViewById<MaterialButton>(R.id.btnInvert).setOnClickListener { adapter.invertSelection() }
        v.findViewById<MaterialButton>(R.id.btnKeepSelected).setOnClickListener { keepSelected() }
        v.findViewById<MaterialButton>(R.id.btnDeleteSelected).setOnClickListener { deleteSelected() }

        reload()
        return v
    }

    override fun onResume() {
        super.onResume()
        reload()
        consumePendingImport()
    }

    /** 处理外部 m3u/m3u8/http(s) 链接唤起：自动抓取并导入，成功后清空待办避免重复 */
    private fun consumePendingImport() {
        val url = PlaylistBus.pendingImportUrl ?: return
        PlaylistBus.pendingImportUrl = null
        if (!url.contains("://") || !viewAlive) return
        val appCtx = requireContext().applicationContext
        toast(appCtx, "正在导入外部直播源…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { fetchText(url) }
                if (text.isNullOrBlank()) {
                    toast(appCtx, "外部源导入失败：无法获取内容")
                    return@launch
                }
                val chans = SourceCollector(appCtx).parseImported(text, "外部:${url.take(30)}")
                if (chans.isEmpty()) {
                    toast(appCtx, "外部源导入失败：未识别到有效频道")
                    return@launch
                }
                val merged = mergeChannels(appCtx, chans, Channel.ORIGIN_LIVE, url)
                Prefs.saveChannels(appCtx, merged)
                reload()
                toast(appCtx, "已导入 ${chans.size} 个频道")
            } catch (e: Throwable) {
                toast(appCtx, "外部源导入异常：${e.message?.take(50) ?: "未知错误"}")
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && viewAlive) reload()
    }

    private fun reload() {
        if (!viewAlive) return
        allChannels = Prefs.getChannels(requireContext().applicationContext)
        allGroups = ChannelMerger.merge(allChannels)
        buildOriginChips()
        buildChips()
        filter()
    }

    /** 按来源大类 + 订阅实例生成 chip，可自由切换；计数以合并后的频道组为单位 */
    private fun buildOriginChips() {
        chipOrigins.removeAllViews()
        val groups = allGroups.groupBy { "${it.originKind}|${it.originKey.ifBlank { it.originKind }}" }
            .map { (_, list) ->
                val first = list.first()
                SourceOrigin.OriginGroup(
                    kind = first.originKind,
                    key = first.originKey.ifBlank { first.originKind },
                    label = first.originLabel.ifBlank { SourceOrigin.kindName(first.originKind) },
                    count = list.size
                )
            }
            .sortedWith(compareBy({ SourceOrigin.kindOrder(it.kind) }, { it.label }))
        // 若当前选中的来源已不存在，回退到全部
        if (currentOrigin != "all" && groups.none { it.id == currentOrigin }) currentOrigin = "all"

        val allChip = Chip(requireContext()).apply {
            text = getString(R.string.all_sources)
            isCheckable = true
            isChecked = currentOrigin == "all"
            setOnClickListener { currentOrigin = "all"; buildOriginChips(); filter() }
        }
        chipOrigins.addView(allChip)

        groups.forEach { g ->
            val chip = Chip(requireContext()).apply {
                text = "${g.label} (${g.count})"
                isCheckable = true
                isChecked = g.id == currentOrigin
                setOnClickListener { currentOrigin = g.id; buildOriginChips(); filter() }
            }
            chipOrigins.addView(chip)
        }
    }

    private fun buildChips() {
        chipGroups.removeAllViews()
        // 分组只统计当前来源下的频道组，切换来源后分组随之更新
        val scoped = if (currentOrigin == "all") allGroups
        else allGroups.filter { "${it.originKind}|${it.originKey.ifBlank { it.originKind }}" == currentOrigin }
        val groups = listOf("全部") + scoped.map { it.group }.filter { it.isNotBlank() }.distinct().sorted().take(15)
        if (groups.none { it == currentGroup }) currentGroup = "全部"
        groups.forEach { g ->
            val chip = Chip(requireContext()).apply {
                text = g
                isCheckable = true
                isChecked = g == currentGroup
                setOnClickListener { currentGroup = g; buildChips(); filter() }
            }
            chipGroups.addView(chip)
        }
    }

    private fun filter() {
        val q = etSearch.text.toString().trim()
        val list = allGroups.filter { g ->
            (currentOrigin == "all" ||
                "${g.originKind}|${g.originKey.ifBlank { g.originKind }}" == currentOrigin) &&
            (currentGroup == "全部" || g.group == currentGroup) &&
            (q.isBlank() || g.name.contains(q, true) || g.best?.url?.contains(q, true) == true)
        }
        visibleGroups = list
        adapter.submit(list)
        emptyState.visibility = if (allGroups.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showChannelMenu(g: ChannelGroup) {
        val ctx = requireContext()
        val best = g.best
        val favs = Prefs.getFavorites(ctx)
        val isFav = favs.contains(g.name)
        val items = mutableListOf("播放", if (isFav) "取消收藏" else "收藏")
        if (g.lineCount > 1) items += "选择线路（${g.lineCount}条）"
        items += listOf("复制地址", "在外部播放器打开", "删除此频道")
        AlertDialog.Builder(ctx)
            .setTitle(if (g.lineCount > 1) "${g.name}（${g.lineCount}条线路）" else g.name)
            .setItems(items.toTypedArray()) { _, i ->
                when (i) {
                    0 -> openPlayerGroup(listOf(g), 0)
                    1 -> {
                        if (isFav) favs.remove(g.name) else favs.add(g.name)
                        Prefs.saveFavorites(ctx, favs)
                    }
                    2 -> if (g.lineCount > 1) showLinePicker(g)
                    3 -> best?.let { copyText(it.url) }
                    4 -> best?.let {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it.url))
                        intent.setDataAndType(Uri.parse(it.url), "video/*")
                        try { startActivity(intent) } catch (e: Exception) { Toast.makeText(ctx, "没有可用的外部播放器", Toast.LENGTH_SHORT).show() }
                    }
                    5 -> confirmDelete(g)
                }
            }.show()
    }

    /** 频道菜单里的「选择线路」：列出该频道所有线路，选中后从该线路开始播放 */
    private fun showLinePicker(g: ChannelGroup) {
        val ctx = requireContext()
        val labels = g.channels.mapIndexed { idx, c ->
            "线路${idx + 1} · ${c.lineStatusText()} · ${c.group.ifBlank { c.source }}"
        }.toTypedArray()
        AlertDialog.Builder(ctx).setTitle("${g.name} · 选择线路")
            .setItems(labels) { _, which ->
                val startLine = which.coerceIn(0, g.channels.lastIndex)
                openPlayerGroup(listOf(g), 0, startLine)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // ---------------- 保留 / 删除 / 回收站 ----------------

    private fun toggleManageMode(v: View) {
        manageMode = !manageMode
        adapter.setManageMode(manageMode)
        v.findViewById<View>(R.id.manageBar).visibility = if (manageMode) View.VISIBLE else View.GONE
        val btn = v.findViewById<MaterialButton>(R.id.btnManage)
        btn.setText(if (manageMode) R.string.done else R.string.manage)
        v.findViewById<View>(R.id.fabPlayAll).visibility = if (manageMode) View.GONE else View.VISIBLE
    }

    /** 单个频道（整组所有线路）删除：二次确认后移入回收站 */
    private fun confirmDelete(g: ChannelGroup) {
        val ctx = requireContext()
        val msg = if (g.lineCount > 1)
            "确定删除「${g.name}」及其 ${g.lineCount} 条线路吗？可在回收站恢复。"
            else getString(R.string.delete_channel_msg, g.name)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.delete_channel_title)
            .setMessage(msg)
            .setPositiveButton(R.string.delete) { _, _ -> deleteGroups(listOf(g)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 把频道组展开为所有线路后删除 */
    private fun deleteGroups(groups: List<ChannelGroup>, showToast: Boolean = true) {
        deleteChannels(groups.flatMap { it.channels }, showToast)
    }

    /** 把频道从列表移除并存入回收站（持久化，采集/导入不会再加回） */
    private fun deleteChannels(channels: List<Channel>, showToast: Boolean = true) {
        if (channels.isEmpty()) return
        val ctx = requireContext()
        val keys = channels.map { it.identity }.toSet()
        val bin = Prefs.getRecycleBin(ctx)
        bin.addAll(channels.filterNot { bin.any { b -> b.identity == it.identity } })
        Prefs.saveRecycleBin(ctx, bin)
        val remain = Prefs.getChannels(ctx).filterNot { keys.contains(it.identity) }
        Prefs.saveChannels(ctx, remain)
        reload()
        if (showToast) Toast.makeText(ctx, getString(R.string.delete_done, channels.size), Toast.LENGTH_SHORT).show()
    }

    /** 批量：只保留勾选的频道，其余移入回收站 */
    private fun keepSelected() {
        val keep = adapter.selectedGroups()
        if (keep.isEmpty()) {
            Toast.makeText(requireContext(), "请先勾选要保留的频道", Toast.LENGTH_SHORT).show()
            return
        }
        val remove = adapter.unselectedGroups()
        if (remove.isEmpty()) {
            Toast.makeText(requireContext(), "当前已全部勾选", Toast.LENGTH_SHORT).show()
            return
        }
        deleteGroups(remove, showToast = false)
        Toast.makeText(requireContext(), getString(R.string.keep_done, keep.size, remove.size), Toast.LENGTH_SHORT).show()
    }

    /** 批量：删除勾选的频道（整组） */
    private fun deleteSelected() {
        val selectedGroups = adapter.selectedGroups()
        if (selectedGroups.isEmpty()) {
            Toast.makeText(requireContext(), "请先勾选要删除的频道", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_channel_title)
            .setMessage("确定删除选中的 ${selectedGroups.size} 个频道吗？可在回收站恢复。")
            .setPositiveButton(R.string.delete) { _, _ -> deleteGroups(selectedGroups) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 回收站：列出已删除频道，可单个恢复、全部恢复或清空 */
    private fun showRecycleBin() {
        val ctx = requireContext()
        val bin = Prefs.getRecycleBin(ctx)
        if (bin.isEmpty()) {
            Toast.makeText(ctx, R.string.recycle_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val names = bin.map { "${it.name}  ·  ${it.group.ifBlank { "未分组" }}" }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.recycle_bin) + "（${bin.size}）")
            .setItems(names) { _, which ->
                restoreChannels(listOf(bin[which]))
            }
            .setPositiveButton(R.string.recycle_restore_all) { _, _ -> restoreChannels(bin.toList()) }
            .setNegativeButton(R.string.recycle_clear) { _, _ ->
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.recycle_clear)
                    .setMessage(R.string.recycle_clear_confirm)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        Prefs.saveRecycleBin(ctx, emptyList())
                        Toast.makeText(ctx, "回收站已清空", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .show()
    }

    /** 从回收站恢复频道：加回列表并从回收站移除 */
    private fun restoreChannels(channels: List<Channel>) {
        if (channels.isEmpty()) return
        val ctx = requireContext()
        val keys = channels.map { it.identity }.toSet()
        val bin = Prefs.getRecycleBin(ctx).filterNot { keys.contains(it.identity) }
        Prefs.saveRecycleBin(ctx, bin)
        val merged = (Prefs.getChannels(ctx) + channels).distinctBy { it.identity }
        Prefs.saveChannels(ctx, merged)
        reload()
        Toast.makeText(ctx, getString(R.string.recycle_restored, channels.size), Toast.LENGTH_SHORT).show()
    }

    private fun copyText(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("IPTV", text))
        Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
    }

    /**
     * 合并新导入频道到现有列表，并过滤已在回收站中的频道（删了就不会被导入加回）。
     * 同时给新频道打来源标记，使频道页可按来源分类切换显示。
     */
    private fun mergeChannels(
        ctx: Context,
        incoming: List<Channel>,
        kind: String = Channel.ORIGIN_LIVE,
        originKey: String = "手动导入"
    ): List<Channel> {
        val removed = Prefs.getRemovedKeys(ctx)
        val tagged = SourceOrigin.tag(incoming, kind, originKey)
        return (Prefs.getChannels(ctx) + tagged)
            .filterNot { removed.contains(it.identity) }
            .distinctBy { it.identity }
    }

    private fun showImportDialog() {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_import, null)
        val etUrl = view.findViewById<EditText>(R.id.etImportUrl)
        val etPaste = view.findViewById<EditText>(R.id.etPaste)
        val dlg = AlertDialog.Builder(ctx).setTitle("导入直播源").setView(view).create()

        view.findViewById<Button>(R.id.btnImportUrl).setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (!url.contains("://")) { Toast.makeText(ctx, "请输入有效地址", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            dlg.dismiss()
            val appCtx = ctx.applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) { fetchText(url) }
                    if (text.isNullOrBlank()) {
                        toast(appCtx, "导入失败：无法获取内容")
                    } else {
                        val chans = SourceCollector(appCtx).parseImported(text, "导入:${url.take(30)}")
                        if (chans.isEmpty()) {
                            toast(appCtx, "导入失败：未识别到有效频道")
                        } else {
                            val merged = mergeChannels(appCtx, chans, Channel.ORIGIN_LIVE, url)
                            Prefs.saveChannels(appCtx, merged)
                            reload()
                            toast(appCtx, "已导入 ${chans.size} 个频道")
                        }
                    }
                } catch (e: Throwable) {
                    toast(appCtx, "导入异常：${e.message?.take(50) ?: "未知错误"}")
                }
            }
        }

        view.findViewById<Button>(R.id.btnImportFile).setOnClickListener {
            dlg.dismiss()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            startActivityForResult(intent, REQ_IMPORT_FILE)
        }

        view.findViewById<Button>(R.id.btnImportPaste).setOnClickListener {
            var text = etPaste.text.toString()
            if (text.isBlank()) {
                try {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                } catch (_: Exception) {}
            }
            if (text.isBlank()) { Toast.makeText(ctx, "没有可导入的内容", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            try {
                val chans = SourceCollector(ctx).parseImported(text, "手动导入")
                if (chans.isEmpty()) { Toast.makeText(ctx, "未识别到有效频道", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val merged = mergeChannels(ctx, chans, Channel.ORIGIN_LIVE, "剪贴板·${System.currentTimeMillis()}")
                Prefs.saveChannels(ctx, merged)
                reload()
                dlg.dismiss()
                Toast.makeText(ctx, getString(R.string.playlist_imported, chans.size), Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                toast(ctx.applicationContext, "导入异常：${e.message?.take(50) ?: "未知错误"}")
            }
        }
        dlg.show()
    }

    /** TVBox / 影视仓配置导入：自动展开 lives 订阅为可播放频道 */
    private fun showTvBoxImportDialog() {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_tvbox_import, null)
        val etUrl = view.findViewById<EditText>(R.id.etTvBoxUrl)
        val etPaste = view.findViewById<EditText>(R.id.etTvBoxPaste)
        val dlg = AlertDialog.Builder(ctx).setTitle(R.string.import_tvbox).setView(view).create()

        view.findViewById<MaterialButton>(R.id.btnTvBoxUrl).setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (!url.contains("://")) {
                Toast.makeText(ctx, "请输入有效的配置订阅地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dlg.dismiss()
            doTvBoxImport(url, null, "TVBox:${url.take(24)}")
        }

        view.findViewById<MaterialButton>(R.id.btnTvBoxPaste).setOnClickListener {
            var text = etPaste.text.toString()
            if (text.isBlank()) {
                try {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                } catch (_: Exception) {}
            }
            if (text.isBlank()) {
                Toast.makeText(ctx, "没有可导入的配置内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dlg.dismiss()
            doTvBoxImport(null, text, "TVBox:手动导入")
        }
        dlg.show()
    }

    /** 执行 TVBox 导入（URL 或文本），带进度提示 */
    private fun doTvBoxImport(url: String?, text: String?, label: String) {
        if (!viewAlive) return
        val ctx = requireContext()
        val appCtx = ctx.applicationContext
        val progress = AlertDialog.Builder(ctx).setTitle("正在导入 TVBox")
            .setMessage("正在展开直播订阅，请稍候…").setCancelable(false).create()
        progress.show()
        // 关键：importer 的进度回调来自 IO 线程，必须 post 回主线程更新 Dialog，
        // 否则 CalledFromWrongThreadException 直接闪退。
        val progressUi: (String) -> Unit = { msg ->
            if (activity != null) activity?.runOnUiThread {
                try { if (progress.isShowing) progress.setMessage(msg) } catch (_: Throwable) {}
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val importer = TvBoxImporter(appCtx)
                val result = if (url != null) {
                    importer.importFromUrl(url, label) { p ->
                        progressUi("${p.stage} ${p.done}/${p.total}\n${p.message}")
                    }
                } else {
                    importer.importFromText(text ?: "", url, label) { p ->
                        progressUi("${p.stage} ${p.done}/${p.total}\n${p.message}")
                    }
                }
                withContext(Dispatchers.Main) {
                    try { progress.dismiss() } catch (_: Throwable) {}
                    if (result.channels.isEmpty()) {
                        toast(appCtx, "导入失败：未展开出可播放频道\n请检查配置地址是否有效")
                    } else {
                        val merged = mergeChannels(
                            appCtx, result.channels, Channel.ORIGIN_TVBOX,
                            url ?: "TVBox粘贴·${System.currentTimeMillis()}"
                        )
                        Prefs.saveChannels(appCtx, merged)
                        reload()
                        toast(appCtx, "TVBox 导入完成：${result.channels.size} 个频道（展开 ${result.expandedCount} 路订阅）")
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    try { progress.dismiss() } catch (_: Throwable) {}
                    toast(appCtx, "TVBox 导入异常：${e.message?.take(60) ?: "未知错误"}")
                }
            }
        }
    }

    /** 用 applicationContext 弹 Toast，避免视图销毁后持有已泄漏的 Activity */
    private fun toast(appCtx: Context, msg: String) {
        try { Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_FILE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val ctx = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }
                    if (!text.isNullOrBlank()) {
                        val chans = SourceCollector(ctx).parseImported(text, "文件导入")
                        if (chans.isEmpty()) {
                            toast(ctx, "未识别到有效频道")
                        } else {
                            val merged = mergeChannels(ctx, chans, Channel.ORIGIN_LIVE, "文件·${uri.lastPathSegment ?: System.currentTimeMillis()}")
                            Prefs.saveChannels(ctx, merged)
                            reload()
                            toast(ctx, "已导入 ${chans.size} 个频道")
                        }
                    }
                } catch (e: Throwable) {
                    toast(ctx, "文件导入失败：${e.message?.take(50) ?: "未知错误"}")
                }
            }
        }
    }

    private fun fetchText(url: String): String? = try {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(url).header("User-Agent", SourceCollector.DEFAULT_UA).build()
        client.newCall(req).execute().use {
            if (it.isSuccessful) com.iptv.app.core.SafeBody.readText(it).text else null
        }
    } catch (e: Throwable) { null }

    /**
     * 以频道组为单位打开播放器。
     * @param groups 可见频道组列表（用于上一台/下一台）
     * @param groupIndex 起始频道组下标
     * @param startLine 起始线路下标，默认 0（最佳线路）
     */
    private fun openPlayerGroup(groups: List<ChannelGroup>, groupIndex: Int, startLine: Int = 0) {
        if (groups.isEmpty()) {
            Toast.makeText(requireContext(), "暂无可播放的频道", Toast.LENGTH_SHORT).show()
            return
        }
        // 通过进程内总线传递，避免超长 JSON 撑爆 Intent Binder 事务（TransactionTooLargeException）
        val safeIndex = groupIndex.coerceIn(0, groups.size - 1)
        val safeLine = startLine.coerceIn(0, (groups[safeIndex].channels.lastIndex).coerceAtLeast(0))
        PlaylistBus.setGroups(groups, safeIndex, safeLine)
        val intent = Intent(requireContext(), PlayerActivity::class.java)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开播放器：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQ_IMPORT_FILE = 2002
    }
}
