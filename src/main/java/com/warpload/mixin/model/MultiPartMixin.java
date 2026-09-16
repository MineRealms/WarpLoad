package com.warpload.mixin.model;

import com.warpload.cache.GlobalCache;
import net.minecraft.client.renderer.block.model.multipart.MultiPart;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

@Mixin(MultiPart.class)
public abstract class MultiPartMixin implements UnbakedModel {
    @Unique
    private Collection<ResourceLocation> warpload$dependencies;

    @Inject(method = "getDependencies", at = @At("HEAD"), cancellable = true)
    public void getDependenciesHeadInjected(CallbackInfoReturnable<Collection<ResourceLocation>> cir) {
        if (GlobalCache.isEnabled && warpload$dependencies != null)
            cir.setReturnValue(warpload$dependencies);
    }

    @Inject(method = "getDependencies", at = @At("RETURN"))
    public void getDependenciesReturnInjected(CallbackInfoReturnable<Collection<ResourceLocation>> cir) {
        if (GlobalCache.isEnabled && warpload$dependencies == null)
            warpload$dependencies = cir.getReturnValue();
    }
}
