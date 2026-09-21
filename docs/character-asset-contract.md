# 噜噜角色资产契约

用户已确认本轮采用 PNG 图片版，直接遵循 [PNG 素材入口](../assets/characters/README.md)。实时 PNG 整体跟随与拍后生成可独立交付。以下骨架契约保留给后续 3D 版，不要求现在提供 GLB。

后续 3D 版的正式噜噜 A/B 使用相同骨架、静止姿态、坐标轴与单位。运行文件放在 `android/app/src/main/assets/models/characters/`，美术源文件放在 `assets/characters/` 并记录授权信息。

## 文件

```text
lulu_a.glb
lulu_b.glb
characters.json
```

每个 GLB 只包含运行时需要的网格、材质、蒙皮和骨架。纹理使用移动端适合的尺寸与压缩格式；单个角色的三角形数、纹理内存和文件体积在真机性能测试后固化。

## 必需骨骼

| 语义 | 节点名 |
| --- | --- |
| 根节点 | `Root` |
| 骨盆 | `Pelvis` |
| 躯干 | `Spine`、`Chest` |
| 头部 | `Neck`、`Head` |
| 左/右前肢 | `LeftFrontLeg`、`RightFrontLeg` |
| 左/右后肢 | `LeftHindLeg`、`RightHindLeg` |

约定右手坐标系，$+Y$ 向上，角色正面朝 $+Z$，单位为米。根节点位于双后脚落地点中间，静止姿态的局部旋转为身份四元数。A/B 的节点层级、骨骼名称和绑定姿态必须一致。

## 导出验收

- GLB 可被 SceneView/Filament 加载，不含缺失纹理或外部绝对路径。
- 每个必需节点唯一存在，蒙皮权重归一化，无未绑定顶点。
- 在相同 `CharacterPose` 输入下，A/B 的锚点、朝向和动作一致。
- 角色轮廓需要覆盖正常站立、挥手和走动的人体范围；不能仅靠无限放大掩盖残留。

## 无素材阶段

目前可以不提供参考图和正式外观。Android 的 `CharacterCatalog` 固定 A/B ID、显示名和占位配色，`referenceAsset`、`glbAsset` 保持 `null`；PNG 有效时自动加载；缺失时预览与保存读取相同占位配色。当前没有 GLB 加载器，不能通过只填路径就宣称完成 3D 接入。

服务端 `backend/lulucamera_backend/characters.json` 的专属提示词、`referenceImage`、`glb` 均留空。空提示词自动使用通用水豚描述；现在可以直接提供规定名称的 PNG，由默认 IP-Adapter 路径按需加载；`LULU_CHARACTER_CATALOG` 用于高级提示词/路径覆盖。正式参考图与用户拍照时的即时合成图是不同输入，后者不用于建立 A/B 外观。
