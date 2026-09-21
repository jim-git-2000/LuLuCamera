# 客户端与高清服务架构

## 图片版数据流

```text
CameraX → RGBA 分析（KEEP_ONLY_LATEST + ViewPort）
                 ↓
      VIDEO 模式 Pose Landmarker（分析线程）
                 ↓
      同帧 Bitmap + 逐人置信度掩码 + 姿态
                 ↓
       Tracker / 像素点选 / A-B 绑定
                 ↓
          PNG 或占位角色共用 Canvas 绘制
                 ↓
       同帧预览 ── 快门冻结 ── CaptureStore / MediaStore
                                    ↓ 用户同意
                         Room / WorkManager / OkHttp
                                    ↓
                    FastAPI / Huey / SQLite 任务状态
                                    ↓
                 可终止的常驻推理子进程 / Diffusers
                                    ↓
                 逐人 Inpainting / 实例像素保护 / 回贴
                                    ↓
                   PNG / SHA-256 回执 / 预览和保存
```

`CameraSession` 只解绑自身 UseCase。初始化、同步推理和关闭位于同一分析线程；CameraX 负责背压，没有自行维护的异步 Bitmap 回收队列。相机会话版本检查丢弃切换镜头或退出后的旧回调。`ImageProxy.toBitmap()` 处理像素步幅，输入经过 ViewPort 裁剪、旋转与单次前置镜像。

MediaPipe 包装图关闭会回收其 Bitmap，因此输出在包装图关闭前单独复制；native mask 在释放前复制为最大 256 × 256 的 JVM 字节置信图。UI 正在绘制的 Bitmap 由 GC 回收，不主动 recycle。屏幕底图采用对应分析帧以确保角色与快照一致，帧率受推理耗时限制，性能仍需真机验收。

`CharacterCatalog` 一次加载 `assets/characters` 中的 PNG 并计算版本摘要。实时预览和导出都调用 `SnapshotCompositor.drawCharacter`，只在 UI 另画选中框。PNG 使用整体平移、缩放、倾斜；没有独立四肢/3D 动作。资源缺失时继续使用矢量占位。

Tracker 点击优先使用该人物掩码；没有掩码才回退到框。匹配分数接近时暂停替换，短暂保留 A/B 选择；失跟过期移除。姿态映射清理过期轨迹缓存，肩线角度不受左右顺序反转影响。

## 拍照和任务

CaptureStore 保存同帧原图、即时合成图、扩边编辑掩码、逐人实例标签图和 JSON。实例标签 1～3 关联 metadata.maskLabel，0 表示背景，255 表示无法确定归属的重叠像素。后端保护其他人物及 255 区域；粗编辑区相交仍保守拒绝，不宣称复杂遮挡已解决。

Room 持久化照片引用和幂等键；WorkManager 完成上传、轮询、重试、下载、取消。结果校验 SHA-256、大小和尺寸。最近预览和生成记录支持重新进入；重拍不会遗失后台任务入口。无网时原图和即时图仍可保存。已取消请求由服务端幂等取消标记阻止迟到创建。

## 服务运行

API 不加载 GPU 模型。单 Huey Worker 将真实推理交给独立常驻子进程，成功后复用已加载模型；默认 300 秒超时或取消时终止该进程，下个任务重新建立，防止卡死调用长期堵塞队列。Worker 自身重启后把原 running 任务标记为可重试失败，API 重启不干扰推理。

Diffusers 使用 SDXL Inpainting；补齐 PNG 后自动作为参考图并按需加载默认 SDXL IP-Adapter。逐人裁剪保持比例，结果回贴到最多 2048 像素的原图缩放底图。只依赖原图上下文与提示词约束动作，未接 ControlNet；背景是插值放大，非超分模型。

`compose.yaml` 提供演示服务，`compose.gpu.yaml` 提供真实 GPU 配置、共享存储和模型缓存。`doctor` 检查服务主机，`--warmup` 显式预加载模型。不自动部署，域名、HTTPS 和 GPU 主机由运行环境提供。
