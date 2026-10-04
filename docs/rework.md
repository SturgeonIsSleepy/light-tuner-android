# 轻调 v0.3 实现说明

目标设备为 MT6991、三簇 CPU0–3 / CPU4–6 / CPU7 与 Mali GPU。CPU 档位来自对应 cpufreq policy，GPU 档位与索引来自 `/proc/gpufreqv2/stack_working_opp_table`。GPU 上限写入 GED `custom_upbound_gpu_freq` 索引，下限写入 `custom_boost_gpu_freq` 索引；节点原本只读时，事务临时增加写位，完成后恢复读数前的权限位。

## 读取正确性

用户之前报告重新打开界面后，短暂的系统频率变化被误认为外部调度修改。v0.3 将一次探测改为先读 12 个 CPU/GPU/核心组值，再用相同快照与已保存目标比较。探测打开应用时分离“最近回读”和“保存的目标”，不凭一次不匹配认定被外部调度覆盖，并在几秒内自动复核。

老版本 0.2 保存的本次已确认全局目标可以显示并沿用。为未保存的用户界面读取构造草稿时使用最低频率档，避免将暂时抬高的 scaling_min_freq 锁进新预设。

## 预设和分应用

配置集 v3 含一个全局默认预设及省电、均衡、性能三个可编辑配置。每套配置包含三簇 CPU 频率、GPU 频率、用户后台、系统后台、前台进程和顶层进程四组 cpuset。每个启动器应用可使用三套共享预设之一，也可有一套完整独立配置；无命中应用回到全局默认。

分应用切换监听 Android `wm_set_resumed_activity` 事件，只处理包名变更，不常驻扫描 `/proc` 线程。监听器进入根 cgroup/cpuset；CPU、GPU、四组应用 cpuset 共用短事务与既有配置的替换回滚记录。轻调本身作为系统全局默认运行，编辑界面不由应用规则抢占。

## 原子替换与回滚

一次变更支持在既有接管上直接开始。第二次试用的回滚点是上一套已确认配置；新试用未确认时，守护进程在超时后退回该点。手动结束接管才恢复初次接管时仍属于轻调控制的资源。和其他写入方不同的当前值会被保留。

提交前检查设备 governor 为 sugov_ext，频率为当前实际 OPP 中的有效档位，频率边界排序正确，四组 CPU 非空。读取原始状态不完整或无法取得自动恢复进程时，不开始写入。Root 守护进程最多在一次 20 秒试用中运行，确认后退出；应用自动切换按事件运行，没有循环轮询。

## 外部资料

- [Uperf](https://github.com/yc9559/uperf)：参考按需配置以及状态/动作分离的组织方式。本项目没有引入其代码或把其他芯片的 governor/thermal 脚本移植到 MT6991。
- [Scene 历史 KeepShell](https://raw.githubusercontent.com/helloklf/vtools/master/common/src/main/java/com/omarea/common/shell/KeepShell.kt)：参考复用 Root 会话并检查命令状态。本资料来自公开历史仓库，不代表 Scene 9.4.1 当前实现。
- [Linux devfreq sysfs ABI](https://www.kernel.org/doc/html/latest/admin-guide/abi-testing.html)：说明标准设备频率请求节点与限制含义。此手机的 Mali devfreq governor 为 dummy，轻调因而读取并使用 MediaTek GED 档位接口。
- [Android WindowManager 事件标签](https://android.googlesource.com/platform/frameworks/base/%2B/master/services/core/java/com/android/server/wm/EventLogTags.logtags)：定义 `wm_set_resumed_activity` 的事件字段。