package com.warpload.mixin.resources;

import com.warpload.cache.GlobalCache;
import com.warpload.compat.FusionPackCompat;
import com.warpload.interfaces.IIndexedPack;
import com.google.common.collect.Maps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mixin(net.minecraft.server.packs.PathPackResources.class)
public abstract class VanillaPathResourcePackMixin implements IIndexedPack {

    @Shadow @Final private Path root;

    @Unique
    private volatile Map<PackType, Set<String>> warpload$pathIndexByType;

    @Unique
    private volatile Map<PackType, Set<String>> warpload$namespaceCache;

    @Inject(method = "getNamespaces", at = @At("HEAD"), cancellable = true)
    public void getNamespacesHeadInjected(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (!GlobalCache.isEnabled || FusionPackCompat.hasOverrides(this))
            return;
        Set<String> cached = warpload$namespaces().get(type);
        if (cached != null)
            cir.setReturnValue(cached);
    }

    @Inject(method = "getNamespaces", at = @At("RETURN"))
    public void getNamespacesReturnInjected(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (!GlobalCache.isEnabled || cir.getReturnValue() == null || FusionPackCompat.hasOverrides(this))
            return;
        warpload$namespaces().put(type, cir.getReturnValue());
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    public void getResourceHeadInjected(PackType type, ResourceLocation location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || FusionPackCompat.hasOverrides(this))
            return;
        Set<String> paths = warpload$pathIndex(type);
        if (paths == null)
            return;
        cir.setReturnValue(paths.contains(location.getNamespace() + "/" + location.getPath())
                ? warpload$open(type, location) : null);
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    public void listResourcesHeadInjected(PackType type, String namespace, String path, PackResources.ResourceOutput resourceOutput, CallbackInfo ci) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || FusionPackCompat.hasOverrides(this))
            return;
        Set<String> paths = warpload$pathIndex(type);
        if (paths == null)
            return;
        String prefix = namespace + "/" + (path.isEmpty() ? "" : path + "/");
        int strip = namespace.length() + 1;
        for (String relative : paths) {
            if (!relative.startsWith(prefix))
                continue;
            ResourceLocation location = ResourceLocation.tryBuild(namespace, relative.substring(strip));
            if (location != null) {
                resourceOutput.accept(location, warpload$open(type, location));
            }
        }
        ci.cancel();
    }

    @Override
    public Boolean warpload$hasIndexedResource(PackType type, ResourceLocation location) {
        if (FusionPackCompat.hasOverrides(this))
            return null;
        Set<String> paths = warpload$pathIndex(type);
        if (paths == null)
            return null;
        return paths.contains(location.getNamespace() + "/" + location.getPath());
    }

    @Unique
    private Set<String> warpload$pathIndex(PackType type) {
        Map<PackType, Set<String>> index = warpload$pathIndexByType;
        if (index == null) {
            synchronized (this) {
                if (warpload$pathIndexByType == null) {
                    warpload$pathIndexByType = Maps.newConcurrentMap();
                }
                index = warpload$pathIndexByType;
            }
        }
        Set<String> existing = index.get(type);
        if (existing != null)
            return existing;
        return index.computeIfAbsent(type, packType -> {
            Path base = root.resolve(packType.getDirectory());
            try (Stream<Path> stream = Files.find(base, Integer.MAX_VALUE, (candidate, attributes) -> attributes.isRegularFile())) {
                String separator = base.getFileSystem().getSeparator();
                return stream.map(base::relativize)
                        .map(Path::toString)
                        .map(value -> value.replace(separator, "/"))
                        .collect(Collectors.toUnmodifiableSet());
            } catch (IOException | RuntimeException e) {
                return null;
            }
        });
    }

    @Unique
    private Map<PackType, Set<String>> warpload$namespaces() {
        if (warpload$namespaceCache == null) {
            synchronized (this) {
                if (warpload$namespaceCache == null) {
                    warpload$namespaceCache = Maps.newConcurrentMap();
                }
            }
        }
        return warpload$namespaceCache;
    }

    @Unique
    private IoSupplier<InputStream> warpload$open(PackType type, ResourceLocation location) {
        return () -> Files.newInputStream(root.resolve(type.getDirectory())
                .resolve(location.getNamespace()).resolve(location.getPath()));
    }
}
