package com.warpload.mixin.renderer;

import com.warpload.cache.GlobalCache;
import com.warpload.compat.ResourceReloadFailureGuard;
import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.logging.LogUtils;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

@Mixin(EntityRenderers.class)
public abstract class EntityRenderersMixin {
    @Unique
    private static final Logger warpload$logger = LogUtils.getLogger();
    @Unique
    private static final Set<String> warpload$loggedFailures = ConcurrentHashMap.newKeySet();

    @WrapOperation(
            method = "createEntityRenderers",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V")
    )
    private static void warpload$isolateEntityRendererFailure(
            Map<EntityType<?>, EntityRendererProvider<?>> providers,
            BiConsumer<EntityType<?>, EntityRendererProvider<?>> action,
            Operation<Void> original,
            @Local(argsOnly = true) EntityRendererProvider.Context context,
            @Local ImmutableMap.Builder<EntityType<?>, EntityRenderer<?>> builder) {
        if (!GlobalCache.shouldIsolateModdedResourceReloadFailures) {
            original.call(providers, action);
            return;
        }

        BiConsumer<EntityType<?>, EntityRendererProvider<?>> guardedAction = (entityType, provider) -> {
            try {
                action.accept(entityType, provider);
            } catch (RuntimeException failure) {
                ResourceLocation rendererId = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
                String rendererNamespace = rendererId == null ? null : rendererId.getNamespace();
                if (!ResourceReloadFailureGuard.shouldIsolateRendererFailure(rendererNamespace, provider, failure)) {
                    throw failure;
                }
                try {
                    builder.put(entityType, new NoopRenderer<Entity>(context));
                } catch (RuntimeException fallbackFailure) {
                    failure.addSuppressed(fallbackFailure);
                    throw failure;
                }
                warpload$logFallback(rendererId == null ? entityType.toString() : rendererId.toString(), provider, failure);
            }
        };
        original.call(providers, guardedAction);
    }

    @WrapOperation(
            method = "createPlayerRenderers",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V")
    )
    private static void warpload$isolatePlayerRendererFailure(
            Map<String, EntityRendererProvider<AbstractClientPlayer>> providers,
            BiConsumer<String, EntityRendererProvider<AbstractClientPlayer>> action,
            Operation<Void> original,
            @Local(argsOnly = true) EntityRendererProvider.Context context,
            @Local ImmutableMap.Builder<String, EntityRenderer<? extends Player>> builder) {
        if (!GlobalCache.shouldIsolateModdedResourceReloadFailures) {
            original.call(providers, action);
            return;
        }

        BiConsumer<String, EntityRendererProvider<AbstractClientPlayer>> guardedAction = (model, provider) -> {
            try {
                action.accept(model, provider);
            } catch (RuntimeException failure) {
                if (!ResourceReloadFailureGuard.shouldIsolateRendererFailure("minecraft", provider, failure)) {
                    throw failure;
                }
                try {
                    builder.put(model, new NoopRenderer<AbstractClientPlayer>(context));
                } catch (RuntimeException fallbackFailure) {
                    failure.addSuppressed(fallbackFailure);
                    throw failure;
                }
                warpload$logFallback("minecraft:player/" + model, provider, failure);
            }
        };
        original.call(providers, guardedAction);
    }

    @Unique
    private static void warpload$logFallback(String rendererId, EntityRendererProvider<?> provider, RuntimeException failure) {
        if (warpload$loggedFailures.add(rendererId)) {
            warpload$logger.warn("WarpLoad replaced failed entity renderer {} from {} with the vanilla no-op renderer: {}",
                    rendererId, provider.getClass().getName(), failure.toString());
            warpload$logger.debug("Entity renderer fallback details for {}", rendererId, failure);
        }
    }
}
