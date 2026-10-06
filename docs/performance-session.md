# 应用性能记录与 AI 分析包

## 使用流程

首页进入「应用与性能记录」，搜索应用并打开详情。先开启并确认全局接管；若需要应用频率预设，配置后在应用列表开启自动切换。线程规则独立于应用频率预设，按应用版本匹配。

在「性能记录」选择 15–600 秒并开始，切到目标应用进行正常使用，结束后回到轻调读取结果。录制结束即停止采样；界面打开在录制页时每 5 秒刷新进度，离开页面不刷新。最多同时一场录制，不因切换其他应用而改变目标包名。

在结果中进入「分析应用线程」，同一应用的多个进程合并展示并保留进程名。点开线程查看占用区间，点击区间查看附近 CPU/GPU 频率。通过平均、峰值、时间线和线程用途判断，再查看候选策略或手动设置核心。候选修改经过 20 秒试用，确认后保存；取消、超时或未确认的重启恢复都撤回未确认规则。关键线程标识来自 PID/TID 身份与名称判断，不保证覆盖所有关键线程。

本地建议条件为汇总至少 15 秒、至少 10 次观测、平均 CPU 占用 ≥30%，排除已识别关键线程。天学网具体 `Thread-N` 候选允许 CPU0–3，其他候选允许 CPU0–6，用户可调整。高占用仅作为筛选条件，不证明线程无用，也不证明该限制有效。不自动生成所有 Thread-N 的通配规则，不限制整个应用，不添加 nice／uclamp／帧率规则。

未启用接管时保存的线程规则为草稿，确认接管后在应用「线程策略」页试用。一个线程规则匹配指定包名、版本、线程名称；同名线程的所有实例均匹配。仍受所在 cpuset 约束。删除及停用也经过试用，以便保留上一套已确认规则。

历史保存在应用私有目录 `sessions/<录制 UUID>`，全应用合计最多 12 场，有效完整、手动停止和中断记录均可保留。删除或更早记录清理后不会因读取同一 Root 记录自动重建历史。清除应用数据会删除本地历史。导出 ZIP 保存到你在系统文件选择器中指定的位置，不会联网。

## ZIP 文件

| 文件 | 内容 |
| --- | --- |
| summary.json | 录制说明、汇总时长、统计线程、关键线程标记和候选原因 |
| threads.tsv | 原始线程汇总，包括包名、版本、进程、UID、PID、TID、启动时间、名称、平均／峰值、掩码和观测次数 |
| frequency.csv | 采样时刻和 CPU 三簇、GPU 瞬时频率，单位 kHz |
| thread-timeline.csv | 有 CPU 时间增量的线程观测区间，可选；旧版记录没有该文件 |
| profile-before.json | 录制前实际读取的三簇 CPU、GPU 范围及四类进程核心集合 |
| README.txt | 中文分析提示、字段限制、数据缺失说明 |

`summary.json` 的 schemaVersion 为 2；0.6.0 历史分析包为 1。`context` 包含目标包名和版本、开始时间、平台、型号、Android／内核、窗口状态、录制前实际配置、已保存应用配置及该应用线程规则。startedAt 为 Unix 毫秒；不包含设备序列号、屏幕、应用内容或硬件唯一标识。用户导出的本机包包含进程名和 PID/TID/UID，和云端的汇总上传不是同一格式。

汇总线程字段为 packageName、versionCode、process、pid、tid、startTicks、threadName、averageCpuPercent、peakCpuPercent、observedCpuTimeMs、allowedCpuMask、samples、criticalHeuristic、candidateReason。versionCode 和 startTicks 用字符串保存，避免 JSON 消费端的整数精度损失；PID/TID 仅是此场记录身份，不能用于下次启动的规则匹配。

`durationMs` 为线程汇总时长，`elapsedMs` 为最后记录状态中的采样时长。正常结束两者基本一致；中断时汇总可能只到最后一个十秒检查点，时间线和频率文件可能比汇总多几秒。observedCpuTimeMs 从汇总占用与汇总时长计算，首个样本作为基线，不能回补首次观察前的线程 CPU 时间。

## CSV 和指标边界

频率 CSV 列为 elapsedMs、cpu0KHz、cpu4KHz、cpu7KHz、gpuKHz，GPU 不可读为 -1。频率是瞬时值，不是精确驻留，属于整机 CPU 簇和 GPU，不是某线程的独立频率。

线程时间线列为 elapsedMs、pid、tid、startTicks、cpuPercent、intervalMs、lastCpu。CPU 100% 代表一个核心，使用两次 stat 的 utime／stime 差除以实际观测间隔。只保存有增量的区间，缺行不代表确定为零；intervalMs 可能超过一秒，按实际间隔解释。lastCpu 是 `/proc/.../stat` 的最近运行处理器，不是区间运行核心或驻留，也不能用它计算真实迁移次数。时间线最多 200000 行，达到上限时 summary 的 timelineLimitReached 为 true。线程实例上限为 8192，达到上限时 threadLimitReached 为 true。

coreResidency 始终为 null；旧记录或未读到相关数据时 frames／power 为 null。0.6.0 没有 FPS／功率／温度采集；0.7.0 的实际可用字段与来源见下节，缺失值仍保持 null。当前没有 sched_switch、SoC 温度或精确频率暴露。AI 应说明未知，不能根据频率和线程百分比编造这些数据，不能仅凭本场记录声称调度已降低功耗。

## 云端关系

AI 分析包不会发到 Cloudflare。设置中的「共享策略」只在确认上传后发送旧版白名单汇总：应用包名、版本、线程名、平均／峰值、实例数、时长和随机编号。云端下载规则只预览，匹配本机安装版本后仍需试用并确认。协议继续使用[线程共享格式](thread-sharing.md)，本次没有更改服务端数据格式。

参考：[Linux procfs 字段说明](https://www.kernel.org/doc/html/latest/filesystems/proc.html)。

## 0.7.0 窗口帧与电池数据

录制时可开启悬浮窗，用户手动允许权限后显示最近已记录的每秒帧数和电池侧读数。最近帧数约滞后两个秒桶，不是无延迟的即时帧率；悬浮窗退出不停止 Root 采集。结果页选择窗口查看每秒呈现帧数、活动区间平均 FPS、P95/P99 呈现间隔、框架卡顿比例和丢弃帧。点时间可查看最多 20 帧明细及一秒附近最高占用线程；完整明细在导出中，不从一秒线程采样推断单帧原因。

frames.csv 字段为 elapsedMs、layerId、pid、appToken、displayToken、presentNs、intervalMs、appTimelineMs、expectedTimelineMs、jankMask、presentType。presentNs 是与目标应用缓冲帧关联的显示帧实际呈现时间，使用 BOOTTIME 时钟；没有有效呈现时为 0。intervalMs 是同一窗口相邻不同呈现时间的间隔，缺失为 -1。appTimelineMs 是框架应用帧时间线长度，不是纯 CPU 或 GPU 执行时间；预测无效、被丢弃或 latency 路径时为 -1。expectedTimelineMs 缺失为 -1。tokens 及纳秒时刻应按 64 位整数／字符串处理，不转低精度数字用于重新关联。

presentType 对应系统：1 按时、2 延后、3 提前、4 丢弃、0／5 未知。jankMask 是系统 JankType 位掩码，1 为无卡顿，>1 为系统分类的卡顿，0／-1 未知。卡顿比例按具有有效分类的应用缓冲帧计算，包含被丢弃帧，不是 Scene 的 Jitter。latency 路径只有呈现时间，jank 为 -1。不同窗口不累加 FPS，默认选有效呈现最多的窗口，也可手动选择。

frame-layers.tsv 保存窗口 ID、PID、来源、名称、有效呈现／卡顿／分类／丢弃数量、活动区间平均及近似分位数。平均 FPS 为 (呈现次数−1)/(末次−首次呈现时间)，不把开始前的等待计入活动区间。P95/P99 呈现间隔直方图精度为 1ms，≥1000ms 饱和；帧时间线精度 0.1ms，≥100ms 饱和。frames.csv 原始值可重新精确计算。frame-bins.tsv 为 second、layer、presented、jank、classified，缺行没有观察到此窗口新帧，静止／后台的零帧不等于卡顿；部分末秒按已观察数量显示。

metrics.csv 为 elapsedMs、currentUa、voltageUv、batteryDeciC、charging、gpuUtilPercent，空字段为缺失。currentUa 保留原符号，厂商符号方向不能直接推定；电池侧功率用 abs(currentUa×voltageUv)/1e12 估算。charging=1 包含电池 Charging／Full 或已识别的 USB／AC／无线外部供电，0 为未充电，-1 为状态未知。只有 0 计入 averageDischargeBatterySideW。电池温度除以 10 得 ℃，不是 SoC 温度。USB 不充电但供电时，电池电流不能代表整机输入功率。

frames.partial 表示缺包、槽位／PID 上限、latency 环形缓冲覆盖或中断。limitReached 表示 Trace 文件／帧明细达到上限。窗口最多 64 个，帧明细最多 200000 行，临时系统 Trace 缓冲 2MiB、文件 32MiB。临时文件优先使用系统标准跟踪目录，文件名绑定录制 UUID，不导出／归档原始全系统 Trace，正常结束或发现中断后清理本项目的该文件。没有对应接口时保持缺失，不用刷新率、GPU 上限或其他应用帧数替代。
