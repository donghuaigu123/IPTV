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
import com.iptv.app.core.SourceCollector
import com.iptv.app.core.TvBoxImporter
import com.iptv.app.data.PlaylistBus
import com.iptv.app.data.Prefs
import com.iptv.app.model.Channel
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
    private lateinit var adapter: ChannelAdapter
    private lateinit var emptyState: View
    private var allChannels: List<Channel> = emptyList()
    private var currentGroup = "全部"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_library, container, false)
        etSearch = v.findViewById(R.id.etSearch)
        recycler = v.findViewById(R.id.recyclerChannels)
        chipGroups = v.findViewById(R.id.chipGroups)
        emptyState = v.findViewById(R.id.emptyState)

        adapter = ChannelAdapter(mutableListOf(), onPlay = { ch ->
            val idx = allChannels.indexOfFirst { it.url == ch.url && it.name == ch.name }
            openPlayer(allChannels, if (idx >= 0) idx else 0)
        }, onLongClick = { ch -> showChannelMenu(ch) })
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
            if (allChannels.isNotEmpty()) openPlayer(allChannels, 0)
            else Toast.makeText(requireContext(), "暂无频道", Toast.LENGTH_SHORT).show()
        }

        reload()
        return v
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) reload()
    }

    private fun reload() {
        allChannels = Prefs.getChannels(requireContext())
        buildChips()
        filter()
    }

    private fun buildChips() {
        chipGroups.removeAllViews()
        val groups = listOf("全部") + allChannels.map { it.group }.distinct().sorted().take(15)
        groups.forEach { g ->
            val chip = Chip(requireContext()).apply {
                text = g
                isCheckable = true
                isChecked = g == currentGroup
                setOnClickListener { currentGroup = g; filter() }
            }
            chipGroups.addView(chip)
        }
    }

    private fun filter() {
        val q = etSearch.text.toString().trim()
        val list = allChannels.filter { ch ->
            (currentGroup == "全部" || ch.group == currentGroup) &&
                (q.isBlank() || ch.name.contains(q, true) || ch.url.contains(q, true))
        }
        adapter.submit(list)
        emptyState.visibility = if (allChannels.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showChannelMenu(ch: Channel) {
        val ctx = requireContext()
        val favs = Prefs.getFavorites(ctx)
        val isFav = favs.contains(ch.url)
        AlertDialog.Builder(ctx)
            .setTitle(ch.name)
            .setItems(arrayOf("播放", if (isFav) "取消收藏" else "收藏", "复制地址", "在外部播放器打开")) { _, i ->
                when (i) {
                    0 -> openPlayer(listOf(ch), 0)
                    1 -> {
                        if (isFav) favs.remove(ch.url) else favs.add(ch.url)
                        Prefs.saveFavorites(ctx, favs)
                    }
                    2 -> copyText(ch.url)
                    3 -> {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ch.url))
                        intent.setDataAndType(Uri.parse(ch.url), "video/*")
                        try { startActivity(intent) } catch (e: Exception) { Toast.makeText(ctx, "没有可用的外部播放器", Toast.LENGTH_SHORT).show() }
                    }
                }
            }.show()
    }

    private fun copyText(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("IPTV", text))
        Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
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
            lifecycleScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) { fetchText(url) }
                    if (text.isNullOrBlank()) {
                        Toast.makeText(ctx, "导入失败：无法获取内容", Toast.LENGTH_LONG).show()
                    } else {
                        val chans = SourceCollector(ctx).parseImported(text, "导入:${url.take(30)}")
                        if (chans.isEmpty()) {
                            Toast.makeText(ctx, "导入失败：未识别到有效频道", Toast.LENGTH_LONG).show()
                        } else {
                            val merged = (Prefs.getChannels(ctx) + chans).distinctBy { it.name + it.url }
                            Prefs.saveChannels(ctx, merged)
                            reload()
                            Toast.makeText(ctx, getString(R.string.playlist_imported, chans.size), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(ctx, "导入异常：${e.message?.take(50)}", Toast.LENGTH_LONG).show()
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
                val merged = (Prefs.getChannels(ctx) + chans).distinctBy { it.name + it.url }
                Prefs.saveChannels(ctx, merged)
                reload()
                dlg.dismiss()
                Toast.makeText(ctx, getString(R.string.playlist_imported, chans.size), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "导入异常：${e.message?.take(50)}", Toast.LENGTH_SHORT).show()
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
        val ctx = requireContext()
        val progress = AlertDialog.Builder(ctx).setTitle("正在导入 TVBox")
            .setMessage("正在展开直播订阅，请稍候…").setCancelable(false).create()
        progress.show()
        lifecycleScope.launch {
            try {
                val importer = TvBoxImporter(ctx.applicationContext)
                val result = if (url != null) {
                    importer.importFromUrl(url, label) { p ->
                        progress.setMessage("${p.stage} ${p.done}/${p.total}\n${p.message}")
                    }
                } else {
                    importer.importFromText(text ?: "", url, label) { p ->
                        progress.setMessage("${p.stage} ${p.done}/${p.total}\n${p.message}")
                    }
                }
                withContext(Dispatchers.Main) {
                    try { progress.dismiss() } catch (_: Exception) {}
                    if (result.channels.isEmpty()) {
                        Toast.makeText(ctx, "导入失败：未展开出可播放频道\n请检查配置地址是否有效", Toast.LENGTH_LONG).show()
                    } else {
                        val merged = (Prefs.getChannels(ctx) + result.channels).distinctBy { it.name + it.url }
                        Prefs.saveChannels(ctx, merged)
                        reload()
                        Toast.makeText(
                            ctx,
                            getString(R.string.tvbox_imported, result.channels.size, result.expandedCount),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    try { progress.dismiss() } catch (_: Exception) {}
                    Toast.makeText(ctx, "TVBox 导入异常：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_FILE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val ctx = requireContext().applicationContext
            lifecycleScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }
                    if (!text.isNullOrBlank()) {
                        val chans = SourceCollector(ctx).parseImported(text, "文件导入")
                        if (chans.isEmpty()) {
                            Toast.makeText(ctx, "未识别到有效频道", Toast.LENGTH_SHORT).show()
                        } else {
                            val merged = (Prefs.getChannels(ctx) + chans).distinctBy { it.name + it.url }
                            Prefs.saveChannels(ctx, merged)
                            reload()
                            Toast.makeText(ctx, getString(R.string.playlist_imported, chans.size), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(ctx, "文件导入失败：${e.message?.take(50)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchText(url: String): String? = try {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(url).header("User-Agent", SourceCollector.DEFAULT_UA).build()
        client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) { null }

    private fun openPlayer(channels: List<Channel>, index: Int) {
        if (channels.isEmpty()) {
            Toast.makeText(requireContext(), "暂无可播放的频道", Toast.LENGTH_SHORT).show()
            return
        }
        // 通过进程内总线传递，避免超长 JSON 撑爆 Intent Binder 事务（TransactionTooLargeException）
        val safeIndex = index.coerceIn(0, channels.size - 1)
        PlaylistBus.set(channels)
        val intent = Intent(requireContext(), PlayerActivity::class.java)
        intent.putExtra("index", safeIndex)
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
