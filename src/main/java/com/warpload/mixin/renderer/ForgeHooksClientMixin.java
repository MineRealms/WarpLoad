package com.warpload.mixin.renderer;

import com.warpload.cache.CacheMemory;
import com.warpload.cache.GlobalCache;
import com.warpload.cache.ModelBakeCache;
import com.warpload.compat.ResourceReloadFailureGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.ForgeHooksClient;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

@Mixin(value = ForgeHooksClient.class, remap = false)
public abstract class ForgeHooksClientMixin {
    @Unique
    private static final Logger warpload$logger = LogUtils.getLogger();
    @Unique
    private static final Set<String> warpload$loggedFailures = ConcurrentHashMap.newKeySet();

    @WrapOperation(
            method = "loadLayerDefinitions",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V", remap = false),
            remap = false
    )
    private static void warpload$isolateLayerDefinitionFailure(
            Map<ModelLayerLocation, Supplier<LayerDefinition>> layerDefinitions,
            BiConsumer<ModelLayerLocation, Supplier<LayerDefinition>> action,
            Operation<Void> original) {
        if (!GlobalCache.shouldIsolateModdedResourceReloadFailures) {
            original.call(layerDefinitions, action);
            return;
        }

        BiConsumer<ModelLayerLocation, Supplier<LayerDefinition>> guardedAction = (location, supplier) -> {
            try {
                action.accept(location, supplier);
            } catch (RuntimeException failure) {
                if (!ResourceReloadFailureGuard.shouldIsolateRendererFailure(location.getModel().getNamespace(), supplier, failure)) {
                    throw failure;
                }
                warpload$logSkippedLayer(location, supplier, failure);
            }
        };
        original.call(layerDefinitions, guardedAction);
    }

    @Inject(method = "onModifyBakingResult", at = @At("RETURN"), remap = false)
    private static void warpload$storeCachedModels(Map<ResourceLocation, BakedModel> models, ModelBakery modelBakery, CallbackInfo ci) {
        ModelBakeCache.tryStore(models);
        CacheMemory.afterHeavyLoad();
    }

    @Unique
    private static void warpload$logSkippedLayer(ModelLayerLocation location, Supplier<LayerDefinition> supplier, RuntimeException failure) {
        if (warpload$loggedFailures.add(location.toString())) {
            warpload$logger.warn("WarpLoad skipped failed model layer {} from {}: {}",
                    location, supplier.getClass().getName(), failure.toString());
            warpload$logger.debug("Model layer failure details for {}", location, failure);
        }
    }
}
