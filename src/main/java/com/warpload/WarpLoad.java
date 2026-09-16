package com.warpload;

import com.mojang.logging.LogUtils;
import com.warpload.cache.CacheMemory;
import com.warpload.cache.DatapackCache;
import com.warpload.cache.GlobalCache;
import com.warpload.config.WarpLoadConfig;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ModConstants.MOD_ID)
public class WarpLoad {
    public static final Logger LOGGER = LogUtils.getLogger();
    private static boolean loggedConnectorCompatibilityMode;

    public WarpLoad() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onConfigEvent);
        modEventBus.addListener(this::commonSetup);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, WarpLoadConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
        WarpLoadConfig.apply();
    }

    private void onConfigEvent(ModConfigEvent event) {
        if (event.getConfig().getSpec() == WarpLoadConfig.SPEC) {
            WarpLoadConfig.apply();
            applyCompatibilityFlags();
        }
    }

    private void applyCompatibilityFlags() {
        if (GlobalCache.shouldUseConnectorCompatibilityMode && isLoaded(ModConstants.CONNECTOR_ID)) {
            GlobalCache.shouldAsyncPreloadPacks = false;
            GlobalCache.shouldUseDedicatedResourceReloadExecutor = false;
            GlobalCache.shouldParallelizeResourcePackLookup = false;
            GlobalCache.shouldCacheWalkedPaths = false;
            GlobalCache.shouldCacheEmptyNamespaces = false;
            GlobalCache.shouldCacheResourceExistence = false;
            GlobalCache.shouldIsolateModdedResourceReloadFailures = false;
            if (!loggedConnectorCompatibilityMode) {
                LOGGER.warn("WarpLoad Sinytra Connector compatibility mode is active; resource-pack parallelism, path caches and reload failure isolation are disabled");
                loggedConnectorCompatibilityMode = true;
            }
        }
        if (isLoaded(ModConstants.SOPHISTICATED_STORAGE_ID) && isLoaded(ModConstants.JSON_THINGS_ID)) {
            GlobalCache.shouldCacheWalkedPaths = false;
        }
        if (isLoaded(ModConstants.MULTIBLOCKED_ID)) {
            GlobalCache.shouldCacheMaterials = false;
        }
    }

    private static boolean isLoaded(String modId) {
        try {
            return ModList.get().isLoaded(modId);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("WarpLoad ready: path indexes, parallel reloads, disk caches, datapack extraction and AI gating armed");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        CacheMemory.afterHeavyLoad();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            com.warpload.client.ClientModelCacheHooks.cancelStore();
        }
        DatapackCache.deactivate();
        CacheMemory.clearAll();
    }
}
