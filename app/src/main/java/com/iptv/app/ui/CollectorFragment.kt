package com.iptv.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.iptv.app.R
import com.iptv.app.core.ChannelChecker
import com.iptv.app.core.Exporter
import com.iptv.app.core.SourceCollector
import com.iptv.app.data.Prefs
import com.iptv.app.data.SourceCatalog
import com.iptv.app.model.CollectProgress
import com.iptv.app.model.ExportFormat
import com.iptv.app.model.SourceConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CollectorFragment : Fragment() {

    private lateinit var btnCollect: MaterialButton
    private lateinit var btnCheck: MaterialButton
    private lateinit var tvProgress: TextView
    private lateinit var tvSummary: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerSources: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var tvDir: TextView
    private lateinit var sourceAdapter: SourceAdapter
    private var job: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_collect, container, false)
        btnCollect = v.findViewById(R.id.btnCollect)
        btnCheck = v.findViewById(R.id.btnCheck)
        tvProgress = v.findViewById(R.id.tvProgress)
        tvSummary = v.findViewById(R.id.tvSummary)
        progressBar = v.findViewById(R.id.progressBar)
        recyclerSources = v.findViewById(R.id.recyclerSources)
        swipe = v.findViewById(R.id.swipe)
        tvDir = v.findViewById(R.id.tvDir)

        setupSources()
        updateDirLabel()
        updateSummary()

        btnCollect.setOnClickListener { startCollect() }
        btnCheck.setOnClickListener { startCheck() }
        swipe.setOnRefreshListener { startCollect() }

        v.findViewById<MaterialButton>(R.id.btnAddSource).setOnClickListener { editSource(null) }
        v.findViewById<MaterialButton>(R.id.btnResetSources).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("恢复默认源")
                .setMessage("将清空自定义源并恢复内置清单，确定吗？")
                .setPositiveButton(R.string.ok) { _, _ ->
                    Prefs.resetSources(requireContext())
                    setupSources()
                }.setNegativeButton(R.string.cancel, null).show()
        }
        v.findViewById<MaterialButton>(R.id.btnChooseDir).setOnClickListener { chooseDir() }

        val exportMap = mapOf(
            R.id.btnM3u to ExportFormat.M3U,
            R.id.btnM3u8 to ExportFormat.M3U8,
            R.id.btnTxt to ExportFormat.TXT,
            R.id.btnJson to ExportFormat.JSON,
            R.id.btnTvbox to ExportFormat.TVBOX,
            R.id.btnXspf to ExportFormat.XSPF,
            R.id.btnCsv to ExportFormat.CSV
        )
        exportMap.forEach { (id, fmt) ->
            v.findViewById<MaterialButton>(id).setOnClickListener { export(fmt) }
        }
        return v
    }

    private fun setupSources() {
        val list = Prefs.getSources(requireContext())
        sourceAdapter = SourceAdapter(
            list,
            onChanged = { Prefs.saveSources(requireContext(), list) },
            onDelete = { s ->
                list.remove(s)
                Prefs.saveSources(requireContext(), list)
                sourceAdapter.submit(list)
            },
            onEdit = { s -> editSource(s) }
        )
        recyclerSources.layoutManager = LinearLayoutManager(requireContext())
        recyclerSources.adapter = sourceAdapter
        sourceAdapter.submit(list)
    }

    private fun editSource(existing: SourceConfig?) {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_source, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etUrl = view.findViewById<EditText>(R.id.etUrl)
        val etUa = view.findViewById<EditText>(R.id.etUa)
        existing?.let {
            etName.setText(it.name)
            etUrl.setText(it.url)
            etUa.setText(it.userAgent)
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "添加订阅源" else "编辑订阅源")
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = etName.text.toString().ifBlank { "自定义源" }
                val url = etUrl.text.toString().trim()
                if (!url.contains("://")) {
                    Toast.makeText(ctx, "请输入有效的 http(s) 地址", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val list = Prefs.getSources(ctx)
                val type = when {
                    url.contains("tvbox", true) || url.contains("config", true) -> SourceConfig.TYPE_TVBOX
                    url.contains(".txt", true) -> SourceConfig.TYPE_TXT
                    else -> SourceConfig.TYPE_M3U
                }
                if (existing != null) {
                    val idx = list.indexOfFirst { it.name == existing.name && it.url == existing.url }
                    if (idx >= 0) list[idx] = existing.copy(name = name, url = url, type = type, userAgent = etUa.text.toString(), builtIn = false)
                } else {
                    list.add(0, SourceConfig(name, url, type, true, etUa.text.toString(), false))
                }
                Prefs.saveSources(ctx, list)
                setupSources()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startCollect() {
        val ctx = requireContext()
        job?.cancel()
        job = lifecycleScope.launch {
            btnCollect.isEnabled = false
            swipe.isRefreshing = true
            val sources = Prefs.getSources(ctx)
            val collector = SourceCollector(ctx)
            val channels = collector.collect(sources) { p -> updateProgress(p) }
            Prefs.saveChannels(ctx, channels)
            updateSummary()
            btnCollect.isEnabled = true
            swipe.isRefreshing = false

            if (Prefs.getBool(ctx, Prefs.KEY_AUTO_CHECK, true) && channels.isNotEmpty()) {
                startCheck()
            } else {
                Toast.makeText(ctx, "采集完成：${channels.size} 个频道", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCheck() {
        val ctx = requireContext()
        val channels = Prefs.getChannels(ctx)
        if (channels.isEmpty()) {
            Toast.makeText(ctx, "请先采集或导入频道", Toast.LENGTH_SHORT).show()
            return
        }
        job?.cancel()
        job = lifecycleScope.launch {
            btnCheck.isEnabled = false
            val checker = ChannelChecker(ctx)
            val checked = checker.check(channels) { p -> updateProgress(p) }
            Prefs.saveChannels(ctx, checked)
            updateSummary()
            btnCheck.isEnabled = true
            Toast.makeText(ctx, "检测完成：可用 ${checked.count { it.isOk }} 个", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateProgress(p: CollectProgress) {
        tvProgress.text = "${p.stage} · ${p.message}"
        if (p.total > 0) progressBar.progress = (p.done * 100 / p.total).coerceIn(0, 100)
    }

    private fun updateSummary() {
        val list = Prefs.getChannels(requireContext())
        val ok = list.count { it.isOk }
        tvSummary.text = "频道 ${list.size} 个 · 可用 $ok 个 · 不可用 ${list.count { it.status == com.iptv.app.model.Channel.STATUS_FAIL }} 个"
    }

    private fun chooseDir() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, REQ_DIR)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DIR && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Prefs.setTreeUri(requireContext(), uri.toString())
            updateDirLabel()
        }
    }

    private fun updateDirLabel() {
        val uri = Prefs.getTreeUri(requireContext())
        tvDir.text = if (uri == null) getString(R.string.dir_not_set) else "下载目录：$uri"
    }

    private fun export(format: ExportFormat) {
        val ctx = requireContext()
        val channels = Prefs.getChannels(ctx)
        if (channels.isEmpty()) {
            Toast.makeText(ctx, "没有可导出的频道", Toast.LENGTH_SHORT).show()
            return
        }
        if (Prefs.getTreeUri(ctx) == null) {
            Toast.makeText(ctx, "请先在设置或采集页选择下载目录", Toast.LENGTH_LONG).show()
            chooseDir()
            return
        }
        lifecycleScope.launch {
            val result = Exporter(ctx).export(channels, format)
            result.fold(
                onSuccess = { uri -> Toast.makeText(ctx, getString(R.string.export_success, uri.lastPathSegment), Toast.LENGTH_LONG).show() },
                onFailure = { e -> Toast.makeText(ctx, getString(R.string.export_fail, e.message), Toast.LENGTH_LONG).show() }
            )
        }
    }

    override fun onDestroyView() {
        job?.cancel()
        super.onDestroyView()
    }

    companion object {
        private const val REQ_DIR = 1001
    }
}
