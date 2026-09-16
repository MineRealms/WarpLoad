package com.warpload.mixin.gt;

import com.gregtechceu.gtceu.common.data.GTRecipes;
import com.warpload.integration.gt.GTRecipeDataCache;
import net.minecraft.data.recipes.FinishedRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(value = GTRecipes.class, remap = false)
public abstract class GTRecipesMixin {

    @Inject(method = "recipeAddition", at = @At("HEAD"), cancellable = true, remap = false)
    private static void warpload$tryApplyCache(Consumer<FinishedRecipe> originalConsumer, CallbackInfo ci) {
        if (GTRecipeDataCache.tryApply(originalConsumer)) {
            ci.cancel();
        } else {
            GTRecipeDataCache.beginCapture();
        }
    }

    @Inject(method = "recipeAddition", at = @At("RETURN"), remap = false)
    private static void warpload$finishCapture(Consumer<FinishedRecipe> originalConsumer, CallbackInfo ci) {
        GTRecipeDataCache.finishCapture();
    }
}
