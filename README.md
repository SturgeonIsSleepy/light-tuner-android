# 轻调 · MT6991 全局与分应用调度

Android Root 调度应用，适配天玑 9400+（MT6991），已在 REDMI K80 Ultra、澎湃 OS3、KernelSU 上进行短时设备检查。应用原生 Java 界面读取手机实际的 CPU/GPU 档位。

## 功能

- CPU0–3、CPU4–6、CPU7 的最低和最高频率，所有设备档位可选
- GPU Mali 的最低和最高频率，按 GED OPP 档位设置，保留系统温控与电源限制
- 用户后台、系统后台、前台进程、顶层进程分别选用 CPU0–7
- 可编辑的省电、均衡、性能三套预设，全局默认选择，以及可按应用单独自定义
- 分应用自动切换由 Android 前台活动事件触发，不轮询应用进程列表
- JSON 配置集导入和导出
- CPU/GPU 与核心分配试用、替换、回滚和手动恢复
- 支持 v2 全局文件迁移；分应用规则由 v3 配置集导入

Root 写入采用短时回滚保护。重新试用配置会以最近一次确认的配置作为回滚点，不必先恢复到项目启动前的值。GPU 节点原有权限会在每次受控写入后立即还原。

## 安装和使用

构建 `app` 调试变体，安装 APK，并在 KernelSU 中授予 Root 权限。首次打开时轻调会自动读取设备配置与频率档位。

在「预设配置」中调整频率和四类进程组，可保存预设草稿并试用。确认本次试用后，该配置留到下次调整或重启。在「分应用」中选择已安装的启动器应用，指派三套预设之一，或创建独立设置；启用自动切换后，轻调按前台应用套用策略，未匹配的应用回到全局预设。

Scene 和其他调度工具不要同时写入相同的 CPU/GPU 节点。轻调保留 `sugov_ext`、Power/MiPerf、温控和充电保护，不锁频率节点权限，不停系统服务。启用应用自动切换不会自动启动开机服务；重启后从应用重新启用。

## 配置文件

- [v3 配置集规范](docs/import-format.md)
- [JSON Schema](docs/import.schema.json)
- [完整三预设及天学网示例](examples/mt6991-policy-book.json)
- [实现选择与硬件接口](docs/rework.md)
- [构建和设备验证记录](docs/validation.md)

v3 配置集为 UTF-8 JSON，最大 64 KiB，只包含数据。CPU/GPU 档位和核心数量会与设备读数校验。导入只保存草稿，须在界面同步或试用后才写入设备。旧版 v2 全局文件可导入均衡预设；旧版天学网 Thread-N/nice/锁帧规则不等价迁移，本版分应用策略是前台场景的 CPU/GPU 与四组 cpuset 配置。

## 构建

需要 JDK 17 或以上及 Android SDK 36：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
```

输出文件为 `app/build/outputs/apk/debug/app-debug.apk`，使用调试签名。仓库保存可构建源码、规范和回归测试，不提交私有签名、机器路径、设备日志、测试截图或生成的 APK。