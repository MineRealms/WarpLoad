package com.warpload.entity;

import com.warpload.compat.FTBChunksCompat;
import com.warpload.config.WarpLoadConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;

public final class EntityAiOptimizer {
    private EntityAiOptimizer() {
    }

    public static boolean shouldRunAi(Mob mob) {
        if (!WarpLoadConfig.aiOptimizationEnabled) {
            return true;
        }
        Level level = mob.level();
        if (level.isClientSide || !(level instanceof ServerLevel)) {
            return true;
        }
        if (mob.isNoAi() || mob.isRemoved()) {
            return false;
        }
        if (isExempt(mob)) {
            return true;
        }
        if (WarpLoadConfig.aiRespectFtbForceLoaded && FTBChunksCompat.isForceLoaded(level, mob.getX(), mob.getZ())) {
            return true;
        }
        double radius = WarpLoadConfig.aiActivationRadius;
        return radius <= 0.0d || level.getNearestPlayer(mob, radius) != null;
    }

    private static boolean isExempt(Mob mob) {
        if (mob.isPassenger()) {
            for (Entity passenger : mob.getPassengers()) {
                if (passenger instanceof Player) {
                    return true;
                }
            }
        }
        if ((mob.getVehicle() instanceof Player) || mob.isLeashed()) {
            return true;
        }
        return (WarpLoadConfig.aiKeepNamedActive && mob.hasCustomName())
                || (mob.getTarget() instanceof Player)
                || (mob instanceof EnderDragon)
                || (mob instanceof WitherBoss)
                || (mob instanceof Warden)
                || (mob instanceof ElderGuardian)
                || (mob instanceof Raider);
    }

    public static void freezeAi(Mob mob) {
        if (mob.getNavigation() != null) {
            mob.getNavigation().stop();
        }
        if (WarpLoadConfig.aiClearTargetWhenFrozen && !(mob.getTarget() instanceof Player)) {
            mob.setTarget((LivingEntity) null);
        }
    }
}
