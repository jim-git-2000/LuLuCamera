# LuLuCamera 高清服务

服务将 API、持久任务和生成器分开。默认 `fake` 生成器用于 CI 和客户端联调，只修改编辑掩码内像素；它不代表高清模型效果。真实 GPU 环境通过 `LULU_GENERATOR=diffusers` 启用 Diffusers inpainting，模型 ID 和访问令牌只放运行环境。

```bash
cd backend
python -m venv .venv
. .venv/bin/activate
pip install -e '.[dev]'
uvicorn lulucamera_backend.api:app --host 127.0.0.1 --port 8000
huey_consumer lulucamera_backend.tasks.huey -w 1
```

客户端为每次安装生成至少 32 字符的随机 `X-Session-Token`，它是读取和取消任务的能力凭证。创建请求还需稳定幂等键。服务限制上传大小、每分钟请求数和活跃任务数；生产环境应在反向代理继续配置 TLS、总连接限制和来源限制。

运行数据位于 `LULU_STORAGE_DIR`，默认 24 小时过期。定期执行 `python -m lulucamera_backend.cleanup` 删除过期文件。Worker 启动前应先启动 API 一次或执行恢复入口，将中断时处于 `running` 的任务标为可重试失败；当前实现不会假装能强制终止已经进入 GPU 内核的调用，取消后的输出会被丢弃。

接口定义见自动生成的 `/docs` 和 `docs/generation-api.md`。正式模型兼容、显存、时延、成本与质量仍需在获授权的 GPU 和固定评测集上验收。
