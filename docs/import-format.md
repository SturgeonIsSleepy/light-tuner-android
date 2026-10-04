# 调度配置集 v3

文件是 UTF-8 JSON，最大 65,536 字节。配置根对象的字段固定为 `schemaVersion`、`platform`、`globalPreset`、`presets`、`applications`。应用只在 MT6991、实测 CPU/GPU 档位范围内读取并验证配置。导入只更新草稿，不直接写硬件。

## 三套预设

`presets` 必须恰有 `powersave`、`balanced`、`performance` 三个配置。每套配置只含 `clusters`、`gpu`、`cpuSets`。

`clusters` 有 `policy0`、`policy4`、`policy7` 三项，每项包含唯一 policy 和整数 `minKHz`、`maxKHz`。`minKHz` 不得大于 `maxKHz`。CPU 档位必须在设备读取到的 `scaling_available_frequencies` 中，三簇调速器须为系统 `sugov_ext`。

`gpu` 含 `minKHz`、`maxKHz`，单位 kHz，需匹配这台手机读取到的 Mali GED OPP 栈频率。最高请求受 PowerHAL 与温控等内核限制时，实际有效上限可能更低；导入不会修改温控或电源保护。

`cpuSets` 必须含以下四组，各组是一个或多个无重复的整数数组，元素为 CPU0–7：

- `background`：用户后台
- `systemBackground`：系统后台
- `foreground`：前台进程
- `topApp`：顶层进程

核心列表描述 cpuset 的允许核心，不会关闭 CPU。

## 分应用规则

`applications` 是包名唯一的数组，最多 200 项。每项必含 `packageName`、`enabled`、`preset`，可选 `custom`。`preset` 只能为 `powersave`、`balanced`、`performance`。`custom` 使用和预设相同的完整 CPU、GPU、四组核心字段，存在时作为该应用自己的配置。

系统界面、KernelSU 和轻调自身不能加入为应用规则。其他已安装的启动器应用可以从界面中选择。关闭或删除某项规则后，该应用使用全局默认预设。

## 严格校验

所有对象只接受规范中列出的字段。字段、频率和 CPU 编号都不接受脚本表达式。CPU 和 GPU 频率必须是整数，不能是字符串或小数。缺少字段、未知字段、未知或重复 CPU 簇、重复应用包名、空核心组、不支持的实际档位、超出文件大小限制和尾随的额外对象都会拒绝导入。

旧版 v2 全局文件只带 CPU、前台和后台核心组；导入后写入均衡预设，新 GPU 范围与两类未定义的核心组来自当前设备读数。新格式示例：[mt6991-policy-book.json](../examples/mt6991-policy-book.json)。静态字段由 [JSON Schema](import.schema.json) 校验，实际硬件档位由应用二次校验。