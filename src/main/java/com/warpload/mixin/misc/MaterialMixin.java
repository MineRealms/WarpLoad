package com.warpload.mixin.misc;

import com.warpload.cache.GlobalCache;
import net.minecraft.client.resources.model.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Material.class)
public abstract class MaterialMixin {

    @Unique
    private Integer warpload$hash;

    @Inject(method = "hashCode", at = @At("HEAD"), cancellable = true)
    public void hashCodeHeadInjected(CallbackInfoReturnable<Integer> cir) {
        if (GlobalCache.isEnabled && warpload$hash != null)
            cir.setReturnValue(warpload$hash);
    }

    @Inject(method = "hashCode", at = @At("RETURN"))
    public void hashCodeReturnInjected(CallbackInfoReturnable<Integer> cir) {
        if (GlobalCache.isEnabled && warpload$hash == null)
            warpload$hash = cir.getReturnValue();
    }
}
