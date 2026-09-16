package com.warpload.mixin.resources;

import com.warpload.cache.GlobalCache;
import com.warpload.interfaces.IPackResources;
import com.google.common.collect.Maps;
import net.minecraft.server.packs.AbstractPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(AbstractPackResources.class)
public abstract class AbstractPackResourcesMixin implements IPackResources {

    @Unique
    private volatile Map<String, Boolean> warpload$existenceByResource;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(String name, boolean isBuiltin, CallbackInfo ci) {
        if (GlobalCache.isEnabled)
            GlobalCache.add(this);
    }

    @Override
    public void warpload$persistAndClearCache() {
        if (warpload$existenceByResource != null) {
            warpload$existenceByResource.clear();
        }
    }

    @Override
    public void warpload$setExistenceByResource(Map<String, Boolean> existenceByResource) {
        this.warpload$existenceByResource = existenceByResource;
    }

    @Override
    public Map<String, Boolean> warpload$getExistenceByResource() {
        if (warpload$existenceByResource == null) {
            synchronized (this) {
                if (warpload$existenceByResource == null) {
                    warpload$existenceByResource = Maps.newConcurrentMap();
                }
            }
        }
        return warpload$existenceByResource;
    }
}
