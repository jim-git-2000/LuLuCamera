# 第三方依赖记录

记录日期：2026-09-20。实际升级依赖时同步更新本文件，模型与角色资产逐项记录来源和许可。

| 组件 | 固定版本 / 来源 | 用途 | 许可与处理 |
| --- | --- | --- | --- |
| Android Gradle Plugin | `9.4.0` | Android 构建 | Android SDK 条款；CI 使用 |
| Gradle Wrapper | `9.6.0` | 可复现构建 | Apache-2.0；Wrapper JAR SHA-256 为 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` |
| Kotlin / Compose Compiler | `2.4.10` | 客户端语言与 Compose 编译 | Apache-2.0 |
| KSP | `2.3.10` | Room 源码生成 | Apache-2.0；配合 AGP 9 内置 Kotlin，不使用不兼容的 `kapt` 插件 |
| Compose BOM | `2025.08.00` | UI 依赖版本对齐 | Apache-2.0 |
| AndroidX Core / Core KTX | `1.18.0` | Android 平台兼容 API | Apache-2.0；官方 AAR 元数据要求 `minCompileSdk=36`，替代要求 API 37 的 1.19.0 |
| CameraX | `1.6.2` | 预览、分析、镜头生命周期 | Apache-2.0 |
| MediaPipe Tasks Vision | `1.0.0` | 人体关键点和分割掩码 | Apache-2.0；适配思路来源于官方 Pose Landmarker Android 示例 |
| Pose Landmarker Lite | [官方 float16 v1](https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task) | 最多 3 人的端侧姿态识别 | 构建时下载到 `android/app/src/main/assets/models/`；SHA-256 为 `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`；发布前再次核对模型卡和分发条款 |
| SceneView | `4.37.0` | 后续正式 GLB 渲染候选 | Apache-2.0；仅在版本目录保留候选，暂不加入 app 依赖图；正式角色资产缺失，尚未完成渲染接入验收 |
| Room | `2.8.5` | 客户端生成任务与文件引用持久化 | Apache-2.0；数据库不存照片内容 |
| WorkManager | `2.11.2` | 可靠上传、下载、重试和本地清理 | Apache-2.0；不作为秒级定时器 |
| OkHttp | `5.3.0` | HTTPS 上传、查询、下载和取消 | Apache-2.0 |
| JUnit | `4.13.2` | JVM 单元测试 | EPL-1.0 |
| FastAPI | `0.141.1` | 高清服务 HTTP API | MIT |
| Huey | `3.4.0` | SQLite 持久任务队列 | MIT；单 Worker 候选配置 |
| Pillow | `11.3.0` | 服务端图像读取与掩码保护合成 | HPND |
| Diffusers | `0.35.1`（可选依赖） | 正式 inpainting 生成适配器 | Apache-2.0；代码依赖许可不替代模型权重许可审查 |

当前没有复制 ByteTrack、KalidoKit 或其他仓库源码。人物关联和动作映射为项目内的轻量实现，后续若迁移上游代码，必须记录文件、commit、修改范围及 NOTICE。

后端其余固定依赖见 `backend/pyproject.toml`。正式噜噜 A/B 模型、生成模型和参考图尚未提供；当前矢量占位角色完全由项目代码绘制，不包含第三方图像或模型资产。声明的 GPU 可选依赖尚未在本机安装或运行。

首次 CI 修正：SDK 仓库未找到 `platforms;android-37`，客户端与 CI 统一使用 API 36。Compose BOM 回到 2025.08.00；Google Maven 的 `lifecycle-runtime-compose-android:2.12.0` 返回 404，Lifecycle 改为 2.9.4。未使用的 SceneView 暂不加入依赖图，避免通过候选渲染库引入更高 Compose 要求。完整依赖解析和 AAR SDK 要求仍由 CI 验证。
