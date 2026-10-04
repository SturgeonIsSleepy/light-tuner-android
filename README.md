# 轻调 · Light Tuner

面向 **天玑 9400+（MT6991）** 的 Android Root 调度工具。可调整 CPU 与 GPU 频率范围、为进程组分配 CPU，并按前台应用切换策略。

> 仅针对 MT6991 设备设计，需要 Android 12 或更新版本和 KernelSU Root。其他芯片组没有适配或验证。调度设置会影响设备运行，请先用安全的配置进行短时试用。

[下载最新版](https://github.com/SturgeonIsSleepy/light-tuner-android/releases/latest) · [配置格式说明](docs/import-format.md) · [天学网示例](examples/mt6991-policy-book.json)

## 功能

- CPU0–3、CPU4–6、CPU7：从设备支持的频率挡位设置上下限
- Mali GPU：读取 MTK GED 实际频率挡位，设置频率范围
- 用户后台、系统后台、前台、顶层进程：分别分配 CPU0–7
- 编辑省电、均衡、性能预设，选择全局默认，或为应用设置独立策略
- 根据 Android 前台应用事件自动切换策略，不持续扫描进程
- 试用、确认、撤回配置，或结束接管并恢复原设置
- 查看当前配置、已应用目标、CPU 调速器和分应用配置

## 获取与安装

1. 从 [Releases](https://github.com/SturgeonIsSleepy/light-tuner-android/releases) 下载 APK。
2. 安装「轻调」，在 KernelSU 中授予 Root 权限。
3. 打开应用，等待读取设备频率和当前调度状态。
4. 首次使用先选择均衡预设试用，在倒计时结束前确认或撤回。

Release 中的 APK 使用 Android 调试签名。若 Android 提示与其他签名版本不一致，需要先卸载旧版本再安装；卸载会清除应用本地保存的预设和日志。

## 使用说明

首页的接管开关用于应用或恢复全局配置。点「检测」只重新读取配置；点接管卡片进入配置详情。选择省电、均衡或性能可编辑相应预设。切换「全局默认」会立即试用并打开倒计时弹窗；确认后保留设置，未确认会自动撤回。

主页状态表示当前读数与已保存目标之间的关系：「配置应用中」表示一致，「配置被修改」表示读数不同，「未启用配置」表示当前没有本应用接管记录。系统温控、电源服务或其他调度工具可能影响读数。

在「分应用策略」里添加已安装的启动器应用，并选择预设或编辑独立设置。启用自动切换后，离开匹配应用会回到全局默认。关闭自动切换后可继续使用全局配置。

日志默认关闭。开启后记录本应用内的操作和错误，最多保留 120 条；关闭时清除日志。日志保存在应用私有数据中，不采集后台运行记录。

## 设备适配与共存

本项目针对 REDMI K80 至尊版上的 MT6991 实现。CPU 簇映射为 CPU0–3、CPU4–6、CPU7；GPU 使用联发科 GED 接口。代码会核对实际频率表和调速器，并在试用期间保留自动回滚保护。

不要让 Scene 或其他工具同时改写相同的 CPU、GPU 或 cpuset 节点；系统没有为这些写入提供统一仲裁。轻调保留设备的 `sugov_ext`、Power/MiPerf、温控和充电保护服务，不修改电压或开关核。启用自动切换不会设置开机自启，设备重启后需要重新开启。

## 配置数据

预设与分应用策略保存在应用私有数据中，使用 MT6991 v3 配置结构。应用当前没有配置文件导入或导出入口；仓库提供的 JSON Schema 和示例用于查看数据格式。示例包含天学网规则，策略只调整应用前台场景的 CPU/GPU 范围和进程组绑核。

旧版的 `Thread-N`、`nice` 和锁帧脚本不能直接转换成本项目策略。

## 从源码构建

需要 JDK 17 或更新版本，以及 Android SDK 36：

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`。详细实现和验证记录见 [docs](docs/)。

## 版本说明

最新版本和安装包请查看 [GitHub Releases](https://github.com/SturgeonIsSleepy/light-tuner-android/releases)。本项目尚未提供生产签名 APK；硬件兼容范围以本文所列设备平台为准。
