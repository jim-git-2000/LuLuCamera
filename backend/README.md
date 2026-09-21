# LuLuCamera 高清服务

参考图和正式 A/B 外观可以留空。`characters.json` 已保留两个角色；空提示词使用通用水豚描述，未配置专属参考图时，自动查找 `assets/characters/lulu_a_reference.png` / `lulu_b_reference.png`，再回退到同名角色 PNG；这些文件都缺失时不启用图像条件。无素材阶段验证上传、逐人编辑、后台任务、下载和保存；不验收 A/B 正式身份一致性。

默认 `LULU_GENERATOR=disabled`，避免未配置模型时冒充真实服务。`fake` 仅回贴即时合成图并缩放，Android 会标注“流程演示完成（非 AI 高清生成）”；`diffusers` 才调用真实模型。

## 流程联调

以下由具备 Python 环境的服务主机执行，不由 Android 手机执行。`.env.example` 是配置说明，进程不会自动读取 `.env`；两个进程必须使用相同配置和存储目录。

```bash
cd backend
python -m venv .venv
. .venv/bin/activate
pip install -e '.[dev]'
export LULU_STORAGE_DIR="$PWD/storage"
export LULU_GENERATOR=fake
uvicorn lulucamera_backend.api:app --host 127.0.0.1 --port 8000
```

在同样配置的第二个终端运行 **一个 Consumer、一个线程 Worker**：

```bash
huey_consumer lulucamera_backend.tasks.huey -k thread -w 1
```

需要给手机访问时，由服务主机配置 HTTPS 反向代理。API 和 Worker 必须共享存储；不要启动第二个 Consumer。`GET /health` 检查进程，`GET /capabilities` 查看模式和配置是否具备，不代表 GPU 已通过实测。

## 真实高清生成

推荐先按 [PNG 使用准备](../docs/ready-to-use.md) 使用已有 Docker/GPU 环境启动，无需逐项配置默认模型。也可以在已有 GPU 环境安装项目可选依赖 `pip install -e '.[gpu]'`，将 API 和 Worker 的配置设为：

```bash
export LULU_GENERATOR=diffusers
export LULU_MODEL_ID=diffusers/stable-diffusion-xl-1.0-inpainting-0.1
export LULU_DEVICE=cuda
export LULU_OUTPUT_SCALE=2
export LULU_INFERENCE_SIZE=1024
export LULU_INFERENCE_STEPS=30
```

模型按 [Diffusers 官方 Inpainting 接口](https://huggingface.co/docs/diffusers/v0.35.1/en/using-diffusers/inpaint) 加载，首次任务需要模型下载和显存。`LULU_MODEL_REVISION` 默认 `main`，验收后应固定模型仓库提交号。CPU 可设置 `LULU_DEVICE=cpu`，不承诺交互时延。供应商令牌仅放服务端运行环境。

处理顺序：校验同帧尺寸和人物绑定 → 拆出选中人物编辑区 → 保持比例裁剪上下文并补边 → 按各自角色描述逐人 Inpainting → 按掩码回贴 → PNG 与尺寸/字节数/SHA-256 回执。输出默认放大 2 倍，最长边上限 2048；推理尺寸可选 512/768/1024，步数 1～60。掩码外像素保持为**同尺寸缩放原图**，不是声称放大后仍与输入像素逐点相等；背景放大采用插值，不是超分辨率模型。姿态目前依赖原图上下文和提示词，未接 ControlNet，不能承诺严格保持动作。

当前掩码仍是扩边人物框。选中编辑区与任何其他人物相交时返回 `PEOPLE_OVERLAP`，提示分开站位重拍；客户端现已额外上传逐人实例像素，服务保护旁人伸出框外的肢体与不确定归属像素；复杂遮挡仍不在本轮范围。

## 以后填写角色素材

通常只需按命名放入 PNG，默认 SDXL IP-Adapter 会在存在参考图时自动加载，不必修改 JSON。若需专属提示词或覆盖默认路径，再复制 `lulucamera_backend/characters.json` 到受控配置目录，设置 `LULU_CHARACTER_CATALOG` 为其路径。保持 `LULU_A`、`LULU_B` ID 不变，分别填写 `prompt` 和 `referenceImage`（相对配置文件目录，或绝对文件路径）。本服务不会从客户端传入路径或 URL 加载角色资源。

默认配置已包含兼容 SDXL 的 `h94/IP-Adapter` / `sdxl_models` / `ip-adapter_sdxl.bin`。更换基础模型时，须相应配置兼容的 `LULU_IP_ADAPTER_ID`、`LULU_IP_ADAPTER_SUBFOLDER` 和 `LULU_IP_ADAPTER_WEIGHT`；使用 Diffusers 已有 IP-Adapter 加载接口，每人按配置选图，未提供该人物参考图时权重为零。只填参考图未配置适配器会明确失败，不会默默忽略。参考图适配尚未 GPU 验收。`glb` 仅预留给后续实时 3D 渲染，不参与服务端高清生成。

## 恢复、取消和数据保留

会话凭证隔离访问；幂等键避免重复创建。模型失败只由用户显式重试，重复上传不会自动重新推理。数据库以事务限制活跃任务上限。Worker 启动将原先 `running` 标记为可重试失败；API 重启不改变运行状态。周期任务补投已入库但尚未投递的任务，CAS 阻止重复消息重复执行。

取消用幂等键写入持久取消标记，因此响应丢失时无需重新上传照片，也会阻止迟到的创建请求。真实推理位于独立子进程，正常任务复用模型；父 Worker 检查取消和默认 300 秒截止时间，超时/取消时终止子进程，下一任务重建。子进程也监测父进程退出，避免 Worker 强制退出后留下孤立推理。迟到输出不发布。进程控制已做无 GPU 冒烟，真实 GPU 驱动下的显存回收仍需验收；生产主机仍应监控容器与驱动健康。

文件默认 24 小时过期，Worker 周期任务及 API 请求时清理；也可通过 `python -m lulucamera_backend.cleanup` 独立定期清理。Worker 进程或容器完全停止时，可由外部调度执行清理。数据库保留不含照片的任务记录及取消标记，维持幂等语义。客户端默认保留照片 7 天，尚有活动任务的目录暂缓清理。

生产环境应配置 TLS、请求体总上限、总连接限制和备份排除。本项目不自动部署。接口见 [API 契约](../docs/generation-api.md)；CI 验证纯逻辑和假服务，真实生成质量、模型兼容、显存、时延与成本须另行 GPU 验收。
