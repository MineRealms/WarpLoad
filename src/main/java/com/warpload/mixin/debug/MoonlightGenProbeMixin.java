package com.warpload.mixin.debug;

import com.warpload.WarpLoad;
import com.warpload.cache.GlobalCache;
import com.warpload.config.WarpLoadConfig;
import com.warpload.debug.MoonlightProbe;
import com.warpload.debug.ReloadSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.mehvahdjukaar.moonlight.api.resources.pack.DynResourceGenerator", remap = false)
public class MoonlightGenProbeMixin {

    @Unique
    private long warpload$generationStartNanos;

    @Inject(method = "regenerateDynamicAssets(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/mehvahdjukaar/moonlight/api/misc/IProgressTracker;)V",
            at = @At("HEAD"), require = 0, remap = false)
    private void warpload$generationStart(CallbackInfo ci) {
        warpload$generationStartNanos = System.nanoTime();
        ReloadSampler.generationStarted();
        if (WarpLoadConfig.debugMoonlightProbe) {
            WarpLoad.LOGGER.info("[WarpLoad-DBG] moonlight generation START thread={} | {}",
                    Thread.currentThread().getName(), GlobalCache.poolStats());
        }
    }

    @Inject(method = "regenerateDynamicAssets(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/mehvahdjukaar/moonlight/api/misc/IProgressTracker;)V",
            at = @At("RETURN"), require = 0, remap = false)
    private void warpload$generationEnd(CallbackInfo ci) {
        if (WarpLoadConfig.debugMoonlightProbe) {
            long millis = (System.nanoTime() - warpload$generationStartNanos) / 1_000_000L;
            WarpLoad.LOGGER.info("[WarpLoad-DBG] moonlight generation END {} ms | theirPool={} | {}",
                    millis, MoonlightProbe.poolStats(), GlobalCache.poolStats());
        }
        ReloadSampler.generationFinished();
    }
}
