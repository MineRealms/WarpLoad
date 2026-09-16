package com.warpload.mixin.server;

import com.warpload.cache.GlobalCache;
import com.warpload.cache.TagReloadCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagLoader;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mixin(TagLoader.class)
public abstract class TagLoaderMixin {

    @Shadow
    @Final
    private String directory;

    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private void warpload$loadCached(ResourceManager resourceManager, CallbackInfoReturnable<Map<ResourceLocation, List<TagLoader.EntryWithSource>>> cir) {
        if (!GlobalCache.isEnabled) {
            return;
        }
        Map<ResourceLocation, List<TagLoader.EntryWithSource>> cached = TagReloadCache.tryLoad(this.directory);
        if (cached != null) {
            TagReloadCache.markLoadedFromCache();
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void warpload$storeCache(ResourceManager resourceManager, CallbackInfoReturnable<Map<ResourceLocation, List<TagLoader.EntryWithSource>>> cir) {
        if (!GlobalCache.isEnabled) {
            return;
        }
        Map<ResourceLocation, List<TagLoader.EntryWithSource>> value = cir.getReturnValue();
        if (value != null && !TagReloadCache.consumeLoadedFromCache()) {
            TagReloadCache.trySave(this.directory, value);
        }
    }

    @Inject(method = "build", at = @At("HEAD"), cancellable = true)
    private void warpload$reuseBuiltTags(Map<ResourceLocation, List<TagLoader.EntryWithSource>> map,
                                         CallbackInfoReturnable<Map<ResourceLocation, Collection<?>>> cir) {
        if (!GlobalCache.isEnabled) {
            return;
        }
        Map<ResourceLocation, Collection<?>> reused = TagReloadCache.tryReuseBuilt(this.directory, map);
        if (reused != null) {
            cir.setReturnValue(reused);
        }
    }

    @Inject(method = "build", at = @At("RETURN"))
    private void warpload$storeBuiltTags(Map<ResourceLocation, List<TagLoader.EntryWithSource>> map,
                                         CallbackInfoReturnable<Map<ResourceLocation, Collection<?>>> cir) {
        if (!GlobalCache.isEnabled) {
            return;
        }
        Map<ResourceLocation, Collection<?>> value = cir.getReturnValue();
        if (value != null) {
            TagReloadCache.storeBuilt(this.directory, map, value);
        }
    }
}
