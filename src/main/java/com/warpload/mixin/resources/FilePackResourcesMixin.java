package com.warpload.mixin.resources;

import com.warpload.cache.GlobalCache;
import com.warpload.compat.FusionPackCompat;
import com.warpload.interfaces.IIndexedPack;
import com.warpload.interfaces.IPackResources;
import com.google.common.collect.Maps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Mixin(FilePackResources.class)
public abstract class FilePackResourcesMixin implements IPackResources, IIndexedPack {

    @Shadow
    @Nullable
    protected abstract ZipFile getOrCreateZipFile();

    @Shadow
    @Nullable
    private ZipFile zipFile;

    @Unique
    private volatile Map<PackType, List<String>> warpload$entriesByPackType;

    @Unique
    private volatile Map<PackType, Set<String>> warpload$entryKeysByPackType;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(String name, java.io.File file, boolean builtin, CallbackInfo ci) {
        if (GlobalCache.isEnabled)
            GlobalCache.add(this);
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    public void listResourcesHeadInjected(PackType packType, String namespace, String path, PackResources.ResourceOutput resourceOutput, CallbackInfo ci) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || FusionPackCompat.hasOverrides(this))
            return;

        try {
            ZipFile zip = warpload$getOpenZipFile();
            if (zip == null) {
                return;
            }

            String s = packType.getDirectory() + "/" + namespace + "/";
            String s1 = s + path + "/";

            List<String> entries = warpload$entries().get(packType);

            if (entries == null) {
                entries = zip.stream()
                        .filter(entry -> !entry.isDirectory())
                        .map(ZipEntry::getName)
                        .collect(Collectors.toList());
                warpload$entries().put(packType, entries);
            }

            entries.stream()
                    .filter(entry -> entry.startsWith(s1))
                    .forEach(entry -> {
                        String relative = entry.substring(s.length());
                        ResourceLocation location = ResourceLocation.tryBuild(namespace, relative);
                        if (location != null) {
                            resourceOutput.accept(location, warpload$openResource(packType, location));
                        }
                    });

            ci.cancel();
        } catch (Throwable ignored) {
            // Any unexpected state (e.g. packs cloned by other mods) falls back to vanilla enumeration
        }
    }

    @Override
    public Boolean warpload$hasIndexedResource(PackType type, ResourceLocation location) {
        if (FusionPackCompat.hasOverrides(this))
            return null;
        Set<String> keys = warpload$entryKeys(type);
        if (keys == null)
            return null;
        return keys.contains(location.getNamespace() + "/" + location.getPath());
    }

    @Override
    public void warpload$persistAndClearCache() {
        if (warpload$entriesByPackType != null) {
            warpload$entriesByPackType.clear();
        }
        if (warpload$entryKeysByPackType != null) {
            warpload$entryKeysByPackType.clear();
        }
    }

    @Unique
    private Map<PackType, List<String>> warpload$entries() {
        if (warpload$entriesByPackType == null) {
            synchronized (this) {
                if (warpload$entriesByPackType == null) {
                    warpload$entriesByPackType = Maps.newConcurrentMap();
                }
            }
        }
        return warpload$entriesByPackType;
    }

    @Unique
    private Map<PackType, Set<String>> warpload$entryKeysMap() {
        if (warpload$entryKeysByPackType == null) {
            synchronized (this) {
                if (warpload$entryKeysByPackType == null) {
                    warpload$entryKeysByPackType = Maps.newConcurrentMap();
                }
            }
        }
        return warpload$entryKeysByPackType;
    }

    @Unique
    private Set<String> warpload$entryKeys(PackType type) {
        Map<PackType, Set<String>> map = warpload$entryKeysMap();
        Set<String> existing = map.get(type);
        if (existing != null)
            return existing;
        ZipFile zip = warpload$getOpenZipFile();
        if (zip == null)
            return null;
        try {
            return map.computeIfAbsent(type, packType -> {
                String prefix = packType.getDirectory() + "/";
                return zip.stream()
                        .filter(entry -> !entry.isDirectory())
                        .map(ZipEntry::getName)
                        .filter(name -> name.startsWith(prefix))
                        .map(name -> name.substring(prefix.length()))
                        .collect(Collectors.toUnmodifiableSet());
            });
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private ZipFile warpload$getOpenZipFile() {
        ZipFile zip = this.getOrCreateZipFile();
        if (zip == null || warpload$isOpen(zip)) {
            return zip;
        }

        this.zipFile = null;
        zip = this.getOrCreateZipFile();
        return zip != null && warpload$isOpen(zip) ? zip : null;
    }

    @Unique
    private static boolean warpload$isOpen(ZipFile zip) {
        try {
            zip.size();
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    @Unique
    private IoSupplier<InputStream> warpload$openResource(PackType packType, ResourceLocation location) {
        return () -> {
            ZipFile zip = warpload$getOpenZipFile();
            if (zip == null) {
                throw new FileNotFoundException(location.toString());
            }
            String entryName = packType.getDirectory() + "/" + location.getNamespace() + "/" + location.getPath();
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new FileNotFoundException(location.toString());
            }
            return zip.getInputStream(entry);
        };
    }
}
