# 运行模型

构建前由 `scripts/download-models.sh` 下载官方 MediaPipe Pose Landmarker Lite 模型到本目录。模型文件不重复提交到 Git；CI 会在构建前准备。

应用只从 APK 资产读取模型，不在用户首次打开相机时联网下载。模型来源和制品版本记录在 `docs/third-party.md`。
