package com.warpload.config;

import com.warpload.cache.GlobalCache;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class WarpLoadConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Master switch for all WarpLoad optimizations")
            .define("enabled", true);
    private static final ForgeConfigSpec.BooleanValue LOG_CACHE_EVENTS = BUILDER
            .comment("Log cache hits, misses, stores and extraction events")
            .define("logCacheEvents", true);

    private static final ForgeConfigSpec.BooleanValue ASYNC_PRELOAD_PACKS = BUILDER
            .push("startup")
            .comment("Preload Forge path resource pack indexes on WarpLoad worker threads during startup")
            .define("asyncPreloadPacks", true);
    private static final ForgeConfigSpec.BooleanValue DEDICATED_RESOURCE_RELOAD_EXECUTOR = BUILDER
            .comment("Use a dedicated work-stealing pool for resource reload preparation instead of competing for the shared Minecraft worker pool")
            .define("dedicatedResourceReloadExecutor", true);
    private static final ForgeConfigSpec.IntValue RELOAD_WORKERS = BUILDER
            .comment("Parallelism of the dedicated reload pool. 0 = auto (CPU cores - 2). Lower it when a mod's own parallel asset generation (e.g. Moonlight's dynamic resources) is slowed down by pool contention")
            .defineInRange("reloadWorkers", 0, 0, 64);
    private static final ForgeConfigSpec.BooleanValue PARALLEL_RESOURCE_LOOKUP = BUILDER
            .comment("Query safe resource-pack segments concurrently while preserving vanilla priority and filter order. Off by default: with path indexes in place, dispatching a task per pack per lookup costs more than sequential O(1) map hits and visibly slows mods that read many small resources (measured: Moonlight dynamic asset generation 7.9s -> 1.4s)")
            .define("parallelResourceLookup", false);
    private static final ForgeConfigSpec.IntValue PARALLEL_LOOKUP_MIN_PACKS = BUILDER
            .comment("Minimum safe pack segment size before WarpLoad uses parallel resource lookup")
            .defineInRange("parallelLookupMinPacks", 4, 2, 64);
    private static final ForgeConfigSpec.BooleanValue CACHE_RESOURCE_EXISTENCE = BUILDER
            .comment("Cache per-pack resource existence checks across launches")
            .define("cacheResourceExistence", true);
    private static final ForgeConfigSpec.BooleanValue PARALLEL_JSON_PARSING = BUILDER
            .comment("Parse data-pack JSON (recipes, advancements, loot tables and every SimpleJsonResourceReloadListener directory) in parallel when no disk cache is available")
            .define("parallelJsonParsing", true);
    private static final ForgeConfigSpec.BooleanValue CACHE_WALKED_PATHS = BUILDER
            .comment("Index directory walks of mod resource packs and reuse them for the whole session")
            .define("cacheWalkedPaths", true);
    private static final ForgeConfigSpec.BooleanValue CACHE_EMPTY_NAMESPACES = BUILDER
            .comment("Also cache namespace lookups that returned nothing")
            .define("cacheEmptyNamespaces", true);
    private static final ForgeConfigSpec.BooleanValue CACHE_MATERIALS = BUILDER
            .comment("Cache BlockModel material lookups during model baking")
            .define("cacheMaterials", true);
    static {
        BUILDER.pop();
    }

    private static final ForgeConfigSpec.BooleanValue DATAPACK_EXTRACTION_ENABLED = BUILDER
            .push("datapack")
            .comment("Extract zip datapacks into a folder inside the world once and use the folder afterwards. Reduces RAM and allows full path indexing")
            .define("extractZipDatapacks", true);
    private static final ForgeConfigSpec.ConfigValue<String> DATAPACK_CACHE_FOLDER = BUILDER
            .comment("Folder name created inside the world directory for extracted datapacks")
            .define("cacheFolder", "warpload_cache");
    private static final ForgeConfigSpec.BooleanValue CACHE_EXTERNAL_ZIPS = BUILDER
            .comment("Also cache zip datapacks that are not inside the world datapacks folder while the world loads")
            .define("cacheExternalZips", true);
    static {
        BUILDER.pop();
    }

    private static final ForgeConfigSpec.BooleanValue JSON_RELOAD_CACHE_ENABLED = BUILDER
            .push("reloadCache")
            .comment("Cache parsed JSON datapack data (recipes, advancements, loot tables by default) on disk for instant reloads")
            .define("jsonReloadCacheEnabled", true);
    private static final ForgeConfigSpec.BooleanValue JSON_RELOAD_CACHE_ALL = BUILDER
            .comment("Cache every SimpleJsonResourceReloadListener directory instead of just the defaults")
            .define("jsonReloadCacheAllDirectories", false);
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> JSON_RELOAD_EXTRA = BUILDER
            .comment("Extra JSON reload directories to cache")
            .defineListAllowEmpty("jsonReloadExtraDirectories", List.of(), o -> o instanceof String);
    private static final ForgeConfigSpec.BooleanValue TAG_RELOAD_CACHE_ENABLED = BUILDER
            .comment("Cache parsed heavy tag directories (items/blocks/entity_types/fluids/biomes/...) on disk")
            .define("tagReloadCacheEnabled", true);
    private static final ForgeConfigSpec.BooleanValue TAG_RELOAD_CACHE_ALL = BUILDER
            .comment("Cache every tag directory instead of just the heavy defaults")
            .define("tagReloadCacheAllDirectories", false);
    private static final ForgeConfigSpec.BooleanValue REUSE_BUILT_TAGS = BUILDER
            .comment("Reuse already built tag collections for repeated datapack loads in the same session (world re-entry, /reload). Guarded by a fingerprint of the raw tag entries, so changed datapacks still rebuild")
            .define("reuseBuiltTags", true);
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> CONTENT_HASHED_MODS = BUILDER
            .comment("Mod ids whose jar content is hashed into cache fingerprints, so a same-version repacked jar still invalidates caches. Keep this list short: jars are read once per launch")
            .defineListAllowEmpty("contentHashedMods", List.of("gtceu"), o -> o instanceof String);
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> TAG_RELOAD_EXTRA = BUILDER
            .comment("Extra tag directories to cache")
            .defineListAllowEmpty("tagReloadExtraDirectories", List.of(), o -> o instanceof String);
    static {
        BUILDER.pop();
    }

    private static final ForgeConfigSpec.BooleanValue MODEL_BAKE_CACHE_ENABLED = BUILDER
            .push("modelCache")
            .comment("Cache baked models on disk and skip the bake loop on later launches. Off by default: applying and storing models spikes RAM. Automatically disabled when ModernFix dynamic resources is on and for custom/layered models")
            .define("modelBakeCacheEnabled", false);
    private static final ForgeConfigSpec.IntValue MODEL_BAKE_MAX_MODELS = BUILDER
            .comment("Skip the model bake cache when the model map is larger than this. Caps peak RAM; 0 = unlimited")
            .defineInRange("modelBakeMaxModels", 20000, 0, 2000000);
    static {
        BUILDER.pop();
    }

    private static final ForgeConfigSpec.DoubleValue AI_ACTIVATION_RADIUS = BUILDER
            .push("ai")
            .comment("Only run mob AI when a player is within this radius (blocks). Outside the radius AI is frozen")
            .defineInRange("aiActivationRadius", 10.0d, 1.0d, 128.0d);
    private static final ForgeConfigSpec.BooleanValue AI_OPTIMIZATION_ENABLED = BUILDER
            .comment("Enable player-distance mob AI gating")
            .define("aiOptimizationEnabled", true);
    private static final ForgeConfigSpec.BooleanValue AI_KEEP_NAMED_ACTIVE = BUILDER
            .comment("Keep AI active for named mobs even outside the radius")
            .define("aiKeepNamedActive", true);
    private static final ForgeConfigSpec.BooleanValue AI_CLEAR_TARGET_WHEN_FROZEN = BUILDER
            .comment("Clear non-player targets when AI is frozen to avoid stale pathing work")
            .define("aiClearTargetWhenFrozen", true);
    private static final ForgeConfigSpec.BooleanValue AI_RESPECT_FTB_FORCE_LOADED = BUILDER
            .comment("When FTB Chunks is present, keep mob AI active inside force-loaded chunks even if no player is nearby")
            .define("aiRespectFtbForceLoaded", true);
    static {
        BUILDER.pop();
    }

    private static final ForgeConfigSpec.BooleanValue ISOLATE_MODDED_RESOURCE_RELOAD_FAILURES = BUILDER
            .push("compatibility")
            .comment("Complete failed third-party client resource reload listeners instead of letting one mod crash the whole loading overlay")
            .define("isolateModdedResourceReloadFailures", true);
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ISOLATED_RESOURCE_RELOAD_LISTENER_PATTERNS = BUILDER
            .comment("Class-name prefixes that may be isolated when a resource reload fails. Use * for all non-core mod listeners")
            .defineList("isolatedResourceReloadListenerPatterns", List.of("*"), value -> value instanceof String string && !string.isBlank());
    private static final ForgeConfigSpec.BooleanValue CONNECTOR_COMPATIBILITY_MODE = BUILDER
            .comment("When Sinytra Connector is installed, avoid startup/resource optimizations that change Fabric resource reload or renderer lookup timing")
            .define("connectorCompatibilityMode", true);
    private static final ForgeConfigSpec.BooleanValue FUSION_COMPATIBILITY_MODE = BUILDER
            .comment("Fusion-aware compatibility. Packs that actually use a Fusion overrides folder automatically bypass the zip fast paths, and when a zip datapack is extracted the overrides folder is transferred to the extracted pack. Set to false only if you never use Fusion overrides folders")
            .define("fusionCompatibilityMode", true);
    static {
        BUILDER.pop();
    }

    private static final ForgeConfigSpec.BooleanValue GT_RECIPE_DATA_CACHE = BUILDER
            .push("integration")
            .comment("Cache GregTech's generated recipe data (the dynamic data pack JSON) on disk and skip regeneration on later launches. Only active when GregTech is loaded")
            .define("gtRecipeDataCache", true);
    static {
        BUILDER.pop();
    }

    private static final ForgeConfigSpec.BooleanValue DEBUG_RELOAD_TIMELINE = BUILDER
            .push("debug")
            .comment("Log every resource reload listener's completion time, the thread that ran it and live WarpLoad executor statistics. Used to diagnose reload-phase contention")
            .define("reloadTimeline", true);
    private static final ForgeConfigSpec.BooleanValue DEBUG_MOONLIGHT_PROBE = BUILDER
            .comment("Log Moonlight dynamic asset generation start/end plus its internal thread-pool statistics")
            .define("moonlightProbe", true);
    static {
        BUILDER.pop();
    }

    private static final ForgeConfigSpec.BooleanValue LDL_CTM_CACHE = BUILDER
            .push("client")
            .comment("Persist LowDragLib CTM metadata (LDLMetadataSection cache) across launches. Only active when ldlib is installed; resource-pack changes are fingerprinted")
            .define("ldlCtmCache", true);
    static {
        BUILDER.pop();
    }

    private static final ForgeConfigSpec.BooleanValue MEMORY_CACHE_ENABLED = BUILDER
            .push("memory")
            .comment("Keep soft in-memory copies of parsed JSON/tag data for faster reloads. Soft refs are dropped under memory pressure")
            .define("memoryCacheEnabled", true);
    private static final ForgeConfigSpec.IntValue MEMORY_CACHE_MAX_ENTRIES = BUILDER
            .comment("Max soft memory-cache entries across each cache type")
            .defineInRange("memoryCacheMaxEntries", 16, 1, 256);
    private static final ForgeConfigSpec.BooleanValue CLEAR_MEMORY_AFTER_RELOAD = BUILDER
            .comment("Drop WarpLoad heap caches after world load / resource reload so Minecraft keeps the only live copy")
            .define("clearMemoryAfterReload", true);
    static {
        BUILDER.pop();
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean enabled = true;
    public static boolean logCacheEvents = true;
    public static boolean asyncPreloadPacks = true;
    public static boolean dedicatedResourceReloadExecutor = true;
    public static int reloadWorkers = 0;
    public static boolean parallelResourceLookup = false;
    public static int parallelLookupMinPacks = 4;
    public static boolean cacheResourceExistence = true;
    public static boolean parallelJsonParsing = true;
    public static boolean cacheWalkedPaths = true;
    public static boolean cacheEmptyNamespaces = true;
    public static boolean cacheMaterials = true;
    public static boolean datapackExtractionEnabled = true;
    public static String datapackCacheFolder = "warpload_cache";
    public static boolean cacheExternalZips = true;
    public static boolean jsonReloadCacheEnabled = true;
    public static boolean jsonReloadCacheAllDirectories = false;
    public static Set<String> jsonReloadExtraDirectories = new HashSet<>();
    public static boolean tagReloadCacheEnabled = true;
    public static boolean tagReloadCacheAllDirectories = false;
    public static Set<String> tagReloadExtraDirectories = new HashSet<>();
    public static boolean reuseBuiltTags = true;
    public static Set<String> contentHashedMods = Set.of("gtceu");
    public static boolean modelBakeCacheEnabled = false;
    public static int modelBakeMaxModels = 20000;
    public static double aiActivationRadius = 10.0d;
    public static boolean aiOptimizationEnabled = true;
    public static boolean aiKeepNamedActive = true;
    public static boolean aiClearTargetWhenFrozen = true;
    public static boolean aiRespectFtbForceLoaded = true;
    public static boolean isolateModdedResourceReloadFailures = true;
    public static List<String> isolatedResourceReloadListenerPatterns = List.of("*");
    public static boolean connectorCompatibilityMode = true;
    public static boolean fusionCompatibilityMode = true;
    public static boolean gtRecipeDataCache = true;
    public static boolean debugReloadTimeline = true;
    public static boolean debugMoonlightProbe = true;
    public static boolean ldlCtmCache = true;
    public static boolean memoryCacheEnabled = true;
    public static int memoryCacheMaxEntries = 16;
    public static boolean clearMemoryAfterReload = true;

    private WarpLoadConfig() {
    }

    public static void apply() {
        try {
            enabled = ENABLED.get();
            logCacheEvents = LOG_CACHE_EVENTS.get();

            asyncPreloadPacks = ASYNC_PRELOAD_PACKS.get();
            dedicatedResourceReloadExecutor = DEDICATED_RESOURCE_RELOAD_EXECUTOR.get();
            reloadWorkers = RELOAD_WORKERS.get();
            parallelResourceLookup = PARALLEL_RESOURCE_LOOKUP.get();
            parallelLookupMinPacks = PARALLEL_LOOKUP_MIN_PACKS.get();
            cacheResourceExistence = CACHE_RESOURCE_EXISTENCE.get();
            parallelJsonParsing = PARALLEL_JSON_PARSING.get();
            cacheWalkedPaths = CACHE_WALKED_PATHS.get();
            cacheEmptyNamespaces = CACHE_EMPTY_NAMESPACES.get();
            cacheMaterials = CACHE_MATERIALS.get();

            datapackExtractionEnabled = DATAPACK_EXTRACTION_ENABLED.get();
            datapackCacheFolder = DATAPACK_CACHE_FOLDER.get();
            cacheExternalZips = CACHE_EXTERNAL_ZIPS.get();

            jsonReloadCacheEnabled = JSON_RELOAD_CACHE_ENABLED.get();
            jsonReloadCacheAllDirectories = JSON_RELOAD_CACHE_ALL.get();
            jsonReloadExtraDirectories = JSON_RELOAD_EXTRA.get().stream()
                    .map(String::valueOf)
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toCollection(HashSet::new));
            tagReloadCacheEnabled = TAG_RELOAD_CACHE_ENABLED.get();
            tagReloadCacheAllDirectories = TAG_RELOAD_CACHE_ALL.get();
            tagReloadExtraDirectories = TAG_RELOAD_EXTRA.get().stream()
                    .map(String::valueOf)
                    .map(value -> value.toLowerCase(Locale.ROOT).replace('\\', '/'))
                    .collect(Collectors.toCollection(HashSet::new));
            reuseBuiltTags = REUSE_BUILT_TAGS.get();
            contentHashedMods = CONTENT_HASHED_MODS.get().stream()
                    .map(String::valueOf)
                    .collect(Collectors.toCollection(HashSet::new));

            modelBakeCacheEnabled = MODEL_BAKE_CACHE_ENABLED.get();
            modelBakeMaxModels = MODEL_BAKE_MAX_MODELS.get();

            aiActivationRadius = AI_ACTIVATION_RADIUS.get();
            aiOptimizationEnabled = AI_OPTIMIZATION_ENABLED.get();
            aiKeepNamedActive = AI_KEEP_NAMED_ACTIVE.get();
            aiClearTargetWhenFrozen = AI_CLEAR_TARGET_WHEN_FROZEN.get();
            aiRespectFtbForceLoaded = AI_RESPECT_FTB_FORCE_LOADED.get();

            isolateModdedResourceReloadFailures = ISOLATE_MODDED_RESOURCE_RELOAD_FAILURES.get();
            isolatedResourceReloadListenerPatterns = ISOLATED_RESOURCE_RELOAD_LISTENER_PATTERNS.get().stream()
                    .map(String::valueOf)
                    .toList();
            connectorCompatibilityMode = CONNECTOR_COMPATIBILITY_MODE.get();
            fusionCompatibilityMode = FUSION_COMPATIBILITY_MODE.get();
            gtRecipeDataCache = GT_RECIPE_DATA_CACHE.get();
            debugReloadTimeline = DEBUG_RELOAD_TIMELINE.get();
            debugMoonlightProbe = DEBUG_MOONLIGHT_PROBE.get();

            ldlCtmCache = LDL_CTM_CACHE.get();
            memoryCacheEnabled = MEMORY_CACHE_ENABLED.get();
            memoryCacheMaxEntries = MEMORY_CACHE_MAX_ENTRIES.get();
            clearMemoryAfterReload = CLEAR_MEMORY_AFTER_RELOAD.get();
        } catch (IllegalStateException ignored) {
            isolatedResourceReloadListenerPatterns = List.of("*");
        }

        GlobalCache.isEnabled = enabled;
        GlobalCache.shouldCacheWalkedPaths = cacheWalkedPaths;
        GlobalCache.shouldCacheEmptyNamespaces = cacheEmptyNamespaces;
        GlobalCache.shouldCacheMaterials = cacheMaterials;
        GlobalCache.shouldAsyncPreloadPacks = asyncPreloadPacks;
        GlobalCache.shouldUseDedicatedResourceReloadExecutor = dedicatedResourceReloadExecutor;
        GlobalCache.setReloadWorkersOverride(reloadWorkers);
        GlobalCache.shouldParallelizeResourcePackLookup = parallelResourceLookup;
        GlobalCache.parallelLookupMinPacks = parallelLookupMinPacks;
        GlobalCache.shouldCacheResourceExistence = cacheResourceExistence;
        GlobalCache.shouldIsolateModdedResourceReloadFailures = isolateModdedResourceReloadFailures;
        GlobalCache.shouldUseConnectorCompatibilityMode = connectorCompatibilityMode;
        GlobalCache.isolatedResourceReloadListenerPatterns = isolatedResourceReloadListenerPatterns;
    }
}
