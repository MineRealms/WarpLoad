package com.warpload.compat;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

final class FTBChunksCompatInternal {
    private FTBChunksCompatInternal() {
    }

    static boolean isForceLoaded(Level level, ChunkPos pos) throws Exception {
        Class<?> apiClass = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI");
        Object api = apiClass.getMethod("api").invoke(null);
        if (!(Boolean) apiClass.getMethod("isManagerLoaded").invoke(api)) {
            return false;
        }
        Class<?> chunkDimPosClass = Class.forName("dev.ftb.mods.ftblibrary.math.ChunkDimPos");
        Constructor<?> constructor = chunkDimPosClass.getConstructor(net.minecraft.resources.ResourceKey.class, ChunkPos.class);
        Object dimPos = constructor.newInstance(level.dimension(), pos);
        if ((Boolean) apiClass.getMethod("isChunkForceLoaded", chunkDimPosClass).invoke(api, dimPos)) {
            return true;
        }
        Object manager = apiClass.getMethod("getManager").invoke(api);
        if (manager == null) {
            return false;
        }
        Method getChunk = manager.getClass().getMethod("getChunk", chunkDimPosClass);
        Object claimed = getChunk.invoke(manager, dimPos);
        if (claimed == null) {
            return false;
        }
        return (Boolean) claimed.getClass().getMethod("isActuallyForceLoaded").invoke(claimed);
    }
}
