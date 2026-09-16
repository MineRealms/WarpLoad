package com.warpload.mixin.client;

import com.warpload.compat.LdlCtmCache;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(Minecraft.class)
public abstract class LdlCtmPreloadMixin {

    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
    private void warpload$preloadCtmCache(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        LdlCtmCache.preloadOnRenderThread();
    }
}
