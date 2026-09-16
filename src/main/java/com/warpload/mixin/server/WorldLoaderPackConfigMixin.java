package com.warpload.mixin.server;

import com.mojang.datafixers.util.Pair;
import com.warpload.cache.DatapackCache;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.world.level.WorldDataConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldLoader.PackConfig.class)
public abstract class WorldLoaderPackConfigMixin {
    @Inject(method = "createResourceManager", at = @At("HEAD"))
    private void warpload$enter(CallbackInfoReturnable<Pair<WorldDataConfiguration, CloseableResourceManager>> cir) {
        DatapackCache.enterServerData();
    }

    @Inject(method = "createResourceManager", at = @At("RETURN"))
    private void warpload$exit(CallbackInfoReturnable<Pair<WorldDataConfiguration, CloseableResourceManager>> cir) {
        DatapackCache.exitServerData();
    }
}
