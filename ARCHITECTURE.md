# WarpLoad 架构说明（v1.1.6）

> 注：本文主体成稿于 v1.0.5，v1.1.x 的主要变化见 README；文末附变更补记。

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 模组名 / ID | WarpLoad / `warpload` |
| 目标平台 | Minecraft 1.20.1 + Forge 47.x（开发基线 47.4.20，生产兼容 47.4.23） |
| Java / Mixin | Java 17；Mixin 0.8.5 + MixinExtras 0.3.5（jarJar 内置） |
| 构建 | ForgeGradle 6.0.x + MixinGradle 0.7-SNAPSHOT（SpongePowered repo）；映射 official 1.20.1 |
| 构建产物 | `build/libs/warpload-<version>.jar`（含 MixinExtras，直接入 mods） |
| 代码规模 | 12 个缓存类 + 28 个 Mixin（18 公共 / 10 客户端）+ 5 个兼容类 + 1 个 AI 优化器 |
| 来源 | Lightspeed（LGPL-3.0）+ OptiLoad（反编译参考）+ 本项目新增 |

---

## 1. 定位与设计目标

**一句话**：把"资源包/数据包的查找、解析、烘焙"从"每次启动重算"变成"一次计算、多次复用"，并对失败做隔离，保证不因优化导致崩溃或视觉错误。

四条设计红线（按优先级）：

1. **绝不崩游戏**：所有快速路径带 try/catch 兜底，任何异常回退原版语义。
2. **绝不返回错误资源**：缓存与快速路径必须可证明等价；不确定时跳过优化。
3. **不做有损缓存**：只缓存能无损重建的对象（原始字节、EntryWithSource、四边面模型）。
4. **环境自适应**：检测 Fusion/Connector/ModernFix 等，按包/按实例降级。

---

## 2. 总体分层

```
┌────────────────────────────────────────────────────────┐
│ 配置层   WarpLoadConfig（ForgeConfigSpec + 静态镜像）     │
├────────────────────────────────────────────────────────┤
│ 事件层   WarpLoad(main) / TitleScreenInjector            │
├────────────────────────────────────────────────────────┤
│ Mixin 层 资源包6 + 数据包4 + 模型4 + 渲染2 + 杂项2 + 启动1 │
├────────────────────────────────────────────────────────┤
│ 兼容层   Fusion / Connector / ModernFix / FTBChunks      │
│          ResourceReloadFailureGuard（失败隔离）           │
├────────────────────────────────────────────────────────┤
│ 缓存层   GlobalCache（资源包总线）                        │
│          DatapackCache / JsonReloadCache / TagReloadCache│
│          ModelBakeCache / CacheIO / PackFingerprint ...  │
├────────────────────────────────────────────────────────┤
│ 支撑层   CachePaths / CacheUtil / SoftValueCache / 接口   │
└────────────────────────────────────────────────────────┘
```

数据流总览：

```
客户端启动 → MinecraftMainMixin 提前拉起 .ser 缓存装载
          → 资源重载在专用 ForkJoinPool 上跑
          → 每个包查询走"索引短路 → 并行查找 → 原版回退"
世界加载  → 数据包 zip 解压到 world/warpload_cache
          → JSON/Tag 优先查磁盘缓存 → 缺失则并行解析并回写
标题界面  → 持久化 .ser → 清理堆缓存
```

---

## 3. 包结构总览

```
com.warpload
├── WarpLoad.java                     主类（@Mod），事件与兼容开关
├── ModConstants.java                 modid、外部 modid 常量
├── client/
│   └── ClientModelCacheHooks.java    客户端类隔离钩子（避免服务端加载 BakedModel）
├── compat/
│   ├── FusionPackCompat.java         Fusion overrides 探测与转接（实例级缓存）
│   ├── ResourceReloadFailureGuard.java 重载失败隔离
│   ├── ModernFixCompat.java          dynamic_resources 探测
│   ├── FTBChunksCompat.java          强制加载区块探测（含反射内部类）
│   └── FTBChunksCompatInternal.java  仅在 FTB 存在时加载的反射实现
├── config/
│   └── WarpLoadConfig.java           分段配置 + apply() 同步到全局静态
├── entity/
│   └── EntityAiOptimizer.java        玩家半径内 AI 门控
├── events/
│   └── TitleScreenInjector.java      启动计时 + 缓存持久化
├── interfaces/
│   ├── ICache.java                   持久化+清理协议
│   ├── IPackResources.java           存在性缓存协议
│   ├── IIndexedPack.java             索引存在性协议
│   └── IPathResourcePack.java        路径包协议（setModFile/异步预载）
├── util/
│   └── CacheUtil.java                .ser Java 序列化读写
├── cache/
│   ├── CachePaths.java               统一缓存根解析
│   ├── CacheIO.java                  gzip 二进制 IO、原子替换、文件锁
│   ├── CacheMemory.java              堆缓存清理策略
│   ├── PackFingerprint.java          SHA-256 指纹（mod/数据包/kubejs）
│   ├── SoftValueCache.java           软引用 LRU 内存缓存
│   ├── GlobalCache.java              资源包层总线（线程池/持久映射/并行查找）
│   ├── DatapackCache.java            zip 数据包解压缓存
│   ├── JsonReloadCache.java          JSON 原始字节磁盘缓存（会话门控）
│   ├── ParallelJsonParser.java       并行 JSON 解析 + 原字节采集
│   ├── TagReloadCache.java           Tag 磁盘缓存（会话门控）
│   └── ModelBakeCache.java           模型烘焙缓存（默认关）
└── mixin/
    ├── MinecraftMainMixin.java
    ├── misc/      MaterialMixin, ResourceLocationMixin, SelectorMixin
    ├── model/     BlockModelMixin, ModelBakeryMixin, MultiPartMixin, MultivariantMixin
    ├── renderer/  EntityRenderersMixin, ForgeHooksClientMixin
    ├── resources/ 12 个（见 §6.1）
    └── server/    6 个（见 §6.2）
```

---

## 4. 核心抽象与接口

### 4.1 ICache

```java
public interface ICache {
    void warpload$persistAndClearCache();
}
```

所有可持久化对象实现该接口并注册到 `GlobalCache.CACHES`；标题界面统一调用持久化，随后清空实例内地图。

### 4.2 IPackResources

```java
public interface IPackResources extends ICache {
    Map<String, Boolean> warpload$getExistenceByResource();
    void warpload$setExistenceByResource(Map<String, Boolean> map);
}
```

存在性缓存协议：字符串 key 为 `ResourceLocation.toString()`，值为资源是否存在。

### 4.3 IIndexedPack

```java
public interface IIndexedPack {
    Boolean warpload$hasIndexedResource(PackType type, ResourceLocation location);
}
```

三态语义（**关键设计**）：

- `TRUE`：索引确定存在 → 直接打开，跳过磁盘探测；
- `FALSE`：索引确定不存在 → 直接返回 null，**跳过所有包的磁盘探测**；
- `null`：索引未建立/不可判定 → 调用方回退原版逐包查找。

### 4.4 IPathResourcePack

```java
public interface IPathResourcePack extends PackResources, IPackResources, IIndexedPack {
    void warpload$setModFile(IModFile modFile);
    void warpload$startAsyncPreload();
}
```

Forge 路径包专用：绑定模组文件（决定缓存命名）并启动异步索引预载。

---

## 5. 缓存层详解

### 5.1 CachePaths —— 统一缓存根

三种根：

| 方法 | 路径 | 用途 |
|---|---|---|
| `gameCacheRoot()` | `<gamedir>/warpload-cache/v1` | 资源包层 .ser、模型缓存 |
| `activeCacheRoot()` | `world/warpload_cache`（世界激活时）否则 gameCacheRoot | 运行时数据包缓存 |
| `reloadCacheRoot()` | `activeCacheRoot()/reload` | JSON/Tag 磁盘缓存 |

设计意图：**资源包层缓存跨世界共享**（键为模组文件），**数据包层缓存跟随世界**（键包含数据包指纹），避免换世界后指纹污染。

### 5.2 CacheIO —— 二进制 IO 基座

- `gzipIn/gzipOut`：GZIP **level 1**（写入速度优先；读快于解析本身）。
- 格式原语：`writeString/readString`（int 长度 + UTF-8）、`readJsonElement`、`mapCapacity`（按 0.75 负载因子预分配）。
- `moveReplace`：先写临时文件再 `ATOMIC_MOVE`；不支持原子移动时退化为 REPLACE，已存在则丢弃临时文件。
- `lockFor(Path)`：全局 `ConcurrentHashMap<规范路径, ReentrantLock>`；`tryLock` 失败的写入直接放弃（下次启动再写），保证**写盘永不阻塞游戏线程**。
- 安全：缓存字符串长度上限 64MB，防损坏文件导致 OOM。

### 5.3 CacheUtil —— .ser 序列化

- 目录：`hasResource/`、`namespaces/`、`resourceLists/`，文件名 = 包指纹 ID `.ser`。
- 直接 Java 序列化 `ConcurrentHashMap`（一次性读出，读性能最好；写入发生在标题界面后台线程）。
- `persist` 的 mkdir 竞争做了容错（`!mkdirs() && !isDirectory()` 才告警）。

### 5.4 PackFingerprint —— 失效核心

指纹组成（SHA-256，每目录缓存一次）：

```
v=6 | dir=<监听器目录> | mods=<排序后 modId@version 的聚合哈希>
    | datapacks=<世界 datapacks 目录条目哈希> | kubejs=<kubejs 目录哈希>
```

- `datapacks`：目录条目 = 名称 + 普通文件 size/mtime；目录型数据包只哈希 `pack.mcmeta` 的 size/mtime。
- `kubejs`：KubeJS 存在时遍历 `kubejs/`（排除 `logs/`、`exported/`），文件名 + size + mtime；**会话内只算一次**（进程级缓存）。
- 目的：跨会话数据变更可失效；会话内变更由"会话门控"兜底（见 §12）。

### 5.5 GlobalCache —— 资源包层总线

**状态标志**（volatile，由配置同步）：`isEnabled`、`shouldCacheWalkedPaths`、`shouldCacheEmptyNamespaces`、`shouldCacheResourceExistence`、`shouldCacheMaterials`、`shouldAsyncPreloadPacks`、`shouldParallelizeResourcePackLookup`、`shouldUseDedicatedResourceReloadExecutor`、`parallelLookupMinPacks`、`shouldIsolateModdedResourceReloadFailures`、`isolatedResourceReloadListenerPatterns`。

**线程池**：

| 池 | 默认大小 | 命名 | 用途 |
|---|---|---|---|
| `EXECUTOR` | `max(2,min(cores,32))` | `WarpLoad-N` | 并行包查找、通用异步 |
| `CACHE_EXECUTOR` | `max(2,min(cores/4,8))`，低优先级 | `WarpLoad-Cache-N` | .ser 装载、后台扫描、持久化 |
| `RESOURCE_RELOAD_EXECUTOR` | `max(2,min(cores-2,32))` | `WarpLoad-Reload-N` | 专用 ForkJoinPool（work-stealing，asyncMode） |

可由系统属性覆盖：`-Dwarpload.workers` / `warpload.cacheWorkers` / `warpload.reloadWorkers`。

**持久映射**（跨 mod 共享、异步装载）：

- `PERSISTED_EXISTENCES_BY_MOD: id -> (resource -> exists)`
- `PERSISTED_NAMESPACES_BY_MOD: id -> (PackType -> Set<namespace>)`
- `PERSISTED_RESOURCE_LISTS_BY_MOD: id -> (PackType -> (namespace -> List<相对路径>))`

**组合任务 API**：

- `loadPersistedCacheAsync(dir,id,map)`：单模组惰性装载；
- `loadPersistedCachesAsync()`：`Main.main` 阶段批量装载（命名空间 + 文件清单）；
- `supplyCacheAfterPersistedLoad(name,task)`：等待批量装载完成后再执行（预载任务用）；
- `executeCacheLogged` / `trackBackgroundTask`：所有后台任务登记，持久化前统一等待。

**并行查找**：

```java
findFirstResource(packs, type, location)
  ├─ 段内任一包不安全/带过滤 → 顺序查找（保序）
  └─ 安全段：对每个包 supplyAsync(getResource, EXECUTOR)，
     按原顺序收集 future，先命中的返回（严格保持原版优先级）
```

`isSafeForParallelLookup` 白名单：Forge PathPackResources、原版 PathPackResources、FilePackResources，且 `!FusionPackCompat.hasOverrides(pack)`。

**持久化与清理**：`persistAndTrimCaches()` 先等缓存装载/后台任务，再并发持久化每个 `ICache`（WeakHashMap 弱引用集合，避免 F3+T 反复创建包实例导致泄漏），最后清空共享映射；`clearCaches()` 额外置 `isEnabled=false`。

### 5.6 DatapackCache —— 世界数据包解压

- **激活点**：`FolderRepositorySource.loadPacks`（仅 `PackType.SERVER_DATA`）→ `activate(world/datapacks)`；`ServerStopping` → `deactivate()`。
- **要点**：
  - 缓存目录 `world/warpload_cache/<sha1(zip绝对路径)>`；
  - 有效性 = 元文件 `.warpload_meta` 的 `source/size/lastModified` 与当前 zip 完全一致，且解压目录含 `pack.mcmeta`；
  - 解压到 `*.tmp-<tid>` 后原子移动；
  - zip 条目路径 `resolveSafe` 防目录穿越；
  - 兼容 PowerShell 风格目录条目（无 `/` 结尾被当文件）：文件写入前递归找最深已存在的非目录祖先并清除，目录条目冲突时先删文件。
- **开关**：`datapack.extractZipDatapacks`、`cacheFolder`、`cacheExternalZips`（世界加载期间的非世界 zip）。

### 5.7 JsonReloadCache —— JSON 原始字节缓存

- 格式 v6：`data.bin.gz`（版本 + 条目数 + `id` 字符串 + 原字节长度 + 原字节）+ `fingerprint.txt`。
- 键：**资源 ID 字符串**（非文件路径），读取时无需再算 `fileToId`。
- 语义：**只存原始字节，不在存盘时序列化对象**；读取后由 `ParallelJsonParser.parseRaw` 用监听器自己的 Gson 并行解析（与 GTOLib 思路一致）。
- 内存层：`SoftValueCache<Map<String,byte[]>>`（软引用，配置上限 16 条）。
- 会话门控：`SERVED_THIS_SESSION`（本会话首次调用才允许读缓存）/`STORED_THIS_SESSION`（首次计算才允许写盘）。
- 默认目录：`recipes`、`advancements`、`loot_tables`；可全量或追加。

### 5.8 ParallelJsonParser —— 并行解析

```
parseDirectory(directory, gson, rm, profiler)
  ├─ FileToIdConverter.json(dir).listMatchingResources(rm)   （受并行查找加速）
  ├─ entrySet().parallelStream()
  │   每项：open() → readAllBytes() → 可缓存则收集原字节
  │         → gson.fromJson(bytes, JsonElement) → isJsonObject 才入表
  └─ 完成后 storeRaw（会话门控 + tryLock）

parseRaw(gson, rawMap) → 同样并行、同样 isJsonObject 过滤
```

错误处理与原版一致（单文件失败只记录日志，不中断）。

### 5.9 TagReloadCache —— Tag 磁盘缓存

- 格式 v5：`Map<ResourceLocation, List<TagLoader.EntryWithSource>>`（entry/source/remove 全量字段）。
- 重度目录清单：`tags/items|blocks|entity_types|fluids|worldgen/biome|damage_type|banner_pattern|worldgen/structure|game_events|painting_variant`。
- 内存软缓存 + 会话门控，与 JSON 同策略。
- 读出即构造 `TagEntry.tag/optionalTag/element/optionalElement`，不依赖注册表即可重建。

### 5.10 ModelBakeCache —— 模型烘焙缓存（默认关）

- 存盘内容：每模型 `ao/gui3d/blockLight`、粒子精灵引用、8 组 ItemTransform 拍平数组、无向面 + 6 向面四边面列表（顶点 int[]、tint、方向、shade、AO、精灵引用）。
- 重建：`SimpleBakedModel` + `ItemOverrides.EMPTY`。
- 跳过条件（防误用）：
  - `isCustomRenderer()`（自绘模型无法重建）；
  - 四边面总数 > 8192（防巨模）；
  - 磁盘条目 < 期望数 90% 或缺失 `MISSING_MODEL_LOCATION`；
  - 模型总数 > `modelBakeMaxModels`（默认 2 万，控峰值内存）；
  - **ModernFix `dynamic_resources` 开启时自动禁用**；
  - OOM 捕获后 `System.gc()` 回退实时烘焙。
- 指纹：mod 列表 + 已选资源包 ID（排序）+ dynamic_resources 状态。
- 写盘：单守护线程 + `STORE_IN_PROGRESS` 原子位 + 中断位。

### 5.11 CacheMemory —— 堆清理策略

- `clearAll()`：JSON/Tag 软缓存 + （仅客户端）模型缓存 + 两处锁表；服务端通过 `ClientModelCacheHooks` 隔离加载 `BakedModel` 相关类（曾修复专用服崩溃）。
- `afterHeavyLoad()`：受 `memory.clearMemoryAfterReload` 控制；调用点：服务器启动完成、模型烘焙完成、标题界面。

### 5.12 SoftValueCache

`ConcurrentHashMap<String, SoftReference<V>>` + 死引用清理 + 条目上限（超限 FIFO 移除最早条目）。OOM 时由 GC 自然回收。

---

## 6. Mixin 层详解

### 6.1 资源包 Mixin（12 个）

| Mixin | 目标 | 注入点 | 作用 |
|---|---|---|---|
| `AbstractPackResourcesMixin` | AbstractPackResources | `<init>` RETURN | 注册 ICache；提供惰性 volatile 存在性地图 |
| `FilePackResourcesMixin` | FilePackResources | `<init>`/`listResources` HEAD | zip 条目清单 + 类型内键集合索引；listResources 从清单重放；**整体 try/catch 兜底** |
| `FilePackResourcesAccessor` | FilePackResources | Accessor | 暴露 `file`（解压替换用） |
| `PathResourcePackMixin` | Forge PathPackResources | `<init>`、`resolve`、`getNamespaces`、`getRootResource`、`getResource`、`listResources` | 解析路径缓存、命名空间缓存、存在性缓存、索引化 getResource/listResources、异步预载；`lang/` 目录特判为 CLIENT_RESOURCES |
| `VanillaPathResourcePackMixin` | 原版 PathPackResources | `getNamespaces`、`getResource`、`listResources` | 文件夹包与解压数据包的全量路径索引（`namespace/path` 集合） |
| `VanillaPackResourcesMixin` | VanillaPackResources | `<init>`、`getResource` HEAD/RETURN | 原版包客户端/服务端双存在性缓存（按 MC 版本命名） |
| `FallbackResourceManagerMixin` | FallbackResourceManager | `getResource` HEAD | 从尾到头累积"安全段"；索引段判定 → 并行/顺序查找 → 原版保序回退；`createResource`/`createStackMetadataFinder` Invoker |
| `FallbackResourceManagerPackEntryAccessor` | PackEntry 记录 | Invoker | 读取 name/resources/filter/isFiltered |
| `DelegatingResourcePackMixin` | Forge DelegatingPackResources | `getResource` HEAD | 候选包并行首命中 |
| `SimpleReloadInstanceMixin` | SimpleReloadInstance | `<init>` WrapOperation | 每个监听器重载包一层失败隔离 |
| `MinecraftReloadExecutorMixin` | Minecraft | `<init>`/`reloadResourcePacks` WrapOperation | 资源重载改用专用 ForkJoinPool |
| `ResourcePackLoaderMixin` | Forge ResourcePackLoader | `createPackForMod` RETURN | 绑定 IModFile + 触发异步预载 |

索引化的三态回退在 `FallbackResourceManagerMixin` 中体现：段内所有包都返回确定态才提前返回；任一 null 即放弃索引路径改走原版逻辑。

### 6.2 数据包/服务端 Mixin（6 个）

| Mixin | 目标 | 作用 |
|---|---|---|
| `SimpleJsonResourceReloadListenerMixin` | `prepare` HEAD cancellable | 缓存命中→并行重解析返回；未命中且开关开→自研并行解析（含采集原字节） |
| `TagLoaderMixin` | `load` HEAD/RETURN | 缓存读 + 结果写（会话门控） |
| `FolderRepositorySourceMixin` | `loadPacks` HEAD/RETURN | 进入 SERVER_DATA 上下文激活缓存 |
| `WorldLoaderPackConfigMixin` | `createResourceManager` HEAD/RETURN | 包裹解压窗口 |
| `PackMixin` | `Pack.open` RETURN | zip→解压文件夹包替换；Fusion overrides 转接（反射 `PackExtension#getFusionMetadata` + `PackResourcesExtension#setFusionOverridesFolder`） |
| `MobAiMixin` | `Mob.serverAiStep` HEAD cancellable | 玩家半径外冻结 AI（含豁免表） |

### 6.3 模型/杂项/渲染/启动 Mixin（10 个）

| Mixin | 目标 | 作用 |
|---|---|---|
| `ModelBakeryMixin` | `bakeModels` HEAD cancellable | 命中模型缓存则整批跳过 |
| `BlockModelMixin` | `getMaterial` | 每模型材质查找缓存 |
| `MultiPartMixin` / `MultivariantMixin` | `getDependencies` | 依赖集合缓存（依赖解析是热路径） |
| `MaterialMixin` / `ResourceLocationMixin` | `hashCode` | 哈希缓存（烘焙期海量哈希） |
| `SelectorMixin` | `getPredicate` | 按 StateDefinition 弱键缓存谓词 |
| `EntityRenderersMixin` | `createEntityRenderers` 的 `Map.forEach` | 单个渲染器构造失败 → NoopRenderer 兜底，不中断整批 |
| `ForgeHooksClientMixin` | `loadLayerDefinitions` forEach / `onModifyBakingResult` RETURN | 层定义失败隔离；模型缓存写盘触发 |
| `MinecraftMainMixin` | `Main.main` tryDetectVersion 后 | 启动早期拉起 .ser 装载 |

注：曾有的 `KeyValueConditionMixin`（重定向 Guava `Splitter.splitToList`）在 1.0.4 移除——非 MC 成员在 SRG 运行时无法可靠注入（生产环境 `InjectionError`）。

---

## 7. 兼容层

### 7.1 FusionPackCompat

三级防护：

1. **加载期**：`isFusionLoaded()` 进程级缓存（未安装时所有检查短路为零成本）。
2. **实例级**：`WeakHashMap<pack, Boolean>` 缓存 `hasOverrides` 结果——修复了“每次资源查找反射读字段”的重载回归（实测省 17-22s）。
3. **元数据级**：`getOverridesFolder(Pack)` 走 `PackExtension#getFusionMetadata`；`applyOverridesFolder(新包, folder)` 把 overrides 转接到我们解压/替换后的包上（若 Fusion 的注入后执行，会重复设置同值，幂等）。

检测字段后缀：`overridesFolder` / `overridesFolderRoot` / `overridesFolderName`（覆盖 File/原版Path/ForgePath 三种 Fusion Mixin）。

### 7.2 ResourceReloadFailureGuard

- `guard(listener, reload)`：同步异常与 future 异常都处理；成功后原样返回。
- 隔离条件（全部满足）：全局开关开、非致命错误（排除 `VirtualMachineError`/`ThreadDeath`）、监听器类命中配置模式、且异常栈中存在监听器自身包/类的帧（`isOwnedBy`）——**避免把玩家自己的核心模组错误吞掉**。
- 同类机制用于实体渲染器与模型层定义：失败单元替换为 Noop/跳过，并一次性告警。
- 核心类前缀（`net.minecraft`、`net.minecraftforge`、`com.mojang`、`java` 等）永不被隔离。

### 7.3 ModernFixCompat

- 探测 `config/modernfix-mixins.properties` 中 `mixin.perf.dynamic_resources`；
- 无文件则反射 `ModernFixMixinPlugin.instance.isOptionEnabled(...)`；
- 结果缓存；命中时禁用模型烘焙缓存并只提示一次。

### 7.4 FTBChunksCompat

- `isLoaded` 缓存；
- `isForceLoaded` = 原版 `getForcedChunks` ∪ FTB API（`FTBChunksAPI`/`ChunkDimPos`/`isActuallyForceLoaded`，全部反射，类隔离在 `FTBChunksCompatInternal`，避免硬依赖）。

---

## 8. 配置系统

`WarpLoadConfig`：`ForgeConfigSpec` 构建 + 静态字段镜像，`apply()` 一次性同步到两处：

1. 本类静态字段（供非 Mixin 代码读取，可在配置热重载时更新）；
2. `GlobalCache` 的 volatile 标志（Mixin 热路径读取，避免 getter 开销）。

构造期 `apply()` 可能因配置未附加抛 `IllegalStateException`（Forge 行为），catch 后保留默认值，等 `ModConfigEvent` 再正式应用。

配置段：`root`（enabled/logCacheEvents）、`startup`（9 项）、`datapack`（3 项）、`reloadCache`（6 项）、`modelCache`（2 项）、`ai`（5 项）、`compatibility`（3 项）、`memory`（3 项）。

兼容降级矩阵（`WarpLoad.applyCompatibilityFlags()`）：

| 检测 | 动作 |
|---|---|
| Connector 且开关开 | 关异步预载/专用线程池/并行查找/路径缓存/存在性缓存/失败隔离 |
| sophisticatedstorage + jsonthings | 关路径缓存（已知二者对资源枚举顺序敏感） |
| multiblocked | 关材质缓存 |
| Fusion | 按实例跳过 fast path；解压时转接 overrides |

---

## 9. 事件与生命周期

```
模组构造     注册配置、注册事件、apply()
FMLCommonSetup   打印就绪日志
Main.main（早期） GlobalCache.loadPersistedCachesAsync()（客户端）
资源重载      包实例创建 → 注册 ICache → 索引随查询惰性建立
              异步预载（Forge 包）在缓存装载完成后启动
标题界面      TitleScreenInjector：
              记录 JVM uptime 并写入品牌行
              后台 persistAndTrimCaches()（持久化 .ser + 清共享映射）
              CacheMemory.afterHeavyLoad()
进入世界      FolderRepositorySource：激活 DatapackCache + 进入 SERVER_DATA 深度
              Pack.open：zip→解压包替换（含 Fusion 转接）
              JSON/Tag：查缓存 → 并行解析 → 回写
服务器启动完成 CacheMemory.afterHeavyLoad()
服务器停止    DatapackCache.deactivate() + CacheMemory.clearAll()
              （客户端额外取消模型缓存写盘）
```

---

## 10. 关键数据流（逐步）

### 10.1 客户端启动

```
JVM → ModLauncher → Mixin 应用（28 个）
  → Minecraft.<init>（捕获 backgroundExecutor：此处决定初始重载线程池）
  → 模组构造 → 配置装载 → ModConfigEvent → apply()
  → 初始资源重载（监听器分布在 ForkJoinPool；并行查找生效）
  → 标题界面：持久化 + 清理
```

### 10.2 单资源查找（getResource）

```
ResourceManager.getResource(loc)
  → FallbackResourceManager.getResource
      ├─ 安全段索引判定（IIndexedPack 三态）
      ├─ 段 ≥ parallelLookupMinPacks 且全安全 → 并行首命中
      └─ 否则原版顺序回退（过滤/优先级语义完全一致）
  → 包本体的索引 fast path（Path/File/VanillaPath）：
      确定存在 → 直接开流；确定不存在 → null；未建索引 → 原版
```

### 10.3 listResources

- Forge 路径包：读相对路径清单 → 按前缀过滤 → `tryBuild` → 输出（异常回退原版遍历）。
- zip 包：从缓存的条目名列表按 `目录/命名空间/路径/` 前缀重放。
- 原版路径包：全量索引集合按 `namespace/path/` 前缀过滤。
- VanillaPackResources：仅存在性缓存，listResources 不拦截。

### 10.4 数据包 JSON/Tag

```
prepare(rm, profiler)
  → tryLoadRaw（会话首次 + 指纹匹配 + 软缓存/磁盘）
      命中：gson 并行解析原字节 → 返回
      未命中：FileToIdConverter 枚举（受并行查找加速）
             → parallelStream：读字节/解析/收集
             → storeRaw（会话首次 + tryLock + 原子替换）
  → 失败/关闭开关：返回空转交原版
```

### 10.5 zip 数据包解压

```
Pack.open RETURN
  → FilePackResources? → getOrExtract(zip)
      shouldCache?（世界 datapacks 目录 或 SERVER_DATA 深度>0 且 cacheExternalZips）
      有效缓存？→ 复用
      否则：staging 解压（目录条目容错）→ 校验 pack.mcmeta → 写 meta → 原子替换
  → 关闭原 zip 包 → 返回 PathPackResources(解压目录)
  → 读 Fusion 元数据 → applyOverridesFolder(新包, folder)
```

### 10.6 模型烘焙

```
bakeModels HEAD
  → ModelBakeCache.trySkipBake(bakedTopLevel, topLevel.size(), spriteGetter)
      前置：开关/ModernFix/上限/指纹/磁盘存在
      回灌：读四边面 → 查精灵 → 重建 SimpleBakedModel
      校验：≥90% 覆盖 且 含 MISSING_MODEL
      全部通过 → cancel()（整批跳过）
  → 正常烘焙（1.20.1 本身并行）
onModifyBakingResult RETURN → tryStore（后台单线程）
```

---

## 11. 并发与线程模型

| 执行体 | 上下文 | 约束 |
|---|---|---|
| 重载监听器 | 专用 FJP（≤32 线程，work-stealing，FIFO） | 包实现必须线程安全 |
| 包索引 | 惰性 + `synchronized(this)` + `volatile` 字段 | 双检锁；重复初始化无害 |
| 并行查找 | `EXECUTOR` 固定池 | 只对白名单包并行；严格保序收集 |
| .ser 装载/扫描/持久化 | `CACHE_EXECUTOR`（低优先级） | 与游戏线程竞争时让路 |
| JSON/Tag/模型写盘 | 每次写 `tryLock`（拿不到即放弃） | 永不阻塞；下次启动补写 |
| 共享集合 | ConcurrentHashMap / 弱引用同步集合 | `CACHES` 为弱引用集合防泄漏 |

**已知时序要点**：`Minecraft.<init>` 早于配置装载，初始重载的线程池在配置生效前就被捕获——因此"专用线程池"开关对**首次重载**实际无效，仅影响后续重载。

---

## 12. 缓存失效与正确性策略

| 层 | 跨会话失效 | 会话内失效 |
|---|---|---|
| .ser 资源包缓存 | 键 = 模组文件 + 版本 + jar 名（文件变则键变） | 每次热重载创建新包实例，索引自然重建 |
| JSON/Tag 磁盘+内存 | PackFingerprint（mod 列表 / 数据包 / kubejs） | **会话门控**：同会话第二次及以后的重载永不读缓存、不覆盖已写缓存 |
| 数据包解压 | zip 的 size+mtime 元文件校验 | 同上（size/mtime 变化即时重解压） |
| 模型烘焙 | mod 列表 + 已选资源包 + dynamic_resources | 仅首个烘焙周期有效（`APPLIED_THIS_CYCLE`） |
| 生存性 | 所有快速路径 try/catch → 原版回退；三态索引不确定即放弃 | 同左 |

会话门控是面向 KubeJS `/kubejs reload`、`/reload`、F3+T 的核心正确性保障：**加速只发生在每个会话的第一次加载**（也就是启动），会话内热重载一律走新鲜路径。

---

## 13. 版本演进

| 版本 | 内容 |
|---|---|
| 1.0.0 | Lightspeed + OptiLoad 合并；新增并行 JSON、原版路径包索引、zip 索引、统一缓存根 |
| 1.0.1 | 全 Mixin 字段改**惰性 + volatile**；快速路径 try/catch；Fusion 全局兼容开关；修复解压目录条目兼容 |
| 1.0.2 | Fusion overrides 转接（解压包保留 overrides）；`Pack.open` 替换与 Fusion 注入顺序解耦 |
| 1.0.3 | JSON/Tag **会话门控**；指纹加入 kubejs 目录；`CACHES` 改弱引用（修 F3+T 泄漏） |
| 1.0.4 | **移除 KeyValueConditionMixin**（修复 SRG 生产环境 InjectionError 崩溃）；mkdir 竞争误报修复 |
| 1.0.5 | **Fusion 实例级缓存**修复（重载 113-118s → 96s）；构建加证书开关 |

---

## 14. 实测数据摘要（本机：E5-2697 v2 / 398 模组 / 远程服务器）

| 阶段 | 无 mod | 1.0.4 | 1.0.5 |
|---|---|---|---|
| ModLauncher→重载开始 | 114-127s | 120-122s | 138s¹ |
| 重载→标题界面 | 55-91s | 113-118s | **96s** |
| 连服→进游戏 | 90-97s | 95-98s | 95-98s² |

¹ 晚间会话普遍偏高（Gradle 守护进程常驻 + 包更新后的负载差异）。
² 远程服务器场景数据包缓存不参与，客户端收益仅资源包层。

结论：1.0.5 修掉 Fusion 反射回归后**不再有可测的负收益**；本机重载瓶颈在模型解析/图集/模组自身逻辑（CPU 密集），不在已优化层。

---

## 15. 已知限制与设计取舍

1. **远程服务器无数据包收益**：JSON/Tag/解压/AI 门控都在服务端或单机侧生效。
2. **模型烘焙缓存默认关**：与 ModernFix dynamic_resources 功能重叠；RenderType 与自绘模型不可无损重建。
3. **图集/动态生成不缓存**（分析结论）：图集受动画/光影/CTM 与失效成本约束；动态生成已由 Moonlight 等自带磁盘缓存（实测仅 3.5s）。
4. **专用线程池对首次重载不可配**：构造捕获时序所致；后续重载与 F3+T 受控。
5. **老 CPU 并行收益有限**：并行查找/索引在 IO 受限场景收益明显，CPU 受限时中性。
6. **.ser 为 Java 序列化**：体积偏大但读取最快；写入在标题界面后台执行。

---

## 16. 配置项参考

| 段 | 键 | 默认 | 作用 |
|---|---|---|---|
| - | enabled | true | 总开关（关闭=全部 fast path 直通原版） |
| - | logCacheEvents | true | 缓存事件日志 |
| startup | asyncPreloadPacks | true | 启动期异步预建索引 |
| startup | dedicatedResourceReloadExecutor | true | 专用 ForkJoinPool |
| startup | parallelResourceLookup | true | 并行包查找 |
| startup | parallelLookupMinPacks | 4 | 并行最小段长 |
| startup | cacheResourceExistence | true | 存在性缓存 |
| startup | parallelJsonParsing | true | 并行 JSON 解析（亦是 JSON 缓存前置） |
| startup | cacheWalkedPaths / cacheEmptyNamespaces / cacheMaterials | true | 索引/命名空间/材质缓存 |
| datapack | extractZipDatapacks | true | zip 数据包解压 |
| datapack | cacheFolder | warpload_cache | 世界内缓存目录名 |
| datapack | cacheExternalZips | true | 外部 zip 也解压 |
| reloadCache | jsonReloadCacheEnabled | true | JSON 磁盘缓存 |
| reloadCache | jsonReloadCacheAllDirectories | false | 全目录缓存 |
| reloadCache | jsonReloadExtraDirectories | [] | 追加目录 |
| reloadCache | tagReloadCacheEnabled | true | Tag 磁盘缓存 |
| reloadCache | tagReloadCacheAllDirectories / tagReloadExtraDirectories | false / [] | 同上 |
| modelCache | modelBakeCacheEnabled | false | 模型烘焙缓存 |
| modelCache | modelBakeMaxModels | 20000 | 缓存上限（防峰值内存） |
| ai | aiOptimizationEnabled | true | AI 门控 |
| ai | aiActivationRadius | 10.0 | 激活半径 |
| ai | aiKeepNamedActive / aiClearTargetWhenFrozen / aiRespectFtbForceLoaded | true | 门控细节 |
| compatibility | isolateModdedResourceReloadFailures | true | 失败隔离 |
| compatibility | isolatedResourceReloadListenerPatterns | ["*"] | 隔离模式 |
| compatibility | connectorCompatibilityMode | true | Connector 降级 |
| compatibility | fusionCompatibilityMode | true | Fusion 感知兼容 |
| memory | memoryCacheEnabled / memoryCacheMaxEntries / clearMemoryAfterReload | true / 16 / true | 堆缓存策略 |

---

**总结**：WarpLoad 的架构本质是**四件事**——①用"持久化索引 + 三态判定"消灭资源查找的磁盘探测；②用"原始字节磁盘缓存 + 会话门控 + 指纹"消灭数据包解析的重复计算；③用专用重载池与失败隔离提升重载成功率与吞吐；④用"兼容层 + 惰性字段 + try/catch 兜底"保证任何环境都不崩、不脏读。所有优化都可开关、可回退、可观测。

---

## 附：v1.1.x 变更补记（相对本文 v1.0.5 主体）

* **GTCEu 配方数据缓存**（`integration.gtRecipeDataCache`）：捕获 GT 动态数据包（约 64031 条配方）并在后续启动跳过重生成；指纹改用内容哈希，稳定版本后二次启动命中。
* **`startup.parallelResourceLookup` 默认关闭**：索引就绪后每包派发一个任务的开销大于顺序 O(1) 查表；实测 Moonlight 动态资源生成 7.9s → 1.4s（总生成 10.3s → 4.2s）。`GlobalCache.findFirstResource` 同步增加了"索引优先短路"，即使开启并行也不再全量派发。
* **`startup.reloadWorkers`**：重载池并行度可配置（0 = 自动，核数-2），支持运行期 config reload 时换池。
* **诊断工具变更**：`debug.reloadTimeline`（每秒采样：进程 CPU / 各线程池活跃与排队 / Moonlight 内部线程池）与 `debug.moonlightProbe`（Moonlight 生成起止+耗时）。早期用于定位 JEI/TC 主线程卡死的 `StallWatchdog` 已整体移除。
* 文中配置表为 v1.0.5 时点值，若与 README / 实际 `config/warpload-common.toml` 不一致，以后者为准。
