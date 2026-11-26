package com.iptv.app.ui

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.iptv.app.R
import com.iptv.app.core.ChannelMerger
import com.iptv.app.core.SourceCollector
import com.iptv.app.data.PlaylistBus
import com.iptv.app.data.Prefs
import com.iptv.app.model.Channel
import com.iptv.app.model.ChannelGroup
import com.iptv.app.model.lineStatusText
import kotlin.math.abs

@SuppressLint("UnsafeOptInUsageError")
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var dataSourceFactory: DefaultHttpDataSource.Factory
    private lateinit var drawer: DrawerLayout
    private lateinit var adapter: ChannelGroupAdapter
    private lateinit var tvTitle: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvCenterHint: TextView
    private lateinit var tvLineBadge: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton

    /** 频道组列表（合并后的频道，每组含 N 条线路） */
    private var groups: List<ChannelGroup> = emptyList()
    private var currentGroupIndex = 0
    private var currentLineIndex = 0
    /** 当前频道组内已尝试过的线路下标，避免自动切换死循环 */
    private val triedLines = mutableSetOf<Int>()
    private var switchingGuard = false

    /** 当前线路连续失败次数：达到阈值才认定线路无效并切换，避免网络抖动导致的误切 */
    private var lineRetryCount = 0

    companion object {
        /**
         * v2.0.3 线路切换提速：错误分级 + 参数收紧。
         * - 确定性失败（HTTP 错误、格式错误、无效地址）：不重试，立即换线——这类线路确实无效。
         * - 临时性失败（网络抖动/超时）：仅重试 1 次，冷静期很短，兼顾稳定与速度。
         * 相比旧版「所有错误都重试 2 次 + 1.2 秒冷静 + 15 秒超时」，切换响应显著加快。
         */
        private const val MAX_TRANSIENT_RETRY = 1
        private const val RETRY_DELAY_MS = 450L
        /** 连接超时兜底：直播流 8 秒连不上基本可判定无效，及时让位下一条线路 */
        private const val CONNECT_TIMEOUT_MS = 8000L
        /** HTTP 底层连接/读取超时，与上面的兜底对齐，避免底层卡满 15 秒 */
        private const val HTTP_TIMEOUT_MS = 8000
    }

    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var locked = false
    private var brightness = 0.5f
    private var volumeStart = 0f
    private var seekStart = 0L
    private var isSeeking = false
    private var gestureMode = 0 // 1=音量 2=亮度 3=进度
    private var touchStartY = 0f
    private var touchStartX = 0f
    private var playerReleased = false

    private val updateTask = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed && !playerReleased) {
                try { updateTime() } catch (_: Exception) {}
                handler.postDelayed(this, 500)
            }
        }
    }

    /** 单条线路 8 秒无响应的兜底：已等待足够久，视为确定性失败直接换线，不再二次等待 */
    private val switchTimeoutTask = Runnable {
        if (switchingGuard && !playerReleased && !isFinishing && !isDestroyed) {
            onLineFailure("连接超时", fatal = true)
        }
    }

    /** 冷静期后重试当前线路（重新 prepare），不切换线路 */
    private val retryTask = Runnable {
        if (!playerReleased && !isFinishing && !isDestroyed) retryCurrentLine()
    }

    /** 失败提示短暂停留后自动隐藏，避免长期遮挡画面 */
    private val hideHintTask = Runnable {
        if (!playerReleased && !isFinishing && !isDestroyed) tvCenterHint.visibility = View.GONE
    }

    private fun showFailureHint(text: String) {
        handler.removeCallbacks(hideHintTask)
        tvCenterHint.apply { this.text = text; visibility = View.VISIBLE }
        handler.postDelayed(hideHintTask, 4000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 优先取进程内总线里的「频道组」播放上下文（多线路），避免 Intent 传超长 JSON。
        if (PlaylistBus.groups.isNotEmpty()) {
            groups = PlaylistBus.groups
            currentGroupIndex = PlaylistBus.groupIndex
            currentLineIndex = PlaylistBus.startLine
        } else {
            // 进程回收后重建等场景：回退为从扁平频道列表即时合并。
            val flat = PlaylistBus.get().ifEmpty { Prefs.getChannels(this) }
            groups = ChannelMerger.merge(flat)
            currentGroupIndex = intent.getIntExtra("index", 0)
            currentLineIndex = 0
        }
        if (groups.isEmpty()) {
            // 兼容旧版本可能通过 Intent 传 JSON 的情况
            val json = intent.getStringExtra("channels")
            if (!json.isNullOrBlank()) {
                val flat: List<Channel> = try {
                    Gson().fromJson<List<Channel>>(json, object : TypeToken<List<Channel>>() {}.type) ?: emptyList()
                } catch (e: Exception) { emptyList() }
                groups = ChannelMerger.merge(flat)
            }
        }
        currentGroupIndex = currentGroupIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
        if (groups.isNotEmpty()) {
            currentLineIndex = currentLineIndex.coerceIn(0, groups[currentGroupIndex].channels.lastIndex.coerceAtLeast(0))
        }

        findViews()
        setupDrawer()
        try {
            setupPlayer()
        } catch (e: Exception) {
            Toast.makeText(this, "播放器初始化失败：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setupControls()
        setupGestures()
        playCurrentLine()

        handler.post(updateTask)
    }

    private fun findViews() {
        playerView = findViewById(R.id.playerView)
        drawer = findViewById(R.id.drawer)
        tvTitle = findViewById(R.id.tvTitle)
        tvTime = findViewById(R.id.tvTime)
        tvCenterHint = findViewById(R.id.tvCenterHint)
        tvLineBadge = findViewById(R.id.tvLineBadge)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
    }

    private fun setupDrawer() {
        val recycler = findViewById<RecyclerView>(R.id.recyclerDrawer)
        adapter = ChannelGroupAdapter(groups.toMutableList(), onPlay = { g ->
            val idx = groups.indexOfFirst { it.key == g.key }
            if (idx >= 0) switchToGroup(idx)
            drawer.closeDrawers()
        })
        adapter.darkTheme = true
        adapter.showDelete = false
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val etSearch = findViewById<EditText>(R.id.etChannelSearch)
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().trim()
                adapter.submit(if (q.isBlank()) groups else groups.filter { it.name.contains(q, true) })
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun setupPlayer() {
        val ctx = this
        val ua = Prefs.getString(ctx, Prefs.KEY_UA, SourceCollector.DEFAULT_UA)
        dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(HTTP_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_TIMEOUT_MS)

        trackSelector = DefaultTrackSelector(ctx)

        val mode = when (Prefs.getInt(ctx, Prefs.KEY_DECODER, 0)) {
            1 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            2 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        val renderersFactory = DefaultRenderersFactory(ctx).setExtensionRendererMode(mode)

        val bufferMode = Prefs.getInt(ctx, Prefs.KEY_BUFFER, 0)
        val loadControl = when (bufferMode) {
            1 -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(1500, 5000, 1000, 1000)
                .setPrioritizeTimeOverSizeThresholds(true).build()
            2 -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(10000, 60000, 5000, 5000)
                .setPrioritizeTimeOverSizeThresholds(false).build()
            else -> DefaultLoadControl()
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(ctx)
            .setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(ctx, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (playerReleased || isFinishing || isDestroyed) return
                        try {
                            when (state) {
                                Player.STATE_BUFFERING -> { if (!switchingGuard) tvCenterHint.apply { text = "缓冲中…"; visibility = View.VISIBLE } }
                                Player.STATE_READY -> {
                                    // 线路真正播放成功：取消失败/重试计时并重置失败计数，说明该线路是好的
                                    switchingGuard = false
                                    lineRetryCount = 0
                                    handler.removeCallbacks(switchTimeoutTask)
                                    handler.removeCallbacks(retryTask)
                                    handler.removeCallbacks(hideHintTask)
                                    tvCenterHint.visibility = View.GONE
                                    updateTime()
                                }
                                Player.STATE_ENDED -> { tvCenterHint.apply { text = "播放结束"; visibility = View.VISIBLE } }
                            }
                            updatePlayButton()
                        } catch (_: Exception) {}
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (playerReleased || isFinishing || isDestroyed) return
                        try {
                            updatePlayButton()
                            window.setFlags(
                                if (isPlaying) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0,
                                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            )
                        } catch (_: Exception) {}
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (playerReleased || isFinishing || isDestroyed) return
                        // v2.0.3：错误分级——确定性失败立即换线，仅网络抖动才快速重试 1 次
                        val (reason, fatal) = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "HTTP 错误" to true
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "格式错误" to true
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败" to false
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "连接超时" to false
                            else -> "播放失败" to false
                        }
                        onLineFailure(reason, fatal)
                    }
                    override fun onVideoSizeChanged(size: VideoSize) {
                        // Media3 自动处理画面比例
                    }
                })
            }
        playerView.player = player
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
            controlsVisible = vis == View.VISIBLE
        })
    }

    /** 播放当前组的当前线路 */
    private fun playCurrentLine() {
        val g = groups.getOrNull(currentGroupIndex)
        if (g == null || g.channels.isEmpty()) {
            Toast.makeText(this, "频道列表为空", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (playerReleased || !::player.isInitialized) return
        if (currentLineIndex !in g.channels.indices) currentLineIndex = 0
        triedLines.add(currentLineIndex)
        val ch = g.channels[currentLineIndex]
        updateTitle()
        Prefs.putInt(this, Prefs.KEY_LAST_PLAYED, currentGroupIndex)

        val url = ch.url.trim()
        if (!url.contains("://")) {
            // 地址本身无效（无协议），确定性失败，立即换线不重试
            tvCenterHint.apply { text = "线路 ${currentLineIndex + 1} 地址无效"; visibility = View.VISIBLE }
            onLineFailure("地址无效", fatal = true)
            return
        }
        try {
            // TVBox 导入的频道可能带专用 UA（如 okhttp/3.15），必须用该 UA 才能播放
            val ua = ch.userAgent.ifBlank { Prefs.getString(this, Prefs.KEY_UA, SourceCollector.DEFAULT_UA) }
            try { dataSourceFactory.setUserAgent(ua) } catch (_: Exception) {}
            val item = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setTag(ch)
                .build()
            switchingGuard = true
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = true
            tvCenterHint.apply { text = "正在连接：${g.name} · 线路 ${currentLineIndex + 1}"; visibility = View.VISIBLE }
            updatePlayButton()
            // 兜底：给弱网线路充足连接时间，超时后按失败流程处理（先重试确认）
            handler.removeCallbacks(switchTimeoutTask)
            handler.postDelayed(switchTimeoutTask, CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            tvCenterHint.apply { text = "线路 ${currentLineIndex + 1} 解析失败：${e.message?.take(40)}"; visibility = View.VISIBLE }
            onLineFailure("解析失败", fatal = true)
        }
    }

    /**
     * 线路失败统一处理（v2.0.3 提速版）：
     * - [fatal]=true：确定性失败（HTTP 错误/格式错误/无效地址），线路确实无效，立即换线不重试。
     * - [fatal]=false：临时性失败（网络抖动/超时），快速重试 1 次后换线。
     * - 一旦线路成功播放（STATE_READY），计数被重置。
     */
    private fun onLineFailure(reason: String, fatal: Boolean = false) {
        if (playerReleased || isFinishing || isDestroyed) return
        if (groups.getOrNull(currentGroupIndex) == null) return
        handler.removeCallbacks(switchTimeoutTask)
        handler.removeCallbacks(retryTask)

        // 确定性失败：不浪费时间重试，直接切下一条
        if (fatal) {
            autoSwitchLine(reason)
            return
        }

        if (lineRetryCount < MAX_TRANSIENT_RETRY) {
            lineRetryCount++
            tvCenterHint.apply {
                text = "线路 ${currentLineIndex + 1} $reason，快速重试…"
                visibility = View.VISIBLE
            }
            // 短冷静期后重新 prepare 当前线路；弱网抖动常可自愈
            handler.postDelayed(retryTask, RETRY_DELAY_MS)
        } else {
            // 重试仍失败，认定线路确实无效，切换到下一条
            autoSwitchLine(reason)
        }
    }

    /** 冷静期后重新加载当前线路（不换线） */
    private fun retryCurrentLine() {
        if (playerReleased || !::player.isInitialized) return
        try { player.stop() } catch (_: Exception) {}
        playCurrentLine()
    }

    /**
     * 当前线路失败后自动切换到本频道下一条未尝试过的线路。
     * 每条线路在本频道内只尝试一次；全部失败后给出提示并停止，避免无限重试。
     */
    private fun autoSwitchLine(reason: String = "播放失败") {
        if (playerReleased || isFinishing || isDestroyed) return
        val g = groups.getOrNull(currentGroupIndex) ?: return
        if (g.channels.size <= 1) {
            showFailureHint("$reason，该频道暂无其他线路")
            return
        }
        val next = g.channels.indices.firstOrNull { it !in triedLines }
        if (next == null) {
            showFailureHint("「${g.name}」全部 ${g.lineCount} 条线路均无法播放")
            return
        }
        val failedLine = currentLineIndex
        currentLineIndex = next
        lineRetryCount = 0 // 新线路重新计数，给足重试机会
        handler.removeCallbacks(retryTask)
        handler.removeCallbacks(switchTimeoutTask)
        tvCenterHint.apply { text = "线路 ${failedLine + 1} $reason，切换线路 ${next + 1} / ${g.lineCount}"; visibility = View.VISIBLE }
        try { if (::player.isInitialized) player.stop() } catch (_: Exception) {}
        playCurrentLine()
    }

    /** 用户手动选择线路 */
    private fun showLinePicker() {
        val g = groups.getOrNull(currentGroupIndex) ?: return
        if (g.lineCount <= 1) {
            Toast.makeText(this, "该频道只有 1 条线路", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = g.channels.mapIndexed { idx, c ->
            "线路 ${idx + 1}${if (idx == currentLineIndex) "（当前）" else ""} · ${c.lineStatusText()}${if (c.group.isNotBlank()) " · ${c.group}" else ""}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("${g.name} · 选择线路（${g.lineCount}条）")
            .setItems(labels) { _, which -> switchToLine(which) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun switchToLine(index: Int) {
        val g = groups.getOrNull(currentGroupIndex) ?: return
        if (index !in g.channels.indices) return
        triedLines.clear()
        switchingGuard = false
        lineRetryCount = 0
        handler.removeCallbacks(retryTask)
        handler.removeCallbacks(switchTimeoutTask)
        currentLineIndex = index
        try { if (::player.isInitialized) player.stop() } catch (_: Exception) {}
        playCurrentLine()
    }

    private fun updateTitle() {
        val g = groups.getOrNull(currentGroupIndex) ?: return
        tvTitle.text = if (g.lineCount > 1)
            "${g.name}  ·  线路 ${currentLineIndex + 1}/${g.lineCount}"
        else "${g.name}  ·  ${g.group}"
        tvLineBadge.text = "线路 ${currentLineIndex + 1}/${g.lineCount}"
        tvLineBadge.visibility = if (g.lineCount > 1) View.VISIBLE else View.GONE
    }

    private fun setupControls() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnList).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        tvLineBadge.setOnClickListener { showLinePicker() }
        findViewById<View>(R.id.btnLock).setOnClickListener {
            locked = !locked
            topBar.visibility = if (locked) View.GONE else View.VISIBLE
            bottomBar.visibility = if (locked) View.GONE else View.VISIBLE
            Toast.makeText(this, if (locked) "已锁定屏幕" else "已解锁", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMoreMenu() }
        btnPlayPause.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { switchChannel(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { switchChannel(1) }
        findViewById<View>(R.id.btnPip).setOnClickListener { enterPip() }
        findViewById<View>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !playerReleased && ::player.isInitialized) {
                    try {
                        if (player.duration > 0) tvTime.text = "${fmt(player.currentPosition)} / ${fmt(player.duration)}"
                    } catch (_: Exception) {}
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                if (playerReleased || !::player.isInitialized) return
                try {
                    val p = sb?.progress ?: 0
                    if (player.duration > 0) player.seekTo(player.duration * p / 1000)
                } catch (_: Exception) {}
            }
        })
    }

    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) return true
                toggleControls()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                togglePlay()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (locked) return
                showChannelInfo()
            }
        })

        playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x; touchStartY = event.y
                    volumeStart = getVolume()
                    seekStart = if (!playerReleased && ::player.isInitialized) {
                        try { player.currentPosition } catch (_: Exception) { 0L }
                    } else 0L
                    gestureMode = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (locked) return@setOnTouchListener false
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    if (gestureMode == 0 && (abs(dx) > 40 || abs(dy) > 40)) {
                        gestureMode = if (abs(dx) > abs(dy)) 3 else if (event.y < resources.displayMetrics.heightPixels / 2) 2 else 1
                    }
                    when (gestureMode) {
                        1 -> setVolume(volumeStart - dy / 200f)
                        2 -> setBrightness(brightness - dy / 400f)
                        3 -> {
                            if (playerReleased || !::player.isInitialized) return@setOnTouchListener false
                            try {
                                val dur = if (player.duration > 0) player.duration else 60_000L
                                val target = (seekStart + dx / 4 * 1000).toLong().coerceIn(0, dur)
                                player.seekTo(target)
                                tvCenterHint.apply { text = "快进到 ${fmt(target)}"; visibility = View.VISIBLE }
                            } catch (_: Exception) {}
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    tvCenterHint.visibility = View.GONE
                    handler.postDelayed({ if (!locked) hideControls() }, 3000)
                }
            }
            false
        }
    }

    private fun togglePlay() {
        if (playerReleased || !::player.isInitialized) return
        try {
            if (player.isPlaying) player.pause() else player.play()
            updatePlayButton()
        } catch (_: Exception) {}
    }

    private fun updatePlayButton() {
        if (playerReleased || !::player.isInitialized) return
        try {
            btnPlayPause.setImageResource(if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        } catch (_: Exception) {}
    }

    /** 上一台 / 下一台：切换频道组（不是线路），从该频道最佳线路开始播放 */
    private fun switchChannel(delta: Int) {
        if (groups.isEmpty()) return
        val next = (currentGroupIndex + delta + groups.size) % groups.size
        switchToGroup(next)
    }

    private fun switchToGroup(index: Int) {
        if (index !in groups.indices) return
        currentGroupIndex = index
        currentLineIndex = 0
        triedLines.clear()
        switchingGuard = false
        lineRetryCount = 0
        handler.removeCallbacks(retryTask)
        handler.removeCallbacks(switchTimeoutTask)
        try { if (::player.isInitialized) player.stop() } catch (_: Exception) {}
        playCurrentLine()
    }

    private fun updateTime() {
        if (isSeeking || playerReleased || !::player.isInitialized) return
        try {
            val pos = player.currentPosition
            val dur = player.duration
            if (dur > 0) {
                seekBar.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
                tvTime.text = "${fmt(pos)} / ${fmt(dur)}"
            } else {
                seekBar.progress = 0
                tvTime.text = "${fmt(pos)} / 直播"
            }
        } catch (_: Exception) {}
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
    }

    private fun hideControls() {
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
    }

    private fun getVolume(): Float {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() /
            am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    private fun setVolume(v: Float) {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (v.coerceIn(0f, 1f) * max).toInt(), 0)
        tvCenterHint.apply { text = "音量 ${(v * 100).toInt()}%"; visibility = View.VISIBLE }
    }

    private fun setBrightness(v: Float) {
        brightness = v.coerceIn(0.05f, 1f)
        try {
            val lp = window.attributes
            lp.screenBrightness = brightness
            window.attributes = lp
        } catch (_: Exception) {}
        tvCenterHint.apply { text = "亮度 ${(brightness * 100).toInt()}%"; visibility = View.VISIBLE }
    }

    private fun showMoreMenu() {
        val items = arrayOf("倍速播放", "音轨选择", "字幕选择", "画面比例", "选择线路", "投屏", "复制当前地址", "频道详情")
        AlertDialog.Builder(this)
            .setTitle("播放设置")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showSpeedDialog()
                    1 -> showTrackDialog(C.TRACK_TYPE_AUDIO)
                    2 -> showTrackDialog(C.TRACK_TYPE_TEXT)
                    3 -> showRatioDialog()
                    4 -> showLinePicker()
                    5 -> Toast.makeText(this, "投屏：请确保同一局域网内有 Chromecast / 智能电视设备", Toast.LENGTH_LONG).show()
                    6 -> copyCurrentUrl()
                    7 -> showChannelInfo()
                }
            }.show()
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog.Builder(this).setTitle("倍速播放").setItems(speeds) { _, i ->
            if (!playerReleased && ::player.isInitialized) {
                try {
                    player.setPlaybackSpeed(values[i])
                    Toast.makeText(this, "已设为 ${speeds[i]}", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        }.show()
    }

    private fun showTrackDialog(type: Int) {
        if (playerReleased || !::player.isInitialized) return
        val groupsTrack = try { player.currentTracks.groups.filter { it.type == type } } catch (_: Exception) { emptyList() }
        val labels = mutableListOf<String>("关闭")
        val selections = mutableListOf<Pair<Tracks.Group, Int>?>(null)
        groupsTrack.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label ?: format.language ?: "轨道"
                labels += "$label (${format.sampleMimeType?.substringAfter('/') ?: "未知"})"
                selections += group to i
            }
        }
        AlertDialog.Builder(this).setTitle(if (type == C.TRACK_TYPE_AUDIO) "选择音轨" else "选择字幕")
            .setItems(labels.toTypedArray()) { _, i ->
                val sel = selections.getOrNull(i)
                if (sel == null) clearTracks(type) else selectTrack(sel.first, sel.second)
            }.show()
    }

    private fun clearTracks(type: Int) {
        val b = trackSelector.buildUponParameters().setTrackTypeDisabled(type, true)
        trackSelector.setParameters(b)
    }

    private fun selectTrack(group: Tracks.Group, index: Int) {
        try {
            val override = androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)
            val b = trackSelector.buildUponParameters()
                .setTrackTypeDisabled(group.type, false)
                .setOverrideForType(override)
            trackSelector.setParameters(b)
        } catch (e: Exception) {
            Toast.makeText(this, "切换轨道失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRatioDialog() {
        val items = arrayOf("适应屏幕", "填满屏幕", "裁剪填充")
        val modes = intArrayOf(
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        AlertDialog.Builder(this).setTitle("画面比例").setItems(items) { _, i ->
            playerView.resizeMode = modes[i]
        }.show()
    }

    private fun currentChannel(): Channel? = groups.getOrNull(currentGroupIndex)?.channels?.getOrNull(currentLineIndex)

    private fun copyCurrentUrl() {
        val ch = currentChannel() ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("IPTV", ch.url))
        Toast.makeText(this, "当前线路地址已复制", Toast.LENGTH_SHORT).show()
    }

    private fun showChannelInfo() {
        val g = groups.getOrNull(currentGroupIndex) ?: return
        val ch = currentChannel() ?: return
        val sb = StringBuilder()
        sb.append("分组：${g.group}\n")
        sb.append("来源：${g.originLabel}\n")
        sb.append("线路：${currentLineIndex + 1} / ${g.lineCount}\n")
        sb.append("当前地址：${ch.url}\n")
        if (g.lineCount > 1) {
            sb.append("\n全部线路：\n")
            g.channels.forEachIndexed { idx, c ->
                sb.append("${idx + 1}. ${c.lineStatusText()}  ${c.url.take(60)}\n")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.ok, null).show()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            } catch (e: Exception) {
                Toast.makeText(this, "当前设备不支持画中画", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "需要 Android 8.0 及以上", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFullscreen() {
        requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onPictureInPictureModeChanged(isInPic: Boolean) {
        super.onPictureInPictureModeChanged(isInPic)
        if (isInPic) hideControls() else showControls()
    }

    override fun onStop() {
        super.onStop()
        try {
            if (::player.isInitialized && !playerReleased && Build.VERSION.SDK_INT >= 24 && !isInPictureInPictureMode) {
                player.pause()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        playerReleased = true
        handler.removeCallbacks(updateTask)
        handler.removeCallbacks(switchTimeoutTask)
        handler.removeCallbacks(retryTask)
        handler.removeCallbacks(hideHintTask)
        try {
            if (::player.isInitialized) {
                playerView.player = null
                player.release()
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
