package com.warpload.mixin.misc;

import com.warpload.cache.GlobalCache;
import com.warpload.interfaces.ICache;
import com.google.common.collect.MapMaker;
import net.minecraft.client.renderer.block.model.MultiVariant;
import net.minecraft.client.renderer.block.model.multipart.Condition;
import net.minecraft.client.renderer.block.model.multipart.Selector;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Predicate;

@Mixin(Selector.class)
public class SelectorMixin implements ICache {

    @Unique
    private volatile Map<StateDefinition<Block, BlockState>, Predicate<BlockState>> warpload$predicatePerDefinition;

    @SuppressWarnings("unchecked")
    @Unique
    private Map<StateDefinition<Block, BlockState>, Predicate<BlockState>> warpload$predicates() {
        if (warpload$predicatePerDefinition == null) {
            synchronized (this) {
                if (warpload$predicatePerDefinition == null) {
                    warpload$predicatePerDefinition = (Map<StateDefinition<Block, BlockState>, Predicate<BlockState>>) (Map<?, ?>) new MapMaker().weakKeys().makeMap();
                }
            }
        }
        return warpload$predicatePerDefinition;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(Condition condition, MultiVariant variant, CallbackInfo ci) {
        if (GlobalCache.isEnabled)
            GlobalCache.add(this);
    }

    @Inject(method = "getPredicate", at = @At("HEAD"), cancellable = true)
    public void getPredicateHeadInjected(StateDefinition<Block, BlockState> definition, CallbackInfoReturnable<Predicate<BlockState>> cir) {
        if (!GlobalCache.isEnabled)
            return;
        Predicate<BlockState> predicate = warpload$predicates().get(definition);
        if (predicate != null)
            cir.setReturnValue(predicate);
    }

    @Inject(method = "getPredicate", at = @At("RETURN"))
    public void getPredicateReturnInjected(StateDefinition<Block, BlockState> definition, CallbackInfoReturnable<Predicate<BlockState>> cir) {
        if (GlobalCache.isEnabled && cir.getReturnValue() != null)
            warpload$predicates().put(definition, cir.getReturnValue());
    }

    @Override
    public void warpload$persistAndClearCache() {
        if (warpload$predicatePerDefinition != null) {
            warpload$predicatePerDefinition.clear();
        }
    }
}
