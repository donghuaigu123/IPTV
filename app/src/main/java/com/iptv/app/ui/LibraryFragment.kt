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
import com.google.gson.Gson
import com.iptv.app.R
import com.iptv.app.core.SourceCollector
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
                val text = withContext(Dispatchers.IO) { fetchText(url) }
                if (text == null) {
                    Toast.makeText(ctx, "导入失败：无法获取内容", Toast.LENGTH_LONG).show()
                } else {
                    val chans = SourceCollector(ctx).parseImported(text, "导入:${url.take(30)}")
                    val merged = (Prefs.getChannels(ctx) + chans).distinctBy { it.name + it.url }
                    Prefs.saveChannels(ctx, merged)
                    reload()
                    Toast.makeText(ctx, getString(R.string.playlist_imported, chans.size), Toast.LENGTH_SHORT).show()
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
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            }
            if (text.isBlank()) { Toast.makeText(ctx, "没有可导入的内容", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val chans = SourceCollector(ctx).parseImported(text, "手动导入")
            val merged = (Prefs.getChannels(ctx) + chans).distinctBy { it.name + it.url }
            Prefs.saveChannels(ctx, merged)
            reload()
            dlg.dismiss()
            Toast.makeText(ctx, getString(R.string.playlist_imported, chans.size), Toast.LENGTH_SHORT).show()
        }
        dlg.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_FILE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (text != null) {
                    val chans = SourceCollector(requireContext()).parseImported(text, "文件导入")
                    val merged = (Prefs.getChannels(requireContext()) + chans).distinctBy { it.name + it.url }
                    Prefs.saveChannels(requireContext(), merged)
                    reload()
                    Toast.makeText(requireContext(), getString(R.string.playlist_imported, chans.size), Toast.LENGTH_SHORT).show()
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
        val intent = Intent(requireContext(), PlayerActivity::class.java)
        intent.putExtra("channels", Gson().toJson(channels))
        intent.putExtra("index", index)
        startActivity(intent)
    }

    companion object {
        private const val REQ_IMPORT_FILE = 2002
    }
}
