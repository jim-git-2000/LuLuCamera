# ADR 001：M0 使用姿态辅助轻量关联

状态：已采纳用于 M0，待真机回放复核。日期：2026-09-20。

## 决策

M0 使用项目内的薄关联层管理最多 3 个 MediaPipe Pose 观测，不在当前阶段引入 ByteTrack-cpp。匹配分数由人物框 IoU、中心运动距离和肩髋关键点距离组成；Track 只负责确认、短暂丢失、删除和角色绑定。

## 依据

- ByteTrack 官方仓库主要是 Python/YOLOX 训练和推理路线，不能作为 Android Kotlin 制品直接接入。
- 已调研的 ByteTrack-cpp 是 MIT 的 C++17 实现，但需要 Eigen、CMake、JNI/NDK 打包和 ABI 验证；仓库没有 Android Maven 制品。
- 本项目已有 MediaPipe 姿态结果，M0 上限为 3 人，且需要姿态相似度和逐人角色绑定。引入第二套检测模型会增加延迟，完整 C++ 栈也无法消除这些业务适配。
- Maven Central 上名称相近的 `bytetrack-sdk-android` 是消息/通知 SDK，不是多目标视觉追踪算法，不能误用。

当前实现不是通用 ReID 或完整 ByteTrack。它只覆盖 PLAN 支持场景，代码保持独立、可替换，并用固定观测回放测试 Track ID、选择保留和丢失过期。

## 复核条件

收集至少 20 段真机录像。若简单双人交叉发生 ID 交换，或遮挡后的恢复率达不到验收门槛，再用同一观测序列运行 ByteTrack-cpp 基线。只有基线明显改善且性能预算允许时，才加入 NDK/Eigen；否则改进当前姿态关联门限或安全回退。
