# 0.3.0 构建和验证记录

日期：2026-10-04

## 构建和测试

使用 JDK 21、Gradle 8.11.1、Android Gradle Plugin 8.10.1、Android SDK 36。`assembleDebug`、`testDebugUnitTest`、`lintDebug` 均通过。18 项 JVM 测试无失败，覆盖 Profile/PolicyBook 格式、Preset与全局回退、应用自定义覆盖、完整 CPU/GPU 范围、读数与目标分离、重新读取恢复以及无效导入。Lint 无错误，仍有文档化的 Android/Gradle 本地化建议警告。调试 APK 由 Gradle 调试密钥签名。

## K80 Ultra 设备检查

手机 REDMI K80 Ultra，MT6991，澎湃 OS3、Android 16、KernelSU。读取 CPU0–3、CPU4–6、CPU7 全档，Mali GED OPP 有 50 个不重复栈频率，四类 cpuset 与其子组均可读。观察到顶层进程包含 render/main 等更细组，设备 effective_cpus 按顶层 cpuset 收紧。

一次含 GPU 及四组 cpuset 的短事务成功，回读与目标一致。第二次试用直接替换第一套已确认配置，不要求先恢复；回滚退回第一套配置。随后结束接管，CPU、GPU、四组 cpuset 回到测试前状态。GPU 上限 sysfs 节点在该机原为 0444；写入前加写权限位，事务结束后恢复 0444。最终 GED POWERHAL ceiling 回到测试前索引32、floor索引49。

本机短操作显示手机仍可响应；配置试用没有持续复现屏幕卡死。短测试不构成长期功耗或温升基准。应用 RootSession、GPU/四组编辑和完整 APK UI流程没有全套逐项实机回归，运行时包切换完成fixture模拟，但还没有真实天学网连续使用测试。

## 隔离文件事务回归

`tests/root-transaction-test.sh` 将 CPU、GPU、OPP 和 cpuset 路径映射到普通临时文件，自动回滚处理覆盖超时退回已确认策略、手动回到初次基线、未确认替换、应用规则和全局回退、瞬时读取恢复、外部修改值保留、GPU 文件权限还原及原值不完整时拒绝写入。该测试不修改真实 CPU/GPU 调度节点。

实机文件 `test-artifacts/v0.3/physical-test.txt` 和隔离回归记录仅保存在本机；源码仓库不包含设备截图、序列号、路径快照或运行日志。

## 限制

版本不改系统 governor、不停止 Power/MiPerf/温控，不改设备开关核；不提供开机自启，重启后需重新开启自动切换。频率上限是请求值，PowerHAL、温控和电源限制可以进一步限频。Scene 等工具如同时写同一节点，数据会按节点权限和最后请求变化。

未完成长时间温升、耗电、游戏帧率与休眠后台功耗测量。省电/均衡/性能预设是用户可编辑的起点，不代表经多机测量的能耗最优解。