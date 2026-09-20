# 开发与验证状态

更新日期：2026-09-20。步骤 1～10 的主要代码路径已经写入，步骤 11～12 的自动化配置和内测材料已经建立；M0、M1、M2 均未通过所需的 CI、真机、正式角色、GPU 和用户验收。

| 步骤 | 已写入工程 | 尚未完成的验收 |
| --- | --- | --- |
| 1 | Android/Compose 工程、Gradle Wrapper、固定版本、CI 与 APK artifact | CI 未在远程运行；未取得两台基线手机 |
| 2 | CameraX 前后镜头、生命周期、RGBA 分析、背压、坐标映射与测试 | 无 SDK Platform 36，未编译；无真机 10 分钟记录 |
| 3 | MediaPipe 模型校验、最多 3 人、姿态及掩码可用性标记 | 未提取逐人掩码像素；未测多人、延迟、内存和温升 |
| 4 | 同帧契约、矢量噜噜、动作映射与快照合成 | 正式 GLB、SceneView/Filament 骨骼、真人覆盖和导出一致性未验收 |
| 5 | Track 状态机、点击命中、逐人绑定、回放测试和 ADR | 未做 ByteTrack 回放对照及真机交叉验证 |
| 6 | A/B 占位外观、身体/四肢映射、限幅与 EMA | 正式 A/B 资产和动作录像缺失 |
| 7 | 同帧冻结、原图/即时图预览、重拍、MediaStore 保存、离线反馈 | 使用分析帧分辨率；编辑掩码暂为人物框扩边；未真机检查方向、镜像、相册与 200 次成功率 |
| 8 | Diffusers inpainting 适配入口、掩码回贴、固定评测协议 | 无授权评测图片、正式模型、GPU、ControlNet/IP-Adapter 对照及成本/时延数据 |
| 9 | FastAPI、任务状态、会话隔离、幂等、Huey/SQLite、取消、过期和假生成器 | 未安装后端依赖执行完整 pytest/API/Worker 重启测试；生产反向代理和 GPU 超时仍需部署环境 |
| 10 | 上传说明、OkHttp、Room、WorkManager 唯一任务、查询/下载/取消、结果校验和 7 天清理 | 未在 Android 进程死亡、断网和真实服务上端到端验证 |
| 11 | 分析节流、CPU 失败回退、Android/后端 CI 与报告 artifact | 性能、连续运行、多机型、发热、内存和真实服务可达性均未测 |
| 12 | 20～50 人内测说明、反馈模板和数据处理草案 | 尚未分发或邀请，未产生用户数据和下一版结论 |

本机已完成：Python 语法编译、SQLite 幂等/访问隔离冒烟测试、Pillow 掩码外像素保护冒烟测试、XML/YAML 解析、Shell 语法、模型和 Wrapper JAR 哈希、`git diff --check`。本机没有 FastAPI、Huey、pytest、SDK Platform 36 或 Gradle 分发缓存，遵循项目规则未安装或升级环境，因此 Android lint/JVM test/APK 和完整后端测试留给 GitHub Actions。

发布判断仍以证据为准：构建通过只证明能构建；正式 A/B GLB、逐人像素掩码、真人覆盖、真机性能、真实高清质量和人工内测是当前关键阻塞项。
