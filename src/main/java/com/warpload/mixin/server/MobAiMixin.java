package com.warpload.mixin.server;

import com.warpload.entity.EntityAiOptimizer;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobAiMixin {
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void warpload$gateAi(CallbackInfo ci) {
        Mob mob = (Mob) (Object) this;
        if (EntityAiOptimizer.shouldRunAi(mob)) {
            return;
        }
        EntityAiOptimizer.freezeAi(mob);
        ci.cancel();
    }
}
