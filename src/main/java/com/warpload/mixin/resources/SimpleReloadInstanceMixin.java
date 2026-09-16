package com.warpload.mixin.resources;

import com.warpload.compat.ResourceReloadFailureGuard;
import com.warpload.config.WarpLoadConfig;
import com.warpload.debug.ReloadSampler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(SimpleReloadInstance.class)
public abstract class SimpleReloadInstanceMixin {
    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture<?> warpload$guardReloadInvocation(
            @Coerce Object stateFactory,
            PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resourceManager,
            PreparableReloadListener listener,
            Executor backgroundExecutor,
            Executor gameExecutor,
            Operation<CompletableFuture<?>> original) {
        CompletableFuture<?> future = ResourceReloadFailureGuard.guard(listener,
                () -> original.call(stateFactory, barrier, resourceManager, listener, backgroundExecutor, gameExecutor));
        if (WarpLoadConfig.debugReloadTimeline) {
            ReloadSampler.listenerStarted();
            future.whenComplete((result, throwable) -> ReloadSampler.listenerFinished());
        }
        return future;
    }
}
