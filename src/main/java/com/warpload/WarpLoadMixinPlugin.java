package com.warpload;

import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WarpLoadMixinPlugin implements IMixinConfigPlugin {
    private static final String GT_MIXIN_PACKAGE = "com.warpload.mixin.gt.";
    private static final Map<String, String> MOD_REQUIRED_MIXINS = Map.of(
            "MoonlightGenProbeMixin", "moonlight",
            "LdlCtmPreloadMixin", "ldlib"
    );
    private static final Map<String, Boolean> MOD_LOADED_CACHE = new ConcurrentHashMap<>();

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(GT_MIXIN_PACKAGE)) {
            return isModLoaded("gtceu");
        }
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        String requiredMod = MOD_REQUIRED_MIXINS.get(simpleName);
        if (requiredMod != null) {
            return isModLoaded(requiredMod);
        }
        return true;
    }

    private static boolean isModLoaded(String modId) {
        return MOD_LOADED_CACHE.computeIfAbsent(modId, key -> {
            try {
                LoadingModList list = FMLLoader.getLoadingModList();
                return list != null && list.getModFileById(key) != null;
            } catch (Throwable ignored) {
                return false;
            }
        });
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
