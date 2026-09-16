package com.warpload.mixin.model;

import com.warpload.cache.ModelBakeCache;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.BiFunction;

@Mixin(ModelBakery.class)
public abstract class ModelBakeryMixin {

    @Shadow
    @Final
    private Map<ResourceLocation, BakedModel> bakedTopLevelModels;

    @Shadow
    @Final
    private Map<ResourceLocation, UnbakedModel> topLevelModels;

    @Inject(method = "bakeModels", at = @At("HEAD"), cancellable = true)
    private void warpload$skipBakeIfCached(BiFunction<ResourceLocation, Material, TextureAtlasSprite> spriteGetter, CallbackInfo ci) {
        if (ModelBakeCache.trySkipBake(this.bakedTopLevelModels, this.topLevelModels.size(), spriteGetter)) {
            ci.cancel();
        }
    }
}
