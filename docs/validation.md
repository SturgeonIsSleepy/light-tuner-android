# 0.3.0 构建和验证记录

日期：2026-10-04

## 构建和测试

使用 JDK 21、Gradle 8.11.1、Android Gradle Plugin 8.10.1、Android SDK 36。`assembleDebug`、`testDebugUnitTest`、`lintDebug` 均通过。19 项 JVM 测试无失败，覆盖 Profile/PolicyBook 格式、Preset与全局回退、应用自定义覆盖、完整 CPU/GPU 范围、读数与目标分离、重新读取恢复以及无效导入。Lint 无错误，仍有文档化的 Android/Gradle 本地化建议警告。调试 APK 由 Gradle 调试密钥签名。

## K80 Ultra 设备检查

手机 REDMI K80 Ultra，MT6991，澎湃 OS3、Android 16、KernelSU。读取 CPU0–3、CPU4–6、CPU7 全档，Mali GED OPP 有 50 个不重复栈频率，四类 cpuset 与其子组均可读。观察到顶层进程包含 render/main 等更细组，设备 effective_cpus 按顶层 cpuset 收紧。

一次含 GPU 及四组 cpuset 的短事务成功，回读与目标一致。第二次试用直接替换第一套已确认配置，不要求先恢复；回滚退回第一套配置。随后结束接管，CPU、GPU、四组 cpuset 回到测试前状态。GPU 上限 sysfs 节点在该机原为 0444；写入前加写权限位，事务结束后恢复 0444。最终 GED POWERHAL ceiling 回到测试前索引32、floor索引49。

原生界面试用及确认完成实机检查；未确认的一次试用在真实节点上自动超时返回。退出并重进后，无需点击刷新，自动复核显示「配置已保存，当前读数一致」。应用选择器能读取启动器应用列表。

真实事件监听以系统设置应用为测试对象：进入设置后 CPU0 上限切换为 1000 MHz、GPU 请求上限 832 MHz、用户后台核心变为 CPU0–4；回到桌面后恢复全局的 800 MHz、780 MHz、CPU0–3。对应 phase 为 active、match 为 1、前台包名正确。测试后停止监听并恢复原值。没有进行天学网的真实连续使用或功耗效果验证。

后台监听只保留 shell 与 logcat 两个进程。一次静置采样的 PSS 分别为 1681、1450 KiB，合计约 3.1 MB；5 秒内两进程的 CPU 计时均未增加。该数值不包含界面缓存进程、切换期间临时进程，不能当作整机功耗或长期待机数据。短操作未复现屏幕卡死，不构成长期稳定性保证。

## 隔离文件事务回归

`tests/root-transaction-test.sh` 将 CPU、GPU、OPP 和 cpuset 路径映射到普通临时文件，自动回滚处理覆盖超时退回已确认策略、手动回到初次基线、未确认替换、应用规则和全局回退、瞬时读取恢复、外部修改值保留、GPU 文件权限还原及原值不完整时拒绝写入。该测试不修改真实 CPU/GPU 调度节点。

实机文件 `test-artifacts/v0.3/physical-test.txt` 和隔离回归记录仅保存在本机；源码仓库不包含设备截图、序列号、路径快照或运行日志。

## 限制

版本不改系统 governor、不停止 Power/MiPerf/温控，不改设备开关核；不提供开机自启，重启后需重新开启自动切换。频率上限是请求值，PowerHAL、温控和电源限制可以进一步限频。Scene 等工具如同时写同一节点，数据会按节点权限和最后请求变化。

未完成长时间温升、耗电、游戏帧率与休眠后台功耗测量。省电/均衡/性能预设是用户可编辑的起点，不代表经多机测量的能耗最优解。