package com.warpload.mixin.model;

import com.warpload.cache.GlobalCache;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

@Mixin(BlockModel.class)
public abstract class BlockModelMixin {
    @Unique
    private Map<String, Material> warpload$materialCache;

    @Inject(method = "getMaterial", at = @At("HEAD"), cancellable = true)
    public void getMaterialHeadInjected(String name, CallbackInfoReturnable<Material> cir) {
        if (GlobalCache.isEnabled && GlobalCache.shouldCacheMaterials && this.warpload$materialCache != null) {
            Material cached = this.warpload$materialCache.get(name);
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        }
    }

    @Inject(method = "getMaterial", at = @At("RETURN"))
    public void getMaterialReturnInjected(String name, CallbackInfoReturnable<Material> cir) {
        if (GlobalCache.isEnabled && GlobalCache.shouldCacheMaterials && cir.getReturnValue() != null) {
            if (this.warpload$materialCache == null) {
                this.warpload$materialCache = new HashMap<>();
            }
            this.warpload$materialCache.put(name, cir.getReturnValue());
        }
    }
}
