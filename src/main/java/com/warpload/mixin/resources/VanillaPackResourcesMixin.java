package com.warpload.mixin.resources;

import com.warpload.cache.GlobalCache;
import com.warpload.interfaces.IPackResources;
import com.warpload.util.CacheUtil;
import com.google.common.collect.Maps;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.io.InputStream;
import java.util.Map;

import static com.warpload.cache.GlobalCache.PERSISTED_EXISTENCES_BY_MOD;

@Mixin(VanillaPackResources.class)
public abstract class VanillaPackResourcesMixin implements IPackResources {

    @Unique
    private volatile Map<String, Boolean> warpload$existencePerClientResource;

    @Unique
    private volatile Map<String, Boolean> warpload$existencePerServerResource;

    @Unique
    private String warpload$versionId;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(CallbackInfo ci) {
        if (!GlobalCache.isEnabled)
            return;

        GlobalCache.add(this);
        warpload$versionId = SharedConstants.getCurrentVersion().getId();

        warpload$existencePerClientResource = PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                warpload$versionId + "-client", k -> Maps.newConcurrentMap());
        warpload$existencePerServerResource = PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                warpload$versionId + "-server", k -> Maps.newConcurrentMap());
        GlobalCache.loadPersistedCacheAsync(CacheUtil.HAS_RESOURCE_CACHE_DIR, warpload$versionId + "-client", warpload$existencePerClientResource);
        GlobalCache.loadPersistedCacheAsync(CacheUtil.HAS_RESOURCE_CACHE_DIR, warpload$versionId + "-server", warpload$existencePerServerResource);
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    public void getResourceHeadInjected(PackType packType, ResourceLocation location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence)
            return;

        Boolean exists = warpload$exists(packType, location.toString());

        if (Boolean.FALSE.equals(exists)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getResource", at = @At("RETURN"))
    public void getResourceReturnInjected(PackType packType, ResourceLocation location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence)
            return;

        boolean actuallyExists = cir.getReturnValue() != null;
        warpload$cacheExists(packType, location.toString(), actuallyExists);
    }

    @Override
    public void warpload$persistAndClearCache() {
        if (warpload$versionId != null) {
            CacheUtil.persist(warpload$clientExistence(), new File(CacheUtil.HAS_RESOURCE_CACHE_DIR.getPath(), warpload$versionId + "-client.ser"));
            CacheUtil.persist(warpload$serverExistence(), new File(CacheUtil.HAS_RESOURCE_CACHE_DIR.getPath(), warpload$versionId + "-server.ser"));
        }
        warpload$clientExistence().clear();
        warpload$serverExistence().clear();
    }

    @Unique
    private Map<String, Boolean> warpload$clientExistence() {
        if (warpload$existencePerClientResource == null) {
            synchronized (this) {
                if (warpload$existencePerClientResource == null) {
                    warpload$existencePerClientResource = Maps.newConcurrentMap();
                }
            }
        }
        return warpload$existencePerClientResource;
    }

    @Unique
    private Map<String, Boolean> warpload$serverExistence() {
        if (warpload$existencePerServerResource == null) {
            synchronized (this) {
                if (warpload$existencePerServerResource == null) {
                    warpload$existencePerServerResource = Maps.newConcurrentMap();
                }
            }
        }
        return warpload$existencePerServerResource;
    }

    @Unique
    public Boolean warpload$exists(PackType packType, String resourceName) {
        if (packType == PackType.CLIENT_RESOURCES)
            return warpload$clientExistence().get(resourceName);
        return warpload$serverExistence().get(resourceName);
    }

    @Unique
    public void warpload$cacheExists(PackType packType, String resourceName, boolean exists) {
        if (packType == PackType.CLIENT_RESOURCES)
            warpload$clientExistence().put(resourceName, exists);
        else
            warpload$serverExistence().put(resourceName, exists);
    }

    @Override
    public Map<String, Boolean> warpload$getExistenceByResource() {
        return warpload$serverExistence();
    }

    @Override
    public void warpload$setExistenceByResource(Map<String, Boolean> existenceByResource) {
        warpload$existencePerServerResource = existenceByResource;
    }
}
