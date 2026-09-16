package com.warpload.mixin.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.warpload.cache.GlobalCache;
import com.warpload.cache.JsonReloadCache;
import com.warpload.cache.ParallelJsonParser;
import com.warpload.config.WarpLoadConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(SimpleJsonResourceReloadListener.class)
public abstract class SimpleJsonResourceReloadListenerMixin {

    @Shadow
    @Final
    private String directory;

    @Shadow
    @Final
    private Gson gson;

    @Inject(method = "prepare", at = @At("HEAD"), cancellable = true)
    private void warpload$cacheOrParallelPrepare(ResourceManager resourceManager, ProfilerFiller profiler, CallbackInfoReturnable<Map<ResourceLocation, JsonElement>> cir) {
        if (!GlobalCache.isEnabled) {
            return;
        }
        Map<String, byte[]> raw = JsonReloadCache.tryLoadRaw(this.directory);
        if (raw != null) {
            cir.setReturnValue(ParallelJsonParser.parseRaw(this.gson, raw));
            return;
        }
        if (WarpLoadConfig.parallelJsonParsing) {
            cir.setReturnValue(ParallelJsonParser.parseDirectory(this.directory, this.gson, resourceManager, profiler));
        }
    }
}
