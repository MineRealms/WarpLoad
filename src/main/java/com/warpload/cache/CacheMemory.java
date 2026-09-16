package com.warpload.cache;

import com.warpload.WarpLoad;
import com.warpload.config.WarpLoadConfig;

public final class CacheMemory {
    private CacheMemory() {
    }

    public static void clearAll() {
        JsonReloadCache.clearMemory();
        TagReloadCache.clearMemory();
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            com.warpload.client.ClientModelCacheHooks.clear();
        }
        DatapackCache.clearLocks();
        CacheIO.clearLocks();
        if (WarpLoadConfig.logCacheEvents) {
            WarpLoad.LOGGER.info("Cleared WarpLoad in-memory caches to free RAM");
        }
    }

    public static void afterHeavyLoad() {
        if (WarpLoadConfig.clearMemoryAfterReload) {
            clearAll();
        }
    }
}
