package com.warpload.events;

import com.warpload.ModConstants;
import com.warpload.cache.CacheMemory;
import com.warpload.cache.GlobalCache;
import com.warpload.config.WarpLoadConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.internal.BrandingControl;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = ModConstants.MOD_ID, value = Dist.CLIENT)
public class TitleScreenInjector {

    private static boolean launchComplete = false;

    @SuppressWarnings({"unchecked"})
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenInit(ScreenEvent.Init event) {
        if (!(event.getScreen() instanceof TitleScreen) || launchComplete) {
            return;
        }
        launchComplete = true;
        long secondsToStart = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        LogUtils.getLogger().info("WarpLoad: Launch took {}s", secondsToStart);
        try {
            BrandingControl brandingControl = new BrandingControl();
            Field brandingsField = BrandingControl.class.getDeclaredField("brandings");
            brandingsField.setAccessible(true);
            Method computeBranding = BrandingControl.class.getDeclaredMethod("computeBranding");
            computeBranding.setAccessible(true);
            computeBranding.invoke(null);
            List<String> brandings = new ArrayList<>((List<String>) brandingsField.get(brandingControl));
            if (brandings.size() > 1) {
                List<String> newBrandings = new ArrayList<>(brandings);
                brandingsField.set(brandingControl, newBrandings);
                newBrandings.add("WarpLoad: Launch took " + secondsToStart + "s");
            }
        } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException |
                 java.lang.reflect.InvocationTargetException e) {
            LogUtils.getLogger().error("WarpLoad cannot add launch time to title screen", e);
        }
        GlobalCache.EXECUTOR.execute(() -> {
            try {
                GlobalCache.persistAndTrimCaches();
            } catch (Throwable throwable) {
                LogUtils.getLogger().error("WarpLoad failed to persist caches", throwable);
            }
        });
        CacheMemory.afterHeavyLoad();
        if (WarpLoadConfig.logCacheEvents) {
            LogUtils.getLogger().info("WarpLoad: startup caches persisted and heap caches trimmed");
        }
    }
}
