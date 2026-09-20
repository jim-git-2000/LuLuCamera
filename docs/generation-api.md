# 高清生成 API 契约

版本：`1.0.0`。所有任务请求必须带本次安装生成的 `X-Session-Token`，长度为 32～256 字符；服务只存它的 SHA-256 摘要。该凭证用于隔离匿名会话，不承担用户账号体系。

## 创建任务

`POST /generations` 使用 `multipart/form-data`：

| 字段 | 必填 | 内容 |
| --- | --- | --- |
| `original` | 是 | 已按显示方向冻结的原图文件 |
| `reference` | 否 | 同帧即时噜噜合成参考 |
| `mask` | 是 | 与原图同尺寸的编辑区，白色可编辑、黑色保护 |
| `metadata` | 是 | `schemaVersion=1` 的姿态、角色绑定、方向和资源版本 JSON |
| `idempotency_key` | 是 | 同一次拍照重试时保持不变 |

同一会话和幂等键只对应一个任务。失败且标记为可重试的任务再次创建时重新入队，不建立第二个计费任务。成功响应为 `202`，返回任务对象。

## 查询、结果与取消

- `GET /generations/{jobId}` 返回状态。
- `GET /generations/{jobId}/result` 只在完成后返回 PNG。
- `DELETE /generations/{jobId}` 取消排队或运行任务；运行中的 GPU 调用可能无法立即中断，但迟到输出不会成为结果。已完成任务调用该接口会删除服务端结果。

状态固定为 `queued`、`running`、`completed`、`failed`、`cancelled`、`expired`。任务对象包含 `error_code`、`retryable`、`expires_at` 和完成时的 `result_url`，不返回服务端文件路径。

稳定错误码包括 `INVALID_SESSION_TOKEN`、`RATE_LIMITED`、`QUEUE_FULL`、`UPLOAD_TOO_LARGE`、`EMPTY_UPLOAD`、`INVALID_METADATA`、`JOB_NOT_FOUND`、`RESULT_NOT_READY`、`WORKER_INTERRUPTED` 和 `GENERATION_FAILED`。HTTP 状态和 `retryable` 共同决定客户端是否交给 WorkManager 重试。

## 数据保留和部署边界

默认单文件上限 15 MiB、单会话每分钟 60 次请求、全局最多 20 个活跃任务、结果 24 小时过期。客户端本地捕获文件默认保留 7 天并由 WorkManager 清理。生产环境还必须配置 HTTPS 反向代理、请求体总上限、单 Worker、GPU 超时监控和备份排除；供应商令牌只进入服务端环境。
