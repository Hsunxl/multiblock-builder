# Multiblock Builder / 多方块结构搭建器

Minecraft **1.21.1 / NeoForge 21.1.169+ / Java 21**。独立工具模组；客户端与服务器均需安装。**硬依赖 Applied Energistics 2 19.2.17+**，同时保留不绑定网络时的主背包模式。

从 ExtendedAE Plus 的终极矩阵搭建器借鉴“选点、锁定预览、确认搭建”交互，并适配其 ME 接口取料流程；本模组是通用、数据驱动的独立实现，不复制原模组源码或材质。

## 当前功能（0.3.2）

- 搜索与分页蓝图目录：物品图标、分类/尺寸/方块数、生存/创造/自动成型标记，支持鼠标滚轮翻页。
- 悬停蓝图行时显示**透视 3D 缩略图并缓慢自转**：服务端只发送外壳体素和方块 ID 调色板，客户端用每个方块自己的纹理（模型粒子面）贴图，5,832 方块矩阵也只有几十 KB，不再弹出原始注册 ID。
- 工具提示显示已选蓝图的标题、尺寸和方块数；生存/创造提示与错误消息全部走语言文件。
- `/mbuilder select` 支持带命名空间的完整 ID（如 `guide_import:silent_charger_3`）。90° 旋转，世界线框预览，按层查看。
- 创造模式免费搭建；经过审核的生存蓝图从玩家主背包或绑定的 ME 网络扣取普通材料。
- 潜行右键 ME 接口即可绑定取料网络；未绑定时回退主背包。绑定信息保存在工具上，可跨维度使用已加载的接口。
- 先在 ME 网络中模拟全部材料，再逐项抽取；任一项不足或变化会归还已抽取材料并回滚方块，不留下半成品。
- 服务端命令 `/mbuilder import` 读取服务器 `guide_import/` 或 `mods` 中的图鉴 JAR，直接生成世界数据包并重载，不需要 Python。
- 客户端命令 `/mbuilder-capture [min]` 用 GuideME 编译图鉴里所有 `StructureViewer` 场景（含 MI、Oritech、Patchouli、Mekanism 等运行时结构），把 `>= min` 方块的场景导出到 `guide_capture` 并重载。
- 对带 `formation` 适配器的蓝图，放置和邻居更新完成后会调用原模组自己的成型 API；目前内置 **ExtendedAE Plus 终极超级装配矩阵**，导入的 `ae_family/ae_plus_ultimate` 会自动带上它。
- 已有完全相同的方块跳过，不重复收费。任何不同的非空气方块都视为障碍，绝不主动清空地形。
- 服务端读取数据包并验证权限、边界、已加载区块、材料；客户端不提交任意方块/NBT。
- 整体检查材料后放置；触发 NeoForge 放置保护事件。提交前失败恢复目标位置快照，不扣材料。
- 空缺/非法方块 ID、重复坐标、越界、非法状态、超过 8192 个方块的蓝图拒绝加载并记录原因。
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
5. 普通机器用原模组的锤子、法杖等完成成型；带 `formation` 适配器的蓝图会尝试自动成型。自动成型不等于启动机器，也不填材料/能量。

### 自动成型

“不能主动成型”原来的意思是：本工具不会猜测每个模组的装配 API，也不会自动启动机器。它本来就会一次放置全部方块，并在最后对每个位置触发邻居更新；很多在方块更新时自行检测多方块的机器已经会成型。

对明确知道 API 的结构，现在增加了数据驱动的 `formation` 字段。当前只接入：

- `extendedae_plus:ultimate_super_assembler_matrix`：在全部方块落位、邻居更新完成后调用 ExtendedAE Plus 的 `SuperAssemblerMatrixCalculator.formUltimate`。

`/mbuilder import` 会给 `ae_family/ae_plus_ultimate` 自动写入这个字段。旋转过的蓝图不会自动成型（恢复 0° 后再搭），成型失败时方块和材料保留，聊天栏会提示用原工具完成。其他机器仍需各自的控制器/工具/能量条件，不能用通用代码安全地猜测。

### ME 取料绑定

- 潜行右键一个已连接网络的 **ME 接口**：绑定为材料来源，聊天栏显示接口坐标。
- 绑定后，生存搭建只从该网络取料；接口被拆除、区块未加载或网络离线时会明确报错，不会静默改扣主背包。
- 未绑定接口时，继续使用原来的主背包材料模式。
- `/mbuilder unbind` 解除绑定。
- 只做材料抽取，不自动合成、不发起合成任务，也不从网络读取机器内容。

```mcfunction
/mbuilder
/mbuilder list
/mbuilder select multiblock_builder:demo_frame
/mbuilder rotate
/mbuilder layer 0
/mbuilder layer -1
/mbuilder materials
/mbuilder clear
/mbuilder unbind
/mbuilder import
/mbuilder import ae_family/*
```

层号从 0 开始，`-1` 显示全部；**分层只影响预览，确认搭建仍放置整个蓝图**。材料清单显示全图占位材料，执行时只扣缺失位置。起点必须距玩家 32 格内；目标全部在已加载区块和世界边界内。默认示例是 3×3×3 石砖框架，不是一台机器。

## 服务端图鉴导入

需要管理员权限（命令等级 2）。把图鉴 JAR 放在服务器 `mods`，或放在服务器根目录的 `guide_import/`（推荐给带客户端依赖的图鉴 JAR，避免服务端尝试加载它），执行：

```mcfunction
/mbuilder import
/mbuilder import ae_family/*      # 只导入匹配的模板路径
```

命令会：

1. 先扫描 `guide_import/`，再扫描 `mods` 中名称含 `structure`、`guide` 或 `atm` 的 JAR，选择包含最多 `assets/*/structures/*.nbt` 的一个；找不到时再扫描全部 JAR。
2. 从指南 Markdown 的 `<ImportStructure src="...">` 与 `title:` 读取标题。
3. 生成 `<world>/datapacks/mbuilder_import/`，包含 `pack.mcmeta`、蓝图 JSON 和 `import-report.json`。
4. 自动启用该数据包并重载；聊天栏报告成功/跳过数量。

生成目录带 `.mbuilder-import` 标记。重复执行会替换本工具生成的目录；如果同名目录不是本工具生成的，会拒绝覆盖。所有导入模板默认 `survival: false`，即**只允许创造模式搭建**，避免把预览占位误算成生存成本。

本地图鉴成功转换 **154 份静态 NBT**；这不是全图鉴覆盖。动态场景、示意图和核心局部图不应作为完整机器。无需 Python；仓库中的 `tools/import_guide.py` 仅保留给离线/自动化场景。

## 运行时图鉴捕获

静态 JAR 里只有 159 个 NBT/SNBT；图鉴另外的 194 个条目由 MI、Oritech、Patchouli、Modonomicon、NeoEco、Mekanism 等在游戏内生成。单人游戏里执行：

```mcfunction
/mbuilder-capture        # 默认排除 <10 方块
/mbuilder-capture 1      # 收录全部
```

命令会：

1. 读取 `atm_structures:library` 的 400 个页面，找到 353 个 `StructureViewer`；
2. 用 GuideME 的 `SceneTagCompiler` 编译每个场景；
3. 只写出方块数 `>= min` 的蓝图到当前世界 `mbuilder_import/data/guide_capture`；
4. 写入 `capture-report.json`，统计页面、场景、导出、小于阈值、失败和超限数量，并重载服务端。

在当前 ATM10 实测（`min=10`）：**353 个场景，286 个 >=10 已导出，66 个 1–9 方块被排除，1 个没有可显示方块**；3 个 5,832 方块的 Mekanism 矩阵通过提高上限也纳入了导出。重载后目录共 441 个可搭建蓝图（155 个原有 + 286 个捕获）。

0.3.1 起捕获模板默认 `survival:true`；本地 ATM10 已把 440 个图鉴蓝图全部设为生存并加载 441 个可搭建蓝图。仍建议审核多方块/流体/多段方块的计价。该命令只对单人/内置服务器自动写入并重载；多人服务器请把 `guide_capture` 作为单独数据包部署。

## 自定义数据包

路径：`data/<namespace>/multiblock_blueprints/<name>.json`，Minecraft 1.21.1 数据包格式为 48。

```json
{
  "title": "Two blocks",
  "size": [2, 1, 1],
  "survival": true,
  "formation": "extendedae_plus:ultimate_super_assembler_matrix",
  "blocks": [
    {"pos": [0, 0, 0], "block": "minecraft:stone"},
    {"pos": [1, 0, 0], "block": "minecraft:oak_log", "properties": {"axis": "y"}}
  ]
}
```

尺寸各轴 1..64，最多 8192 格。坐标从 0 开始。省略的位置是“不处理”，不是“必须清空”；JSON 不能含空气或 `nbt`。`survival` 缺省为 false。`formation` 可省略；只有安装对应模组并确认结构完全匹配时才应填写。生存材料取对应 BlockItem，一格一个普通无额外组件的物品；双层台阶、门、床、双格植物、含水状态等拒绝推测成本。

> **同名蓝图由后加载的数据包覆盖。** 备份数据包不要放在 `<world>/datapacks/` 里：即使目录名带 `.bak`，它仍会被当成正常数据包加载，并可能覆盖新版 `mbuilder_import` 的 `survival: true`，表现为“生存模式仍然只让创造搭建”。启动日志现在会打印 `Loaded N buildable blueprints (A survival, B creative-only)`；B 不为 0 时先检查数据包列表。

## 构建和测试

需要 JDK 21 和 NeoForge 21.1.259 开发环境；AE2 通过 Maven Central 解析，不会打进模组 JAR。

```shell
./gradlew build
python -m unittest discover -s tools -p 'test_*.py'
./gradlew -PgameTests runGameTestServer
./gradlew clean build
```

最后一条生成不包含测试代码的发布 JAR。`src/gametest` 仅在显式 `-PgameTests` 时启用；测试使用隔离开发世界。

## 边界

- 不会自动扫描图鉴 JAR；必须执行 `/mbuilder import` 或使用数据包。
- 只对带已知 `formation` 适配器的蓝图调用原模组成型 API；不会猜测其他模组的成型/启动逻辑。自动成型失败不会回滚已放置方块。
- 不会自动合成缺失材料，不会从 ME 网络读取/启动机器，也不会代替控制器完成配方、能量或物品配置。
- 当前没有蓝图编辑器或撤销已提交建筑。
- 线框预览不是方块材质的半透明投影。存档中的材料和保护逻辑以服务端为准。
- 原模组特殊放置初始化、邻居回调、多方块成型和领地插件需逐模组实测。快照可恢复本工具改动的目标方块，**不能回滚其他模组在回调中修改的任意外部状态**。不宣称通用 ACID 事务。
- 生存白名单应由可信服务器/整合包作者审核；不要给未知模板开启生存材料映射。
- 本次没有覆盖 ATM10 全包机器成型或客户端 GPU 验收；详见 `VALIDATION.md`。

## English summary

Data-driven, server-authoritative multiblock construction tool for NeoForge 1.21.1. It hard-depends on AE2: bind an ME Interface to pull survival materials transactionally, or fall back to the player's main inventory. `/mbuilder import` converts an installed structure-guide JAR into a world datapack and reloads it. Search blueprints, lock an anchor, rotate and inspect wireframes, then confirm. Block-entity data and entities are never imported. A data-driven `formation` adapter can call a known mod API after placement (currently ExtendedAE Plus's ultimate matrix); machines without an adapter still need their native formation tool. Both sides need the mod.

MIT for this original implementation. See `NOTICE.md` for conceptual references and third-party asset boundaries.
