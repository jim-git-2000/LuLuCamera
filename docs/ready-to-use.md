# PNG 图片版使用准备

本轮已确认：PNG 实时角色跟随 + 拍后 AI 高清。PNG 会平移、缩放、倾斜，不会自动长出可活动的 3D 骨骼；实时贴图也不会恢复被真人挡住的背景。真实人物替换由拍后 Inpainting 完成。

## 你需要提供的内容

1. `assets/characters/lulu_a.png`、`lulu_b.png`：透明背景的完整角色，建议 512～2048 像素，少留白。可先提供一个，另一个继续占位。
2. 可选 `lulu_a_reference.png`、`lulu_b_reference.png`：高清生成参考。没有时自动使用实时 PNG，不需要你额外编辑 JSON。
3. 一台可运行高清模型的 GPU 服务主机及可供手机访问的 HTTPS 地址。服务不能在没有 GPU/模型的情况下仅靠两张图自行产生。

素材规则见 [角色图片入口](../assets/characters/README.md)。图片已打包进 APK 的版本不可热替换；补图后需要重新构建和安装 APK。服务端通过挂载目录读图，更新后重启 Worker。

## 在服务主机准备高清服务

项目不自动安装本机环境或部署。具备 Docker Compose 2.30+ 和 NVIDIA 容器运行支持的服务主机，可在仓库根目录执行：

```bash
# 先验证假服务流程（无 GPU，仅演示）
docker compose -f backend/compose.yaml up --build -d

# 切换到真实 GPU 服务，使用默认 SDXL Inpainting + 按需加载 IP-Adapter
docker compose -f backend/compose.yaml -f backend/compose.gpu.yaml up --build -d

# 检查 CUDA、角色参考和默认模型配置
docker compose -f backend/compose.yaml -f backend/compose.gpu.yaml exec worker python -m lulucamera_backend.doctor

# 首次显式预下载和检查模型加载，避免首次拍照任务等待下载超过 300 秒
# 先停止 worker 再使用独立容器预热，避免同时占用显存。
docker compose -f backend/compose.yaml -f backend/compose.gpu.yaml stop worker
docker compose -f backend/compose.yaml -f backend/compose.gpu.yaml run --rm worker python -m lulucamera_backend.doctor --warmup
docker compose -f backend/compose.yaml -f backend/compose.gpu.yaml up -d worker
```

API 默认仅监听服务主机 `127.0.0.1:8000` 的容器端口映射。将 HTTPS 反向代理指向它；手机用公网或可达局域网域名，不能填写手机自身的 localhost。参考 [后端说明](../backend/README.md) 配置证书、请求体上限和持久卷；模板不会自动建立域名、证书或云资源。不要同时启动两个 Worker。

## 构建与测试

在 GitHub Actions 的 Android CI 手动填写 `api_base_url`，或设置仓库变量 `LULU_API_BASE_URL`。不填写时保留拍照、贴图和相册功能，高清入口提示服务未配置。CI 校验素材、后端测试、Android lint/单元测试后产出 `lulucamera-debug` APK。

人工验收顺序：安装并授权相机 → 单人选择 A/B → 切换前后镜头 → 拍照比较即时图与预览 → 保存相册 → 点击高清并同意上传 → 查看下载结果 → 重拍后从“生成记录”找回任务 → 检查断网重试与取消。

本地已完成的轻量检查不能代替 CI 编译、APK 安装和真实生成验收。模型质量、角色相似度、边缘和延迟需要用最终图片验证；如果参考图不足以稳定外观，可能仍要调整提示词、适配权重或补素材，不能承诺任意图片零调参。
