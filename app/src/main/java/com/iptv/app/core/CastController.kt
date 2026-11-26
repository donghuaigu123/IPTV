package com.iptv.app.core

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 投屏控制器：串联 DLNA 设备发现 + 本地流代理 + SOAP 播放控制。
 *
 * 投屏采用「本地流代理」模式：电视不直接访问直播源，而是访问本机的 StreamProxy，
 * 由手机用正确的 UA / Referer 拉取并（对 m3u8）改写后转发，解决防盗链与兼容性问题。
 *
 * 状态回调 [listener] 均在主线程；所有耗时操作在 IO 协程中执行。
 */
class CastController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener
) {
    interface Listener {
        fun onCastStateChanged(state: State, deviceName: String = "")
        fun onBusy(busy: Boolean, message: String)
    }

    enum class State { IDLE, CONNECTING, CASTING }

    private val proxy = StreamProxy()
    private var device: DlnaManager.DlnaDevice? = null
    private var currentToken: String = ""
    private var currentTitle: String = ""
    private var currentUrl: String = ""
    private var currentUa: String = ""
    @Volatile var state: State = State.IDLE
        private set

    /** 是否正在投屏 */
    fun isCasting(): Boolean = state == State.CASTING

    /** 扫描设备；结果通过 [onResult] 主线程回调 */
    fun discover(onResult: (List<DlnaManager.DlnaDevice>) -> Unit) {
        if (state == State.CONNECTING) return
        listener.onBusy(true, "正在搜索同一局域网的电视 / 机顶盒…")
        scope.launch {
            val devices = withContext(Dispatchers.IO) {
                DlnaManager.discover(context, 3200)
            }
            withContext(Dispatchers.Main) {
                listener.onBusy(false, "")
                onResult(devices)
            }
        }
    }

    /** 连接设备并开始投屏；[url] 为直播源地址，[ua] 为可用 UA */
    fun connect(target: DlnaManager.DlnaDevice, url: String, ua: String, title: String) {
        state = State.CONNECTING
        listener.onCastStateChanged(State.CONNECTING, target.name)
        listener.onBusy(true, "正在连接 ${target.name}…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // 1. 启动本地流代理
                    if (!proxy.start()) return@withContext Result.failure(Exception("流代理启动失败"))
                    // 2. 生成该频道的专属播放地址（写入 UA/Referer）
                    val playUrl = proxy.configure(url, ua)
                        ?: return@withContext Result.failure(Exception("未获取到局域网 IP，请确认已连接 Wi-Fi"))
                    // 3. 让电视加载并播放
                    val ok = DlnaManager.playUrl(target, playUrl, title)
                    if (!ok) return@withContext Result.failure(Exception("设备拒绝播放，请确认电视在同一网络并支持 DLNA"))
                    Result.success(playUrl)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            withContext(Dispatchers.Main) {
                listener.onBusy(false, "")
                val urlResult = result.getOrNull()
                if (urlResult != null) {
                    device = target
                    currentTitle = title
                    currentUrl = url
                    currentUa = ua
                    state = State.CASTING
                    listener.onCastStateChanged(State.CASTING, target.name)
                    Toast.makeText(context, "已投屏到 ${target.name}", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "投屏失败"
                    proxy.stop()
                    state = State.IDLE
                    listener.onCastStateChanged(State.IDLE)
                    Toast.makeText(context, "投屏失败：$msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 投屏中切换频道 / 线路 */
    fun switchSource(url: String, ua: String, title: String) {
        val target = device ?: return
        if (state != State.CASTING) return
        listener.onBusy(true, "正在切换投屏频道…")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val playUrl = proxy.configure(url, ua)
                        ?: return@withContext false
                    // 先停止再播放，强制电视重新拉取播放列表，绕过缓存
                    DlnaManager.stop(target)
                    DlnaManager.playUrl(target, playUrl, title)
                } catch (_: Exception) { false }
            }
            withContext(Dispatchers.Main) {
                listener.onBusy(false, "")
                if (ok) {
                    currentTitle = title
                    currentUrl = url
                    currentUa = ua
                    Toast.makeText(context, "投屏已切换：$title", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "投屏切换失败，请重试或断开", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 投屏播放/暂停切换；返回当前是否处于播放态由调用方维护 */
    fun play() {
        val target = device ?: return
        scope.launch { withContext(Dispatchers.IO) { DlnaManager.play(target) } }
    }

    fun pause() {
        val target = device ?: return
        scope.launch { withContext(Dispatchers.IO) { DlnaManager.pause(target) } }
    }

    /** 设置电视端音量 */
    fun setVolume(volume: Int) {
        val target = device ?: return
        scope.launch { withContext(Dispatchers.IO) { DlnaManager.setVolume(target, volume) } }
    }

    /** 断开投屏 */
    fun disconnect() {
        val target = device
        scope.launch {
            withContext(Dispatchers.IO) {
                try { if (target != null) DlnaManager.stop(target) } catch (_: Exception) {}
                proxy.stop()
            }
            withContext(Dispatchers.Main) {
                device = null
                state = State.IDLE
                listener.onCastStateChanged(State.IDLE)
                Toast.makeText(context, "已断开投屏", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Activity 销毁时同步清理（不弹 Toast、不弹窗），fire-and-forget 停止设备 */
    fun release() {
        val target = device
        if (target != null) {
            scope.launch { withContext(Dispatchers.IO) { try { DlnaManager.stop(target) } catch (_: Exception) {} } }
        }
        proxy.stop()
        device = null
        state = State.IDLE
    }

}
