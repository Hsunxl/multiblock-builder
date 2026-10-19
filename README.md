# Multiblock Builder / 多方块结构搭建器

Minecraft **1.21.1 / NeoForge 21.1.169+ / Java 21**。独立工具模组；客户端与服务器均需安装。

从 ExtendedAE Plus 的终极矩阵搭建器借鉴“选点、锁定预览、确认搭建”交互，独立实现可由数据包扩展的通用蓝图工具。无需 AE2、ExtendedAE、GuideME 或 KubeJS。

## 当前功能（0.1.0）

- 搜索与分页蓝图目录，90° 旋转，世界线框预览，按层查看。
- 创造模式免费搭建；经过审核的生存蓝图从玩家主背包扣取普通材料。
- 已有完全相同的方块跳过，不重复收费。任何不同的非空气方块都视为障碍，绝不主动清空地形。
- 服务端读取数据包并验证权限、边界、已加载区块、材料；客户端不提交任意方块/NBT。
- 整体检查材料后放置；触发 NeoForge 放置保护事件。提交前失败恢复目标位置快照，不扣材料。
- 空缺/非法方块 ID、重复坐标、越界、非法状态、超过 4096 个方块的蓝图拒绝加载并记录原因。
- 模板无实体、无方块实体 NBT，因此不复制库存、能源、玩家进度或网络身份。

## 使用

合成：上中、左右各一个铁锭，中心书，下中木棍。创造物品栏“工具与实用工具”也可获得：

```mcfunction
/give @s multiblock_builder:builder
```

1. 手持工具，右键空气打开目录并选择蓝图。
2. 右键方块，将被点击面外侧一格设为蓝图最小角起点。
3. 预览锁定后再次右键方块确认。潜行右键方块重新选点；潜行右键空气取消。
4. 按住 **Ctrl** 显示逐格线框：青色待放置、绿色已匹配、红色冲突。
5. 用原模组的锤子、法杖等完成成型；本工具不绕过机器条件、不自动启动机器。

```mcfunction
/mbuilder
/mbuilder list
/mbuilder select multiblock_builder:demo_frame
/mbuilder rotate
/mbuilder layer 0
/mbuilder layer -1
/mbuilder materials
/mbuilder clear
```

层号从 0 开始，`-1` 显示全部；**分层只影响预览，确认搭建仍放置整个蓝图**。材料清单显示全图占位材料，执行时只扣缺失位置。起点必须距玩家 32 格内；目标全部在已加载区块和世界边界内。默认示例是 3×3×3 石砖框架，不是一台机器。

## 图鉴导入

不把第三方图鉴、结构模板或整合包私有内容再分发到本仓库。提供 Python 3 标准库导入器，读取用户本地已安装的 `atm-structure-library`：

```shell
python tools/import_guide.py /path/to/atm-structure-library-1.0.0.jar /path/to/new-blueprints-pack
python tools/import_guide.py /path/to/guide.jar /path/to/new-ae-pack --include 'ae_family/*'
```

将生成目录放到服务器/单人世界的 `datapacks` 后 `/reload`。每份模板记录来源路径和 SHA-256，输出已存在时拒绝覆盖。

**导入默认 `survival: false`**：图鉴预览不保证等于可成型布局，也不保证一个预览方块只消耗一个物品。审核一个模板的材料、状态和成型条件后，可单独用 `--reviewed-survival ae_family/ae_plus_ultimate` 开启它的生存模式；多格方块、含流体状态等仍会被运行时保守规则拒绝。需要原模组实际存在，否则该蓝图被跳过。

本地图鉴目前成功转换 **154 份静态 NBT**；这不是全图鉴覆盖。MI、Patchouli、其他运行时动态编译场景不由这个离线导入器导出；示意图和核心局部图也不应当作完整机器。

## 自定义数据包

路径：`data/<namespace>/multiblock_blueprints/<name>.json`，Minecraft 1.21.1 数据包格式为 48。

```json
{
  "title": "Two blocks",
  "size": [2, 1, 1],
  "survival": true,
  "blocks": [
    {"pos": [0, 0, 0], "block": "minecraft:stone"},
    {"pos": [1, 0, 0], "block": "minecraft:oak_log", "properties": {"axis": "y"}}
  ]
}
```

尺寸各轴 1..64，最多 4096 格。坐标从 0 开始。省略的位置是“不处理”，不是“必须清空”；JSON 不能含空气或 `nbt`。`survival` 缺省为 false。生存材料取对应 BlockItem，一格一个普通无额外组件的物品；双层台阶、门、床、双格植物、含水状态等拒绝推测成本。

## 构建和测试

```shell
./gradlew build
python -m unittest discover -s tools -p 'test_*.py'
./gradlew -PgameTests runGameTestServer
./gradlew clean build
```

最后一条生成不包含测试代码的发布 JAR。`src/gametest` 仅在显式 `-PgameTests` 时启用；测试使用隔离开发世界。

## 边界

- 当前没有 AE2 网络材料提取、自动锤击成型、动态指南场景导出、蓝图编辑器或撤销已提交建筑。
- 线框预览不是方块材质的半透明投影。存档中的材料和保护逻辑以服务端为准。
- 原模组特殊放置初始化、邻居回调、多方块成型和领地插件需逐模组实测。快照可恢复本工具改动的目标方块，**不能回滚其他模组在回调中修改的任意外部状态**。不宣称通用 ACID 事务。
- 生存白名单应由可信服务器/整合包作者审核；不要给未知模板开启生存材料映射。
- 本次没有覆盖 ATM10 全包机器成型或客户端 GPU 验收；详见 `VALIDATION.md`。

## English summary

Data-driven, server-authoritative multiblock construction tool for NeoForge 1.21.1. Search blueprints, lock an anchor, rotate and inspect wireframes, then confirm. Survival uses plain inventory BlockItems for explicitly reviewed blueprints; creative-only is the import default. Existing matching cells are skipped. Block-entity data and entities are never imported. Native formation tools remain necessary. Both sides need the mod.

MIT for this original implementation. See `NOTICE.md` for conceptual references and third-party asset boundaries.
