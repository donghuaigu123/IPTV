# IPTV for Android

一款集 **直播源自动采集、可用性检测、多格式导出** 与 **全功能播放器** 于一体的 Android 应用。

## 功能特性

### 采集中心
- 内置 16 个公开直播源（参考 [HerbertHe/iptv-sources](https://github.com/HerbertHe/iptv-sources) 与 [Guovin/iptv-api](https://github.com/Guovin/iptv-api) 的上游清单）
- 并发抓取、频道名归一化、去重合并、别名识别（CCTV/卫视等）
- 自动可用性检测：延迟、下载速度、响应特征三重判定
- 支持自定义订阅源（M3U / TXT / TVBox JSON）、可单独启停

### 多格式导出
一键导出到用户选择任意目录（Android SAF，可选外部 SD 卡）：

| 格式 | 说明 |
|------|------|
| M3U / M3U8 | 标准播放列表，含 tvg-id / tvg-logo / group-title |
| TXT | 通用 `频道名,地址` 格式 |
| JSON | 结构化数据，含检测结果 |
| TVBox | TVBox 订阅配置 |
| XSPF | XML 播放列表 |
| CSV | 表格数据，可用 Excel 打开 |

### 播放器
- 基于 **Media3 ExoPlayer**，支持 HLS / DASH / SmoothStreaming / RTSP / 渐进式流
- 频道列表抽屉、搜索、上一个/下一个
- 播放/暂停、拖动进度、倍速（0.5x–2.0x）、音轨/字幕选择、画面比例
- 手势：双击播放暂停、左右滑动进度、上下滑动音量/亮度、长按频道信息
- 画中画（PIP）、全屏、平板/手机自适应、后台锁屏唤醒策略
- 解码器偏好（自动/硬解优先/软解优先）、缓冲策略（标准/低延迟/大缓冲）
- 播放源导入：M3U 文件 / TXT / 网址 / 剪贴板 / TVBox 订阅

## 编译

环境要求：JDK 17+、Android SDK Platform 34、Gradle 8.7

```bash
# 方式一：命令行
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk

# 方式二：Android Studio
# 打开项目目录，点击 Run
```

## 技术栈

- Kotlin + AndroidX / Material Components
- Media3 ExoPlayer 1.4.1（HLS / DASH / SS / RTSP）
- OkHttp 4.12 + Kotlin Coroutines
- Gson + DocumentFile (SAF)

## 免责声明

本软件仅为技术工具，不存储、不提供任何直播内容。请使用合法授权的直播源，并遵守所在地区的法律法规。

## 许可证

[MIT License](LICENSE)
