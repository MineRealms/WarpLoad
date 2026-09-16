package com.warpload.compat;

import com.warpload.ModConstants;
import com.warpload.WarpLoad;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

public final class FTBChunksCompat {
    private static Boolean present;
    private static boolean loggedPresent;

    private FTBChunksCompat() {
    }

    public static boolean isLoaded() {
        if (present == null) {
            present = ModList.get().isLoaded(ModConstants.FTBCHUNKS_ID);
        }
        if (present && !loggedPresent) {
            loggedPresent = true;
            WarpLoad.LOGGER.info("FTB Chunks detected - force-loaded chunks will keep mob AI active");
        }
        return present;
    }

    public static boolean isForceLoaded(Level level, ChunkPos pos) {
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (serverLevel.getForcedChunks().contains(pos.toLong())) {
            return true;
        }
        if (isLoaded()) {
            try {
                return FTBChunksCompatInternal.isForceLoaded(level, pos);
            } catch (Exception | NoClassDefFoundError | NoSuchMethodError e) {
                WarpLoad.LOGGER.warn("FTB Chunks force-load check failed (API mismatch?): {}", e.toString());
                return false;
            }
        }
        return false;
    }

    public static boolean isForceLoaded(Level level, double x, double z) {
        int chunkX = ((int) Math.floor(x)) >> 4;
        int chunkZ = ((int) Math.floor(z)) >> 4;
        return isForceLoaded(level, new ChunkPos(chunkX, chunkZ));
    }
}
