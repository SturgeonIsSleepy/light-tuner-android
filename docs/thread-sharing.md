# 线程配置与共享协议 v1

本地对象：`{"schemaVersion":1,"threads":[...]}`，最多 256 条。每条包含且仅包含如下字段：

```json
{
  "packageName": "com.up366.mobile",
  "versionCode": "123",
  "threadName": "Thread-*",
  "cpuMask": 15,
  "enabled": true
}
```

版本是已安装应用整数版本号字符串，123 仅为格式示例。线程名采用内核 comm，最多 64 字符，无控制字符，支持通配符，不作为 shell 命令执行。相同包名、版本和线程名保存时替换。CPU 掩码 1–255，各位为 CPU0–7，15 表示 CPU0–3，240 表示 CPU4–7。不支持轻调新旧包名、SystemUI、KernelSU。

## 服务

地址：`https://lighttuner-threads.zhx100560.workers.dev`，HTTPS UTF-8 JSON，请求／响应最多 256 KiB。

- `POST /v1/reports`：上传结束录制的统计
- `GET /v1/catalog`：下载发布规则
- `GET /admin/catalog`：管理员读取汇集
- `PUT /admin/policies`：管理员替换发布集

管理员请求需 `Authorization: Bearer <管理员密钥>`，密钥不嵌入客户端。

上传对象：

```json
{
  "schemaVersion": 1,
  "platform": "mt6991",
  "sessionId": "00000000-0000-4000-8000-000000000001",
  "clientId": "00000000-0000-4000-8000-000000000002",
  "durationMs": 60000,
  "threads": [{
    "packageName": "com.up366.mobile",
    "versionCode": "123",
    "threadName": "Thread-7",
    "averageCpu": 20.5,
    "peakCpu": 87.0,
    "instances": 1
  }]
}
```

最多 1000 种线程，时长 1–660 秒。相同名称实例平均占用相加，峰值取单实例最大值。未归属线程不上传。录制编号幂等，不重复累计，汇集平均值按统计时长加权。只存储上述统计字段，不保存额外的 UID、PID/TID 或命令行。客户端编号是随机生成的安装标识，不是硬件 ID。每个编号每天最多上传 20 次，另有 6 次／分钟的入口限速。

发布对象 `{"policies":[规则...]}` 最多 200 条。下载返回 `{"schemaVersion":1,"platform":"mt6991","policies":[...]}`，规则附加更新时间。客户端移除更新时间后严格验证，展示并确认后仅合并安装版本匹配的条目。后台不根据占用自动生成限制，用户或管理员决定核心分配。

## 部署与权限

`cloud/wrangler.jsonc` 配置账号及 D1。数据库使用 `cloud/schema.sql`，管理员密钥用 Wrangler secret put 设置为 `ADMIN_TOKEN`，部署 `cloud/worker.js`。本机密钥在 `.secrets/cloud-admin.txt`，不放入源码包。

本次完成数据库创建及 Worker 部署，用户要求不测试，没有上传样例统计或进行正式接口验证。参考：[D1 入门](https://developers.cloudflare.com/d1/get-started/)、[Workers 限速](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)。

## 0.6.0 客户端确认流程

入口收进设置。客户端下载规则后预览，点击试用只合并本机安装版本匹配的规则，并启动 20 秒独立回滚守护；确认后才保留本地规则。未启用接管时仅保存草稿。上传字段和服务端协议保持原样，AI 分析 ZIP 是本机导出格式，不上传到汇集服务。
