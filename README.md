# TFC + IE Prospecting

面向 **Minecraft 1.20.1 Forge** 的地质勘探玩法模组：在**屏蔽地下透视**的前提下，让玩家或 LLM 智能体像野外勘探员一样，
用有盲区、有漂移、有干扰的物探仪器逐步缩小异常范围，最后用有限次数的钻探取芯验证矿种。

它是一套「多工具、部分可观测（POMDP）状态推理」玩法的执行层：六件仪器各有量程与失效方式，
矿种物性由数据文件定义，靶场由 JSON 声明式生成。配套的 LLM 陪伴体模组见
[Light-xing/minecraft-numen](https://github.com/Light-xing/minecraft-numen)（1.20.1 fork）。

## 环境要求

| 项 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.x（构建基线 47.4.6） |
| Java | 17（编译与运行） |
| TerraFirmaCraft | `[3.1.0-beta,)` |
| Immersive Engineering | `[1.20.1-10.0.0-169,)` |
| Numen（可选，软依赖） | 0.1.5 的 1.20.1 定制版；用于 Lua 原语、状态注入与开局补给 |

## 功能

### 六件勘探仪器

全部为服务端判定，没有强制的客户端界面。

| 仪器 | 水平/垂向量程 | 耐久 | 机制要点 |
|---|---|---|---|
| 踏勘镐 `recon_pick` | 8 / 24 格 | 256（每次 2） | 必须对准 4.5 格内**裸露岩面**敲击；约 20% 假阴性；同区块累计 3 次一致有效采样收敛出疑似矿名，6 次饱和 |
| 密度探锤 `density_plumb` | 8 / 96 格 | 192（每次 1） | 报高/低密度符号与粗深度带，是全场最深的探测手段；每用 1 次漂移 +2 格，满 3 次强制回基点砝码校准 |
| 感应探棒 `induction_probe` | 24 / 64 格 | 免耐久 | 报导电强度、八向粗方位与距离/深度档；孔雀石不导电（盲区），石墨低密度但高导电（典型伪异常），地下水造成干扰 |
| 磁力探针 `magnetic_needle` | 32 / 48 格 | 免耐久 | 只响应磁铁矿（强磁）与赤铁矿（弱磁）；报 16 向角度；两点相距 ≥6 格可解算矿脉走向 |
| 回声探针 `echo_probe` | 干岩贴面 8 / 浸水 16 | 有磨损 | 必须紧贴石面或浸水耦合；报相邻空腔、水/岩浆与洞壁暴露矿面数，不穿透完整岩体 |
| 钻探取样器 `core_sampler` | 正下方 1×1 单列，默认 32 / 最大 64 | 256（每次 1） | **唯一真值工具**：输出精确岩层序列与含矿层厚度；见矿时产出 `core_sample` 样本实物入包 |

另有基点砝码 `base_weight`（密度探锤的漂移复位基准）与岩芯样本物品 `core_sample`。

### 数据驱动的矿石物性表

矿种的导电性 / 磁性 / 密度档 / 相似矿物混淆关系全部写在
`data/tfcie_prospecting/prospecting/ore_properties/*.json`，可被数据包覆盖或整表替换（`"replace": true`）。
默认表覆盖 TFC 的 32 个矿种，并与 TFC 1.20.x 的品位规则一致：12 种有品位金属矿为
`tfc:ore/<rich|normal|poor>_<ore>/<rock>`，其余无品位矿种为 `tfc:ore/<ore>/<rock>`。

### 与 Numen 的联动（软依赖）

- 在 Lua 沙箱内暴露 `prospect.instrument.*` 原语（踏勘镐 / 密度探锤 / 感应探棒 / 磁力探针 / 回声探针 /
  钻探取样器 + 岩芯查询 / 工具状态 / 调用历史），与玩家手动右键**走同一套守卫、耐久与冷却**；
- 向陪伴体的每轮上下文注入 `<prospecting_state>`（漂移、校准、岩芯、工具磨损）；
- 陪伴体出生时自动补一套基础工具；
- 每次仪器调用追加写入遥测账本 `tfcie_prospecting/telemetry/prospect_ledger.jsonl`。
- Numen 缺席时上述层全部静默跳过，模组本身照常工作。

### 命令

```
/prospect list                       # 列出全部工具
/prospect describe <tool>            # 读工具参数与当前磨损/漂移
/prospect use <tool> [<x> <y> <z>]   # 以玩家身份调用一次
/prospect kit [<player>]             # 发放整套基础装备
```

### 管理员靶场

```
/prospect arena list                         # 首次自动释放内置 map1~4.json（已存在不覆盖）
/prospect arena build <name> [<x> <z>]       # 声明式刷图；缺省以执行者脚下区块西北角为原点
/prospect arena starter [<player>|<name>] [<name>]   # 清背包发装备；已建图则传送到推荐起点
```
- 未完成。

## 构建

### 1. 先构建 Numen 的 API 制品（编译期契约，不打包进本模组）

本模组的 Lua API 注册层在编译期需要 Numen 的 API jar。先取得 Numen 的 1.20.1 源码并构建：

```bash
git clone --branch 1.20.1 https://github.com/Light-xing/minecraft-numen.git
cd minecraft-numen
./gradlew :numen-api:forge:build
# 产物：numen-api/forge/build/libs/numen_api-forge-1.20.1-0.1.5-api.jar
```

### 2. 构建本模组

```bash
git clone https://github.com/Light-xing/tfcie-prospecting.git
cd tfcie-prospecting
./gradlew build -PnumenRoot=/path/to/minecraft-numen
```

- `-PnumenRoot` 指向 Numen 源码根（也可用环境变量 `NUMEN_ROOT`）；
- 离线环境在命令后加 `--no-daemon --offline`（需预先缓存 Gradle/Maven 依赖）。
- 产物：`build/libs/tfcie_prospecting-1.20.1-0.1.0.jar`，连同 TFC、IE（以及可选的 Numen）一并放入 `mods/`。

### 3. 测试与开发运行

```bash
./gradlew test        # 纯 JUnit（224 例），不启动 Minecraft
./gradlew runClient   # 开发客户端（首次需联网下载依赖）
./gradlew runServer   # 开发服务端
```

## 许可

MIT License，见 [LICENSE](LICENSE)。
