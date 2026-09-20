# 客户端与高清服务架构

## 数据流

```text
CameraX Preview ───────────────────────────────────────────→ 屏幕底图
       │
       └→ RGBA ImageAnalysis → MediaPipe Pose Landmarker
                                  │
                                  └→ PersonObservation
                                            │
                                            ├→ PersonTracker → Track ID / 角色绑定
                                            └→ PoseMapper → CharacterPose
                                                                  │
                                                                  └→ CharacterOverlay
                                                                         │
                                                               快门冻结同一分析帧
                                                                         ↓
                                                       CaptureStore → 预览 / MediaStore
                                                                         │
                                                 用户确认高清上传后 → WorkManager
                                                                         ↓
                                                FastAPI → Huey → Generator → 掩码回贴
```

相机只由 `CameraSession` 持有。分析使用 `STRATEGY_KEEP_ONLY_LATEST`，每个 `ImageProxy` 在转换结束后关闭。MediaPipe 输入已经按显示方向旋转，前置输入在推理前镜像，因此 UI 层不重复镜像识别结果。

## 模块职责

| 包 | 职责 |
| --- | --- |
| `camera` | CameraX 绑定、帧方向和预览坐标映射 |
| `vision` | MediaPipe 初始化、推理和结果归一化 |
| `tracking` | 最多 3 人的跨帧匹配、Track 生命周期和角色选择 |
| `character` | 人体到噜噜动作参数映射、限幅与 EMA 平滑 |
| `ui` | 权限、相机页、点选、选择器和当前矢量原型渲染 |
| `generation` | Room 任务、OkHttp API、WorkManager 恢复、结果校验与本地清理 |

`CaptureStore` 在应用私有目录保存同帧原图、即时合成图、编辑掩码和姿态/角色元数据。普通拍照不联网；相册保存只写用户当前选择的版本。当前编辑掩码由人物框扩边生成，真正的逐人分割像素尚未接入，因此不能把后端链路存在等同于高清质量达标。

高清服务以匿名安装会话令牌隔离任务，以会话和幂等键唯一约束避免重复创建。API 不在请求线程推理，Huey 单 Worker 从 SQLite 队列执行生成适配器。无 GPU 的 CI 使用确定性假适配器验证状态机和编辑区外像素保持；Diffusers 适配器必须在正式模型与 GPU 环境另行验收。

## 当前渲染边界

`CharacterOverlay` 是用于贯通交互与动作映射的矢量原型。它能区分 A/B、跟随锚点和尺度，并响应身体及四肢角度，但不能代替 PLAN 中要求的绑定 GLB、逐人掩码合成和真人无残留覆盖。

SceneView 依赖已经固定，但在正式 A/B GLB 到位前不编造模型和骨骼。后续 3D 层消费同一个 `CharacterPose`，无需修改相机、追踪和交互层。

## 线程与回退

- CameraX Analyzer 与 MediaPipe CPU 实例运行在单线程执行器中；正常约 15 FPS 送入分析，最近推理超过 90 ms 时降至约 8 FPS。
- 识别回调只输出不可变观测；`PersonTracker` 的更新、点击和角色选择使用同步保护。
- Track 丢失时立即停止点击命中；短暂丢失保留角色选择，超过 6 帧删除。
- 姿态缺少可靠肩/髋时不渲染角色；半身缺少脚踝时按躯干长度估算尺度。
- 当前 GPU Delegate 未启用。真机建立 CPU 基线后再验证 GPU，初始化失败不得让相机崩溃。
