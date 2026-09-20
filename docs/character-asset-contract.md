# 噜噜角色资产契约

正式噜噜 A/B 使用相同骨架、静止姿态、坐标轴与单位。运行文件放在 `app/src/main/assets/models/characters/`，美术源文件放在 `assets/characters/` 并记录授权信息。

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
