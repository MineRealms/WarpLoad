# WarpLoad

**MineRealmsPowered 出品** · Produced by **MineRealmsPowered**

Minecraft **1.20.1 Forge** 的启动 / 资源重载加速模组，面向大型整合包：把"资源包与数据包的查找、解析、烘焙"从**每次启动重算**变成**一次计算、多次复用**，并对第三方加载失败做隔离。
由 **Lightspeed**（LGPL-3.0）与 **OptiLoad** 合并重写而来，并持续加入新优化。

*Startup & resource-reload accelerator for heavy Minecraft 1.20.1 Forge modpacks.*

## 特性

### 资源包层
- 每 mod **持久化资源索引**（命名空间 / 目录遍历 / 存在性检查），启动期异步载入、标题界面或进服后统一落盘（quickplay / 直连服务器同样生效），miss 不再碰磁盘
- Forge PathPack、原版 PathPack、zip 包全部走索引查询
- **专用 work-stealing 重载执行器**（并行度可配 `startup.reloadWorkers`）
- **重载失败隔离**：单个坏监听器 / 实体渲染器 / 模型层不再拖垮整个加载
- 微观缓存：ResourceLocation / Material hashCode、BlockModel 材质、MultiPart / MultiVariant / Selector

### 数据包层
- zip 数据包**解包**进 `world/warpload_cache/<sha1>`（size+mtime 校验），以索引目录包打开
- **JSON 重载磁盘缓存**（recipes / advancements / loot_tables，可扩展目录）
- 无缓存时**并行 JSON 解析**；重型 **Tag 目录磁盘缓存**
- 可选**模型烘焙缓存**（默认关）；**生物 AI 距离门控**（默认 10 格，FTB Chunks 强加载感知）

### 集成与诊断
- **GTCEu 配方数据缓存**：跳过格雷科技动态数据包配方重生成（约 6.4 万条），稳定版本后二次启动从数十秒降至秒级（指纹含内容哈希）
- **重载采样器 / Moonlight 生成探针**（`debug.*`，诊断用，可关）

## 实测观察（某大型整合包，仅供参考）
- 并行包查找在索引就绪后是**负优化**：Moonlight 动态资源生成 7.9s → 1.4s，全部生成 10.3s → 4.2s，故默认关闭
- 冷启动到主界面约 230–260s（受磁盘 / 网络波动影响大）
- 启动期与第三方卡死解耦，重载失败不再级联

## 配置（`config/warpload-common.toml` 摘要）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `startup.asyncPreloadPacks` | true | 启动期预载资源索引 |
| `startup.dedicatedResourceReloadExecutor` | true | 专用重载池 |
| `startup.reloadWorkers` | 0 | 重载池并行度（0 = 自动，核数-2） |
| `startup.parallelResourceLookup` | false | 并行包查找（实测负优化，默认关） |
| `startup.parallelJsonParsing` | true | 并行 JSON 解析 |
| `reloadCache.jsonReloadCacheEnabled` | true | JSON 磁盘缓存 |
| `reloadCache.tagReloadCacheEnabled` | true | Tag 磁盘缓存 |
| `reloadCache.reuseBuiltTags` | true | 同会话复用已构建 Tag 集合（原始条目指纹门控） |
| `reloadCache.contentHashedMods` | ["gtceu"] | 参与内容哈希的 mod（同版本换 jar 也能失效） |
| `datapack.extractZipDatapacks` | true | 解包 zip 数据包 |
| `modelCache.modelBakeCacheEnabled` | false | 模型烘焙缓存 |
| `ai.aiOptimizationEnabled` | true | 生物 AI 距离门控 |
| `integration.gtRecipeDataCache` | true | GTCEu 配方数据缓存 |
| `client.ldlCtmCache` | true | 持久化 LowDragLib CTM 元数据缓存（仅装 ldlib 时生效） |
| `debug.reloadTimeline` / `debug.moonlightProbe` | true | 诊断日志（可关） |

## 构建

- 需要 JDK 17：`./gradlew build`
- GT 集成部分编译需要 `libs/gtceu-1.20.1-7.5.3.jar`（自行从 GTCEu 发行渠道获取后放入 `libs/`；该文件不随仓库分发）

## 致谢与许可

- 基于 **Lightspeed** by CCr4ft3r & kltyton（LGPL-3.0）
- 参考 **OptiLoad** by NeoFastFTL（反编译研究）
- 思路借鉴 GTOCore / GTOLib（原始字节缓存、内容指纹、异步写盘）
- 本项目以 **LGPL-3.0** 发布

**MineRealmsPowered 出品**
