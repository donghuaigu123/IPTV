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
import com.iptv.app.model.Channel
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
    private lateinit var tvStatChannels: TextView
    private lateinit var tvStatOk: TextView
    private lateinit var tvStatSources: TextView
    private lateinit var sourceAdapter: SourceAdapter
    private var collector: SourceCollector? = null
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
        tvStatChannels = v.findViewById(R.id.tvStatChannels)
        tvStatOk = v.findViewById(R.id.tvStatOk)
        tvStatSources = v.findViewById(R.id.tvStatSources)

        setupSources()
        updateDirLabel()
        updateSummary()

        btnCollect.setOnClickListener { startCollect() }
        btnCheck.setOnClickListener { startCheck() }
        swipe.setOnRefreshListener { startCollect() }
        v.findViewById<MaterialButton>(R.id.btnDiagnosis).setOnClickListener { showDiagnosis() }

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
        val ctx = requireContext().applicationContext
        job?.cancel()
        job = lifecycleScope.launch {
            btnCollect.isEnabled = false
            swipe.isRefreshing = true
            try {
                val sources = Prefs.getSources(ctx)
                val c = SourceCollector(ctx)
                collector = c
                val channels = c.collect(sources) { p -> updateProgress(p) }
                Prefs.saveChannels(ctx, channels)
                updateSummary()

                if (Prefs.getBool(ctx, Prefs.KEY_AUTO_CHECK, true) && channels.isNotEmpty()) {
                    // 在同一协程里直接执行检测，避免 startCheck() 内部 job?.cancel() 取消自身导致协程异常
                    doCheck(ctx)
                } else {
                    safeToast(ctx, "采集完成：${channels.size} 个频道")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户主动取消，正常退出
            } catch (e: Exception) {
                safeToast(ctx, "采集失败：${e.message?.take(50)}")
            } finally {
                if (isAdded) {
                    btnCollect.isEnabled = true
                    swipe.isRefreshing = false
                }
            }
        }
    }

    private fun startCheck() {
        val ctx = requireContext().applicationContext
        val channels = Prefs.getChannels(ctx)
        if (channels.isEmpty()) {
            safeToast(ctx, "请先采集或导入频道")
            return
        }
        job?.cancel()
        job = lifecycleScope.launch { doCheck(ctx) }
    }

    /** 执行可用性检测（suspend，供采集后自动检测与手动检测共用） */
    private suspend fun doCheck(ctx: android.content.Context) {
        if (!isAdded) return
        btnCheck.isEnabled = false
        try {
            val channels = Prefs.getChannels(ctx)
            if (channels.isEmpty()) return
            val checker = ChannelChecker(ctx)
            val checked = checker.check(channels) { p -> updateProgress(p) }
            val valid = checked.filter { it.status == Channel.STATUS_OK }
            val invalidCount = checked.size - valid.size
            val purge = Prefs.getBool(ctx, Prefs.KEY_PURGE_INVALID, true)
            when {
                // 检测后有可用源：清除全部无效源，只保留有效源
                purge && invalidCount > 0 && valid.isNotEmpty() -> {
                    Prefs.saveChannels(ctx, valid)
                    safeToast(ctx, "检测完成：保留 ${valid.size} 个有效源，已自动清除 $invalidCount 个无效源")
                }
                // 全部无效：多半是网络波动或误判，保留原列表避免数据全丢，交给用户判断
                purge && invalidCount > 0 && valid.isEmpty() -> {
                    Prefs.saveChannels(ctx, checked)
                    safeToast(ctx, "本次检测没有可用源，可能是网络异常，已保留原列表")
                }
                else -> {
                    Prefs.saveChannels(ctx, checked)
                    safeToast(ctx, "检测完成：可用 ${valid.size} 个，无效 $invalidCount 个")
                }
            }
            updateSummary()
        } catch (e: kotlinx.coroutines.CancellationException) {
        } catch (e: Exception) {
            safeToast(ctx, "检测失败：${e.message?.take(50)}")
        } finally {
            if (isAdded) btnCheck.isEnabled = true
        }
    }

    /** 线程安全地弹出 Toast */
    private fun safeToast(ctx: android.content.Context, msg: String) {
        val activity = activity ?: return
        activity.runOnUiThread {
            try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    /**
     * 采集/检测的进度回调来自 Dispatchers.IO 线程，必须切回主线程更新 UI，
     * 否则会抛 CalledFromWrongThreadException 导致闪退。
     */
    private fun updateProgress(p: CollectProgress) {
        if (!isAdded || context == null) return
        val activity = activity ?: return
        activity.runOnUiThread {
            if (!isAdded || context == null) return@runOnUiThread
            try {
                tvProgress.text = "${p.stage} · ${p.message}"
                if (p.total > 0) progressBar.progress = (p.done * 100 / p.total).coerceIn(0, 100)
            } catch (_: Exception) {}
        }
    }

    private fun updateSummary() {
        if (!isAdded || context == null) return
        try {
            val ctx = requireContext()
            val list = Prefs.getChannels(ctx)
            val ok = list.count { it.isOk }
            val fail = list.count { it.status == com.iptv.app.model.Channel.STATUS_FAIL }
            tvStatChannels.text = list.size.toString()
            tvStatOk.text = ok.toString()
            tvStatSources.text = Prefs.getSources(ctx).count { it.enabled }.toString()
            tvSummary.text = "共 ${list.size} 个频道 · 可用 $ok · 失效 $fail"
        } catch (_: Exception) {}
    }

    /** 展示每个数据源的采集诊断信息 */
    private fun showDiagnosis() {
        val results = collector?.getLastResults() ?: emptyList()
        if (results.isEmpty()) {
            Toast.makeText(requireContext(), "请先执行一次采集", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        val okCount = results.count { it.success }
        sb.append("成功 $okCount / ${results.size} 个数据源\n\n")
        results.sortedBy { it.success }.forEach { r ->
            val icon = if (r.success) "✓" else "✕"
            val info = if (r.success) "+${r.count} 频道 · ${r.usedMirror}" else r.detail
            sb.append("$icon ${r.name}\n   $info\n")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("采集诊断详情")
            .setMessage(sb.toString())
            .setPositiveButton(R.string.ok, null)
            .show()
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
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // 部分机型不支持持久化授权，仅本次会话有效，仍可继续使用
            }
            Prefs.setTreeUri(requireContext(), uri.toString())
            updateDirLabel()
        }
    }

    private fun updateDirLabel() {
        val uri = Prefs.getTreeUri(requireContext())
        tvDir.text = if (uri == null) getString(R.string.dir_not_set) else "下载目录：$uri"
    }

    private fun export(format: ExportFormat) {
        val ctx = requireContext().applicationContext
        // 只导出有效源：明确检测失败的源一律排除；未检测的源（UNKNOWN）保留，
        // 用户可在「可用性检测」后再次导出，即可得到纯有效源列表。
        val channels = Prefs.getChannels(ctx).filter { it.status != Channel.STATUS_FAIL }
        if (channels.isEmpty()) {
            safeToast(ctx, "没有有效源可导出，请先做可用性检测")
            return
        }
        if (Prefs.getTreeUri(ctx) == null) {
            safeToast(ctx, "请先在设置或采集页选择下载目录")
            chooseDir()
            return
        }
        lifecycleScope.launch {
            try {
                val result = Exporter(ctx).export(channels, format)
                result.fold(
                    onSuccess = { uri ->
                        safeToast(ctx, "已导出 ${channels.size} 个有效源：${uri.lastPathSegment ?: "文件"}")
                    },
                    onFailure = { e -> safeToast(ctx, getString(R.string.export_fail, e.message ?: "未知错误")) }
                )
            } catch (e: Exception) {
                safeToast(ctx, "导出异常：${e.message?.take(50)}")
            }
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
