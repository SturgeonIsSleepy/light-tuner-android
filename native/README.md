# 原生工作进程

`lightcore.c` 是仅用于 Android arm64 的 Root 工作进程，使用 libc，不加载 Java 虚拟机。

- `record <Root目录> <秒数> <包名或*>`：限时 1 Hz 采样，最多 8192 个线程实例，线程增量时间线最多 200000 行
- `threads <Root目录>`：按已配置应用和版本扫描，每 3 秒处理新线程
- `restore-threads <Root目录>`：恢复仍可核对身份及本项目写入值的线程

频率曲线和线程统计保存在 Root 私有目录，界面按需复制到应用私有数据。线程匹配记录采用索引，规则数组及历史恢复记录有界。没有规则时工作进程退出。版本映射包含 APK 路径，路径失效后不沿用旧版本规则，不能由同名的隔离进程猜测宿主。

使用 NDK 28.2.13676358 编译：`tools/build-native.ps1 -Sdk <SDK目录>`，产物为 `app/src/main/assets/lightcore`。编译目标 Android 31 arm64，PIE，16 KiB 段对齐；源码包附带已编译产物便于离线构建 APK。此说明不代表实际内存、耗电或后台可靠性已测量。

线程时间线为 elapsedMs、pid、tid、startTicks、cpuPercent、intervalMs、lastCpu，仅保留有 CPU 时间增量的观测区间。lastCpu 取自 `/proc/<pid>/task/<tid>/stat` 第 39 字段，是最近运行核心，不表示区间驻留。见 [Linux procfs 文档](https://www.kernel.org/doc/html/latest/filesystems/proc.html)。首个样本作为基线，汇总每十秒及正常结束时更新，异常退出可能只保留最后检查点。界面仅在录制结束后复制原始时间线，避免实时复制大文件。

0.7.0 新增 frames.h 协议读取和 metrics.h 电池侧采样，随 lightcore 一起编译，没有引入 SDK／额外原生库。优先系统 Perfetto FrameTimeline，关联显示帧实际呈现，缺失／过时时尝试目标窗口 latency。临时 Trace 文件只由本场录制使用，正常结束删除，中断后按本场 UUID 清理，不触及其他工具。Root 文件和进程有界；未开启悬浮窗时没有 Android 前台服务。
