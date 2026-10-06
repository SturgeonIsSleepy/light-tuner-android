# Scene 参考与轻调 0.7.0

## 实际检查的内容

- [Scene 官方主仓库](https://github.com/helloklf/vtools)，master 的 FpsUtils、FloatFpsWatch、线程监视器和场景管理代码
- [官方 scene5 分支](https://github.com/helloklf/vtools/tree/scene5)，检查帧率代码及 JNI 入口，许可证与 master 相同，为 GPL-3.0
- [Scene8.3.7 官方 APK](https://download.omarea.com/scene8/scene_8.3.7.apk)，下载后仅做 DEX／资源静态阅读，没有执行、安装或操作 Scene
- [Scene9 的 MT6991 配置](https://github.com/helloklf/scheduler/tree/profile/scene9/9.0/hp/mt6991)、[Limiter 文档](https://github.com/helloklf/scheduler/blob/profile/scene9/docs/limiter.md)及 [FAS 文档](https://github.com/helloklf/scheduler/blob/profile/scene9/docs/fas.md)

检查时的官方源码树标识为 master `d735955f723329896386ed03e0608ce1bc2082a5`、scene5 `cfd05478b322ba91c315555b56fea95865efec4d`、scheduler/profile `ab497b638c046247df13728ce1dfba5c05fb791e`。这些用于定位上游源码树，没有执行 SHA256 文件校验。

老版及 Scene5 的 FpsUtils 使用 measured_fps 节点或 SurfaceFlinger 1013 的全局计数。Android16 [SurfaceFlinger 源码](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/surfaceflinger/SurfaceFlinger.cpp)中 1013 已标为未使用，因此本版不采用它。屏幕刷新率和显示节点读数也不冒充目标应用 FPS。

Scene8 APK 可看到 FpsADBUtilsBase／FpsADBUtils 调用 daemon 的 SurfaceFlinger `--list`／`--latency` 路径，按应用、Activity、SurfaceView 和 Chrome 子窗口筛选并解析窗口时间。轻调参考这一接口路线，自己实现命令边界、时间戳去重和归属筛选，没有复制反编译代码或链接 Scene daemon。

Scene9 的公开 MT6991 profile 含 sugov_ext 升降频响应、cpuset、top-app uclamp、CPU/GPU、DDR、EAS ioctl 等配置，公开 powercfg 初始化还包含温控／电池限制和绑定挂载改动。轻调本次增加有明确读写节点和恢复记录的响应参数，保留温控、电池保护及系统服务。没有移植其初始化脚本、闭源 FAS／Limiter 算法或未知 ioctl。

本次检查的 scheduler 树未列出 LICENSE，不将公开可读等同于可直接复制发布许可。原生协议读取和调度参数实现均为轻调新增代码，APK 与源码包不包含 Scene 源文件、APK、反编译结果或二进制。没有确认到 Scene8/9 完整可编译应用工程，本版不宣称完整替代 Scene9。

## 帧采集实现

主路径是系统 Perfetto 的 `android.surfaceflinger.frametimeline`。参照 Android16 [事件协议](https://android.googlesource.com/platform/external/perfetto/+/refs/heads/android16-release/protos/perfetto/trace/android/frame_timeline_event.proto)及 [FrameTimeline 实现](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/surfaceflinger/FrameTimeline/FrameTimeline.cpp)，用缓冲帧 PID 对应录制期间观察到的目标进程，再关联 display_frame_token 的显示帧呈现时间。

应用 SurfaceFrame 结束是应用提交／GPU 结束时间，不能直接算作屏幕呈现时间。本版按显示帧结束时间统计呈现 FPS，仅处理具有有效呈现类型的显示帧。丢弃帧不计 FPS；时间线预测过期／未知时，不统计应用帧时间线时长。按窗口分别去重，不累加多个窗口的帧数。直方图 P95/P99 是有界近似，不能替代逐帧导出的原始值。

目标帧不可用或过时，则尝试目标包名对应窗口的 latency 数据，优先 SurfaceView，最多读三个候选窗口。排序、去重，检测环形缓冲覆盖的缺失。此路径没有框架卡顿类型、预期帧时间线或应用帧时间线，相关值保持缺失。窗口不可归属或接口不可读时，不输出伪造 FPS。

采集限于本次录制，使用 2 MiB Trace 缓冲、最多 32 MiB 临时文件、64 个窗口、4096 个关联槽和 200000 条目标帧明细。系统跟踪文件优先在系统跟踪目录中以本场 UUID 文件名暂存，回退到 Root 私有目录，正常结束删除，不进入本地历史或 AI 导出。只保留目标窗口的过滤结果。异常退出后下一次读取或开始录制会停止仍可证明属于此记录的 Perfetto 进程，不影响其他工具的跟踪。

本版没有 sched_switch 核心驻留、迁移轨迹、Scene9 FAS、动态 Limiter、DDR／EAS ioctl 或设备专用温控接管。线程仍是一秒区间采样。所有功能只完成源码与编译交付，未作手机、虚拟机、帧源兼容性、回滚、悬浮窗或耗电验证。
