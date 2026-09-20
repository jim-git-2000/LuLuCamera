# LuLuCamera

面向 Android 的噜噜相机：点击画面中的人物，将其实时替换为噜噜水豚角色，拍照保存，并可在拍照后生成高清版本。

## 当前状态

已写入 Android 与高清服务的完整 MVP 代码骨架：相机、人体姿态、基础追踪、点选、矢量噜噜、同帧拍照、对比与相册保存，以及经用户确认后才启动的持久高清任务。后端包含幂等 API、Huey/SQLite 队列、假生成器和 Diffusers 适配入口。

当前仍不是可宣称完成的 M2：正式 GLB、逐人像素掩码、真机性能和覆盖效果、真实 GPU 生成质量、CI 构建及用户内测均未验收。详细边界见 [验证状态](docs/verification-status.md)。

- [开发计划](PLAN.md)：产品范围、技术候选、12 个实施步骤与验收条件。
- [项目规范](AGENTS.md)：工作方式、开发环境约束与 Git 规则。

## 目录约定

按开发计划逐步创建目录，不建立没有实际内容的空目录。

| 路径 | 用途 |
| --- | --- |
| `android/` | Android 客户端与 Gradle 配置 |
| `backend/` | FastAPI、Huey/SQLite 与生成适配器 |
| `assets/characters/` | 角色源文件、参考图与授权记录 |
| `docs/` | 架构、接口与验收记录 |
| `tests/fixtures/` | 后续放获授权的固定测试素材，不提交用户照片 |
| `experiments/` | 后续 GPU 技术验证，结论归档后清理中间产物 |
| `.github/workflows/` | 后续构建、检查和 APK artifact 工作流 |

未出现的目录在取得真实素材或开始对应实验时再创建。细分目录与命名规则见开发计划第 4 节。

## 开发与验证

当前依赖版本和第三方来源见 [依赖记录](docs/third-party.md)，所有步骤的真实完成度见 [验证状态](docs/verification-status.md)。

构建前准备模型，然后执行检查和 APK 构建：

```bash
./scripts/download-models.sh
cd android
./gradlew lintDebug testDebugUnitTest assembleDebug
```

本地使用已有工具编辑和轻量检查，不自行安装或升级开发环境。正式依赖还原、构建、测试和打包由 GitHub Actions 完成，APK 由用户手动下载并在真机验收。本机缺少完整 Android SDK，因此本次不能以本地执行替代 CI。

高清服务的本地运行、配置和清理方式见 [backend/README.md](backend/README.md)，接口契约见 [docs/generation-api.md](docs/generation-api.md)。Android 默认使用不可达占位地址，因此高清按钮会明确提示服务未配置；构建时通过 Gradle 属性指定正式 HTTPS 地址：

```bash
cd android
./gradlew assembleDebug -PLULU_API_BASE_URL=https://example.com
```

Debug 构建可为模拟器联调指定 `http://10.0.2.2:8000`，Release 只接受 HTTPS。普通拍照和相册保存始终不依赖服务。

内测使用带 `.debug` 后缀的 Debug APK。正式签名只从 `LULU_RELEASE_STORE_FILE`、`LULU_RELEASE_STORE_PASSWORD`、`LULU_RELEASE_KEY_ALIAS` 和 `LULU_RELEASE_KEY_PASSWORD` 读取；变量不完整时 Release 保持未签名，密钥及密码不得写入仓库。

构建产物、缓存、本地配置、签名密钥和运行数据不入库。角色资产、测试素材及后续依赖锁文件应按计划管理，不因扩展名被一律忽略。不得提交用户照片或服务凭证。

Git 提交使用中文，不自动 push、部署或发布。
