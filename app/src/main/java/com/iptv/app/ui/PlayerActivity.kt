package com.iptv.app.ui

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import com.iptv.app.core.SourceCollector
import com.iptv.app.data.Prefs
import com.iptv.app.model.Channel
import kotlin.math.abs

@SuppressLint("UnsafeOptInUsageError")
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var drawer: DrawerLayout
    private lateinit var adapter: ChannelAdapter
    private lateinit var tvTitle: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvCenterHint: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton

    private var channels: List<Channel> = emptyList()
    private var currentIndex = 0
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

    private val updateTask = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                updateTime()
                handler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val json = intent.getStringExtra("channels") ?: "[]"
        channels = try {
            Gson().fromJson(json, object : TypeToken<List<Channel>>() {}.type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
        currentIndex = intent.getIntExtra("index", 0).coerceIn(0, (channels.size - 1).coerceAtLeast(0))

        findViews()
        setupDrawer()
        setupPlayer()
        setupControls()
        setupGestures()
        playCurrent()

        handler.post(updateTask)
    }

    private fun findViews() {
        playerView = findViewById(R.id.playerView)
        drawer = findViewById(R.id.drawer)
        tvTitle = findViewById(R.id.tvTitle)
        tvTime = findViewById(R.id.tvTime)
        tvCenterHint = findViewById(R.id.tvCenterHint)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
    }

    private fun setupDrawer() {
        val recycler = findViewById<RecyclerView>(R.id.recyclerDrawer)
        adapter = ChannelAdapter(channels.toMutableList(), onPlay = { ch ->
            currentIndex = channels.indexOfFirst { it.url == ch.url && it.name == ch.name }.coerceAtLeast(0)
            playCurrent()
            drawer.closeDrawers()
        })
        adapter.darkTheme = true
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val etSearch = findViewById<EditText>(R.id.etChannelSearch)
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().trim()
                adapter.submit(if (q.isBlank()) channels else channels.filter { it.name.contains(q, true) })
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun setupPlayer() {
        val ctx = this
        val ua = Prefs.getString(ctx, Prefs.KEY_UA, SourceCollector.DEFAULT_UA)
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

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
                        when (state) {
                            Player.STATE_BUFFERING -> tvCenterHint.apply { text = "缓冲中…"; visibility = View.VISIBLE }
                            Player.STATE_READY -> {
                                tvCenterHint.visibility = View.GONE
                                updateTime()
                            }
                            Player.STATE_ENDED -> { tvCenterHint.apply { text = "播放结束"; visibility = View.VISIBLE } }
                        }
                        updatePlayButton()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayButton()
                        window.setFlags(
                            if (isPlaying) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0,
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        )
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        tvCenterHint.apply { text = "播放失败：${error.message?.take(80)}"; visibility = View.VISIBLE }
                        Toast.makeText(ctx, "播放失败，尝试切换线路或检查网络", Toast.LENGTH_LONG).show()
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

    private fun playCurrent() {
        if (channels.isEmpty()) {
            Toast.makeText(this, "频道列表为空", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val ch = channels[currentIndex]
        tvTitle.text = "${ch.name}  ·  ${ch.group}"
        Prefs.putInt(this, Prefs.KEY_LAST_PLAYED, currentIndex)
        val item = MediaItem.Builder()
            .setUri(ch.url)
            .setTag(ch)
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        tvCenterHint.apply { text = "正在连接：${ch.name}"; visibility = View.VISIBLE }
        updatePlayButton()
    }

    private fun setupControls() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnList).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
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
                if (fromUser && player.duration > 0) {
                    tvTime.text = "${fmt(player.currentPosition)} / ${fmt(player.duration)}"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                val p = sb?.progress ?: 0
                if (player.duration > 0) player.seekTo(player.duration * p / 1000)
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
                    seekStart = player.currentPosition
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
                            val dur = if (player.duration > 0) player.duration else 60_000L
                            val target = (seekStart + dx / 4 * 1000).toLong().coerceIn(0, dur)
                            player.seekTo(target)
                            tvCenterHint.apply { text = "快进到 ${fmt(target)}"; visibility = View.VISIBLE }
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
        if (player.isPlaying) player.pause() else player.play()
        updatePlayButton()
    }

    private fun updatePlayButton() {
        btnPlayPause.setImageResource(if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private fun switchChannel(delta: Int) {
        if (channels.isEmpty()) return
        currentIndex = (currentIndex + delta + channels.size) % channels.size
        playCurrent()
    }

    private fun updateTime() {
        if (isSeeking || !::player.isInitialized) return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur > 0) {
            seekBar.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
            tvTime.text = "${fmt(pos)} / ${fmt(dur)}"
        } else {
            seekBar.progress = 0
            tvTime.text = "${fmt(pos)} / 直播"
        }
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
        window.attributes = window.attributes.apply { screenBrightness = brightness }
        tvCenterHint.apply { text = "亮度 ${(brightness * 100).toInt()}%"; visibility = View.VISIBLE }
    }

    private fun showMoreMenu() {
        val items = arrayOf("倍速播放", "音轨选择", "字幕选择", "画面比例", "投屏", "复制当前地址", "频道详情")
        AlertDialog.Builder(this)
            .setTitle("播放设置")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showSpeedDialog()
                    1 -> showTrackDialog(C.TRACK_TYPE_AUDIO)
                    2 -> showTrackDialog(C.TRACK_TYPE_TEXT)
                    3 -> showRatioDialog()
                    4 -> Toast.makeText(this, "投屏：请确保同一局域网内有 Chromecast / 智能电视设备", Toast.LENGTH_LONG).show()
                    5 -> copyCurrentUrl()
                    6 -> showChannelInfo()
                }
            }.show()
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog.Builder(this).setTitle("倍速播放").setItems(speeds) { _, i ->
            player.setPlaybackSpeed(values[i])
            Toast.makeText(this, "已设为 ${speeds[i]}", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun showTrackDialog(type: Int) {
        val groups = player.currentTracks.groups.filter { it.type == type }
        val labels = mutableListOf<String>("关闭")
        val selections = mutableListOf<Pair<Tracks.Group, Int>?>(null)
        groups.forEach { group ->
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

    private fun copyCurrentUrl() {
        val ch = channels.getOrNull(currentIndex) ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("IPTV", ch.url))
        Toast.makeText(this, "地址已复制", Toast.LENGTH_SHORT).show()
    }

    private fun showChannelInfo() {
        val ch = channels.getOrNull(currentIndex) ?: return
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setMessage("分组：${ch.group}\n来源：${ch.source}\n地址：${ch.url}\n状态：${if (ch.isOk) "可用" else "未检测"}")
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
        if (Build.VERSION.SDK_INT >= 24 && !isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateTask)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
