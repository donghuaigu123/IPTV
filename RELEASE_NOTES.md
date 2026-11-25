# IPTV v1.2.0 — 稳定性修复版

全面排查并修复频繁闪退问题（尤其是「有效性检测」与「开始播放」两个场景）。

## 闪退根因与修复

| 问题 | 触发场景 | 修复 |
|------|---------|------|
| 通过 Intent 传递整份频道列表 JSON（数千条 >1MB），超出 Binder 事务上限 | 点击播放 | 改为进程内总线（PlaylistBus）传递，Intent 只传下标 |
| 采集/检测进度回调运行在 IO 线程，直接更新 TextView/ProgressBar | 有效性检测 | 统一切回主线程（runOnUiThread）更新 UI |
| RecyclerView 复用时 `holder.adapterPosition` 为 -1，访问 `items[-1]` | 滚动数据源列表 | 改用 `bindingAdapterPosition` 并做边界检查，绑定前清除旧监听 |
| `onStop()` 访问尚未初始化的 player | 快速退出播放 | 增加 `isInitialized` / `playerReleased` 双重保护 |
| 采集完成后自动检测时，`startCheck()` 内部 `job?.cancel()` 取消了自身协程 | 采集完成 | 抽取共用 `doCheck()` suspend 函数，同一协程内顺序执行 |
| 播放器释放后，监听器/手势/定时器仍访问 player | 播放中切后台/旋转 | 全路径增加状态保护与异常捕获 |
| 畸形播放地址导致 `setMediaItem/prepare` 抛异常 | 播放坏源 | URL 合法性校验 + try-catch，错误改为界面提示 |
| `takePersistableUriPermission` 在部分机型抛 SecurityException | 选择下载目录 | 捕获异常并降级 |
| 导入流程异常未捕获导致协程崩溃 | 导入订阅 | 全流程 try-catch + 空结果提示 |

## 其他完善
- 解析器识别并处理被误当作频道名的「更新时间戳」（部分源的 EXTINF 标题位）
- 修正频道名归一化去重逻辑
- 采集、检测、导出、导入全流程增加异常兜底与友好提示

## 验证情况
- Kotlin/Java 编译通过（仅弃用 API 警告，无错误）
- APK 签名有效，versionCode 3 / versionName 1.2.0
- DEX 类完整性校验通过，无缺失类
- 用真实采集数据（iptv-org / Guovin，约 1000 条）验证解析与去重逻辑无异常

## 安装
下载 `IPTV-v1.2.0.apk`，在 Android 5.0+ 设备安装（需允许安装未知来源应用）。升级安装会保留你的自定义源与设置。
