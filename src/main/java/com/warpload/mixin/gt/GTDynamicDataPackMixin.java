package com.warpload.mixin.gt;

import com.gregtechceu.gtceu.data.pack.GTDynamicDataPack;
import com.warpload.integration.gt.GTRecipeDataCache;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GTDynamicDataPack.class, remap = false)
public abstract class GTDynamicDataPackMixin {

    @Inject(method = "addToData", at = @At("HEAD"), remap = false)
    private static void warpload$capture(ResourceLocation location, byte[] bytes, CallbackInfo ci) {
        GTRecipeDataCache.capture(location, bytes);
    }

    @Invoker("addToData")
    public static void warpload$addToData(ResourceLocation location, byte[] bytes) {
        throw new AssertionError();
    }
}
