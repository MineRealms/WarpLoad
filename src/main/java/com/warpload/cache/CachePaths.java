package com.warpload.cache;

import com.warpload.config.WarpLoadConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class CachePaths {
    private CachePaths() {
    }

    public static String worldFolderName() {
        return (WarpLoadConfig.datapackCacheFolder == null || WarpLoadConfig.datapackCacheFolder.isBlank())
                ? "warpload_cache" : WarpLoadConfig.datapackCacheFolder;
    }

    public static Path gameCacheRoot() {
        return FMLPaths.GAMEDIR.get().resolve("warpload-cache").resolve("v1").toAbsolutePath().normalize();
    }

    public static Path activeCacheRoot() {
        Path world = DatapackCache.getCacheRoot();
        if (world != null) {
            return world;
        }
        return gameCacheRoot();
    }

    public static Path reloadCacheRoot() {
        return activeCacheRoot().resolve("reload");
    }
}
