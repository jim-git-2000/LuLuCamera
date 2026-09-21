# 高清生成 API 契约

版本：`1.0.0`。所有任务请求必须带本次安装生成的 `X-Session-Token`，长度为 32～256 字符；服务只存其 SHA-256 摘要。该凭证隔离匿名会话，不承担用户账号体系。

## 创建与能力查询

`GET /capabilities` 无需会话，返回 `mode`（`disabled`、`fake` 或 `diffusers`）、`available`、`reference_required=false`、`output_scale`、`max_output_edge=2048`、`retention_hours`。`available` 表示配置满足入口条件，不代表模型已经加载或 GPU 可用。

`POST /generations` 使用 `multipart/form-data`：

| 字段 | 必填 | 内容 |
| --- | --- | --- |
| `original` | 是 | 已按显示方向冻结的原图文件 |
| `reference` | 否 | 同帧即时合成图，仅假生成器回贴使用；不是正式角色参考图 |
| `mask` | 是 | 与原图同尺寸的编辑区，白色可编辑、黑色保护 |
| `instances` | 否 | 与原图同尺寸的逐人标签 PNG；0 背景、1～3 人物、255 不确定归属 |
| `metadata` | 是 | `schemaVersion=1`、width/height、people、方向和资源版本 JSON |
| `idempotency_key` | 是 | 同一次拍照保持不变，8～128 字符 |

`people` 为 1～3 人，`trackId` 唯一，`character` 为 `HUMAN/LULU_A/LULU_B`，`state=TRACKED` 的非 HUMAN 人物参与生成；`bounds=[left,top,right,bottom]` 使用归一化坐标。上传 `instances` 时，每人需提供唯一的 `maskLabel=1..3`，其他人物及标签 255 的像素强制保护。编辑掩码不能溢出选中人物扩边区域；选中区域与其他人物交叠会拒绝生成。上传图片最少 8 像素边长、最多 16777216 像素，metadata 最大 64 KiB。

同一会话和幂等键只对应一个任务。重复创建返回原状态，**不会自动重跑失败模型**。成功响应为 `202`，返回任务对象。已提前取消的幂等键返回 `REQUEST_CANCELLED`，并删除本次临时上传。

## 查询、下载、重试与取消

- `GET /generations/{jobId}`：返回状态。
- `GET /generations/{jobId}/result`：完成后返回 PNG；过期返回 410。
- `POST /generations/{jobId}/retry`：仅将 `failed && retryable` 任务重新入队，保留同一个 ID/幂等键；其他状态原样返回。用户显式操作才调用。
- `DELETE /generations/{jobId}`：取消任务；运行中的推理子进程会被取消，迟到输出不会成为结果。已完成任务调用后删除服务端文件。
- `DELETE /generation-requests/{idempotencyKey}`：取消已知或尚未取得 Job ID 的请求，返回 `{"status":"cancelled"}`。持久取消标记可阻止迟到创建；无需再次上传照片。

状态为 `queued/running/completed/failed/cancelled/expired`。任务对象包含 `error_code`、`retryable`、`expires_at` 和完成时的 `result_url`，不返回服务端路径。完成后附带 `mode`、`result_width`、`result_height`、`result_bytes`、`result_sha256`。客户端校验大小、SHA-256 和可解码尺寸后展示；`fake` 明确标为演示。

错误响应格式：`{"detail":{"code":"稳定错误码","retryable":false}}`。常见码包括 `SERVICE_NOT_CONFIGURED`、`INVALID_METADATA`、`INVALID_IMAGE_SIZE`、`MASK_SIZE_MISMATCH`、`MASK_OUTSIDE_EDIT_REGION`、`PEOPLE_OVERLAP`、`EMPTY_EDIT_MASK`、`NO_CHARACTER_SELECTED`、`QUEUE_FULL`、`RATE_LIMITED`、`JOB_NOT_FOUND`、`RESULT_EXPIRED`、`WORKER_INTERRUPTED`、`GENERATION_FAILED`、`GENERATION_TIMEOUT`、`INFERENCE_PROCESS_EXITED`、`EMPTY_INSTANCE_MASK`。

## 输出与保留

默认 2 倍输出、最长边 2048，掩码外与同尺寸缩放原图一致。该路径生成选中人物的细节；背景插值放大，不承诺全图超分辨率或严格动作保持。角色专属参考图缺省时自动使用所提供的 PNG；所有素材为空时使用通用水豚提示词，无正式 A/B 身份保证。

默认单文件 15 MiB、每会话每分钟 60 次请求、最多 20 个活跃任务、服务器图片 24 小时过期。Android 本地照片默认 7 天，活动任务引用目录延后清理。持久幂等/取消记录不随照片删除。部署需 HTTPS、请求体总限制、单 Consumer/单 Worker、外部 GPU 超时监控及备份排除。供应商令牌仅在服务端。
