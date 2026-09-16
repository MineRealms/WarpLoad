package com.warpload.mixin.resources;

import com.warpload.cache.GlobalCache;
import com.warpload.compat.FusionPackCompat;
import com.warpload.interfaces.IPackResources;
import com.warpload.interfaces.IPathResourcePack;
import com.warpload.util.CacheUtil;
import com.google.common.collect.Maps;
import net.minecraft.FileUtil;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.resource.PathPackResources;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mixin(value = net.minecraftforge.resource.PathPackResources.class)
public abstract class PathResourcePackMixin implements IPathResourcePack, IPackResources {

    @Shadow @Final private static Logger LOGGER;
    @Shadow protected abstract Path resolve(String... paths);

    @Unique
    private Map<String, Path> warpload$resolvedPathByResource;

    @Unique
    private Map<PackType, Set<String>> warpload$namespacesByPackType;

    @Unique
    private Map<PackType, Map<String, List<String>>> warpload$relativeFilePathsByPackType;

    @Unique
    private Map<PackType, Map<String, Set<String>>> warpload$indexedResourcePathsByPackType;

    @Unique
    private Map<String, CompletableFuture<List<String>>> warpload$resourceListScans;

    @Unique
    private volatile boolean warpload$existenceCacheLoadRequested;

    @Unique
    private IModFile warpload$modFile;

    @Unique
    private String warpload$id;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(String packId, boolean isBuiltin, Path source, CallbackInfo ci) {
        if (GlobalCache.isEnabled) {
            warpload$resolvedPaths();
            warpload$namespaces();
            GlobalCache.add(this);
        }
    }

    @Inject(method = "resolve", at = @At("HEAD"), cancellable = true, remap = false)
    public void resolveHeadInjected(String[] paths, CallbackInfoReturnable<Path> cir) {
        if (warpload$modFile == null) {
            if (!GlobalCache.isEnabled)
                return;
            Path resolved = warpload$getResolvedPath(paths);
            if (resolved != null)
                cir.setReturnValue(resolved);
            return;
        }
        if (!GlobalCache.isEnabled) {
            cir.setReturnValue(warpload$modFile.findResource(paths));
            return;
        }
        Path path = warpload$getResolvedPath(paths);
        if (path == null) {
            path = warpload$modFile.findResource(paths);
            if (warpload$isUsablePath(path)) {
                warpload$resolvedPaths().put(Arrays.toString(paths), path);
            }
        }
        cir.setReturnValue(path);
    }

    @Inject(method = "resolve", at = @At("RETURN"), remap = false)
    public void resolveReturnInjected(String[] paths, CallbackInfoReturnable<Path> cir) {
        if (!GlobalCache.isEnabled || warpload$modFile != null)
            return;
        Path path = cir.getReturnValue();
        if (warpload$isUsablePath(path)) {
            warpload$resolvedPaths().put(Arrays.toString(paths), path);
        }
    }

    @Inject(method = "getNamespaces", at = @At("HEAD"), cancellable = true)
    public void getNamespacesHeadInjected(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (!GlobalCache.isEnabled || FusionPackCompat.hasOverrides(this))
            return;
        Set<String> namespaces = warpload$getCachedNamespaces(type);
        if (namespaces != null)
            cir.setReturnValue(namespaces);
    }

    @Inject(method = "getNamespaces", at = @At("RETURN"))
    public void getNamespacesReturnInjected(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (!GlobalCache.isEnabled || FusionPackCompat.hasOverrides(this))
            return;
        if (GlobalCache.shouldCacheEmptyNamespaces || cir.getReturnValue() != null && !cir.getReturnValue().isEmpty())
            warpload$cacheNamespaces(type, cir.getReturnValue());
    }

    @Inject(method = "getRootResource", at = @At("HEAD"), cancellable = true)
    public void getRootResourceHeadInjected(String[] paths, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence)
            return;

        String cacheKey = Arrays.toString(paths);
        Boolean exists = warpload$exists(cacheKey);

        if (exists != null) {
            if (exists) {
                cir.setReturnValue(warpload$openRootResource(Arrays.copyOf(paths, paths.length)));
            } else {
                cir.setReturnValue(null);
            }
        }
    }

    @Inject(method = "getRootResource", at = @At("RETURN"))
    public void getRootResourceReturnInjected(String[] paths, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence)
            return;

        String cacheKey = Arrays.toString(paths);
        boolean exists = cir.getReturnValue() != null;
        warpload$cacheExists(cacheKey, exists);
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    public void getResourceHeadInjected(PackType type, ResourceLocation location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || FusionPackCompat.hasOverrides(this)) {
            return;
        }
        Boolean indexed = warpload$hasIndexedResource(type, location);
        if (indexed != null) {
            cir.setReturnValue(indexed ? warpload$openResource(type, location) : null);
        }
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    public void listResourcesHeadInjected(PackType type, String namespace, String path, PackResources.ResourceOutput resourceOutput, CallbackInfo ci) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || FusionPackCompat.hasOverrides(this)) {
            return;
        }

        boolean[] fallbackToVanilla = {false};
        FileUtil.decomposePath(path).get().ifLeft(parts -> {
            try {
                Path root = resolve(type.getDirectory(), namespace).toAbsolutePath();
                List<String> cachedPaths = warpload$getCachedFilePaths(type, namespace);
                if (cachedPaths == null) {
                    CompletableFuture<List<String>> scan = warpload$scheduleFilePathScan(type, namespace, root);
                    cachedPaths = scan == null ? null : scan.join();
                    if (cachedPaths == null) {
                        fallbackToVanilla[0] = true;
                        return;
                    }
                }

                Path requestedRoot = FileUtil.resolvePath(root, parts);
                for (String cachedPath : cachedPaths) {
                    Path candidate = root.resolve(cachedPath);
                    if (!candidate.startsWith(requestedRoot)) {
                        continue;
                    }

                    String resourcePath = cachedPath.replace(candidate.getFileSystem().getSeparator(), "/");
                    ResourceLocation location = ResourceLocation.tryBuild(namespace, resourcePath);
                    if (location == null) {
                        Util.logAndPauseIfInIde(String.format(Locale.ROOT, "Invalid path in pack: %s:%s, ignoring", namespace, resourcePath));
                    } else {
                        resourceOutput.accept(location, warpload$openResource(type, location));
                    }
                }
            } catch (RuntimeException e) {
                fallbackToVanilla[0] = true;
                LOGGER.warn("WarpLoad path index lookup failed for {}:{}; falling back to vanilla resource enumeration",
                        namespace, path, e);
            }
        }).ifRight(dataResult -> LOGGER.error("Invalid path {}: {}", path, dataResult.message()));

        if (!fallbackToVanilla[0]) {
            ci.cancel();
        }
    }

    @Override
    public void warpload$persistAndClearCache() {
        if (warpload$modFile != null) {
            if (GlobalCache.shouldCacheResourceExistence) {
                CacheUtil.persist(warpload$getExistenceByResource(), new java.io.File(CacheUtil.HAS_RESOURCE_CACHE_DIR.getPath(), warpload$id + ".ser"));
            }
            CacheUtil.persist(warpload$namespaces(), new java.io.File(CacheUtil.NAMESPACE_CACHE_DIR.getPath(), warpload$id + ".ser"));
            CacheUtil.persist(warpload$relativeFilePaths(), new java.io.File(CacheUtil.RESOURCE_LIST_CACHE_DIR.getPath(), warpload$id + ".ser"));
        }
        warpload$getExistenceByResource().clear();
        warpload$resolvedPaths().clear();
        warpload$namespaces().clear();
        warpload$relativeFilePaths().clear();
        warpload$resourcePathSets().clear();
        warpload$scanFutures().clear();
    }

    @Override
    public void warpload$setModFile(IModFile modFile) {
        this.warpload$modFile = modFile;
        this.warpload$id = modFile.getModFileInfo().moduleName() + modFile.getModFileInfo().versionString()
                + "-" + FilenameUtils.getBaseName(modFile.getFilePath().toString()).replaceAll("[^a-zA-Z0-9.-]", "")
                + warpload$fileStamp(modFile.getFilePath());
        warpload$setExistenceByResource(GlobalCache.PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                warpload$id, i -> Maps.newConcurrentMap()));
        warpload$namespacesByPackType = GlobalCache.PERSISTED_NAMESPACES_BY_MOD.computeIfAbsent(
                warpload$id, i -> Maps.newConcurrentMap());
        warpload$relativeFilePathsByPackType = GlobalCache.PERSISTED_RESOURCE_LISTS_BY_MOD.computeIfAbsent(
                warpload$id, i -> Maps.newConcurrentMap());
    }

    @Override
    public void warpload$startAsyncPreload() {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldAsyncPreloadPacks) {
            return;
        }

        for (PackType packType : PackType.values()) {
            GlobalCache.supplyCacheAfterPersistedLoad("preload namespaces " + warpload$id + " " + packType, () -> {
                Set<String> namespaces = warpload$getCachedNamespaces(packType);
                if (namespaces == null) {
                    namespaces = warpload$scanNamespaces(packType);
                    warpload$cacheNamespaces(packType, namespaces);
                }
                for (String namespace : namespaces) {
                    if (warpload$getCachedFilePaths(packType, namespace) == null) {
                        Path root = resolve(packType.getDirectory(), namespace).toAbsolutePath();
                        warpload$scheduleFilePathScan(packType, namespace, root);
                    }
                }
                return null;
            });
        }
    }

    @Override
    public Boolean warpload$hasIndexedResource(PackType type, ResourceLocation location) {
        if (FusionPackCompat.hasOverrides(this)) {
            return null;
        }
        PackType effectiveType = warpload$effectiveType(type, location);
        List<String> cachedPaths = warpload$getCachedFilePaths(effectiveType, location.getNamespace());
        if (cachedPaths == null) {
            return null;
        }
        return warpload$getResourcePathSet(effectiveType, location.getNamespace(), cachedPaths)
                .contains(location.getPath());
    }

    @Unique
    public Path warpload$getResolvedPath(String... paths) {
        String key = Arrays.toString(paths);
        Path path = warpload$resolvedPaths().get(key);
        if (path != null && !warpload$isUsablePath(path)) {
            warpload$resolvedPaths().remove(key);
            return null;
        }
        return path;
    }

    @Unique
    public Boolean warpload$exists(String resourceName) {
        warpload$requestExistenceCacheLoad();
        return warpload$getExistenceByResource().get(resourceName);
    }

    @Unique
    public void warpload$cacheExists(String resourceName, boolean exists) {
        warpload$getExistenceByResource().put(resourceName, exists);
    }

    @Unique
    public List<String> warpload$getFilePaths(PackType packType, String resourceNamespace, Path root) {
        Map<String, List<String>> relativePaths = warpload$getRelativeFilePathsMap(packType);
        List<String> cachedRelativePaths = relativePaths.get(resourceNamespace);
        if (cachedRelativePaths != null) {
            warpload$getResourcePathSet(packType, resourceNamespace, cachedRelativePaths);
            return cachedRelativePaths;
        }

        List<String> scannedPaths = warpload$scanRelativeFilePaths(root);
        if (scannedPaths != null) {
            relativePaths.put(resourceNamespace, scannedPaths);
            warpload$getResourcePathSet(packType, resourceNamespace, scannedPaths);
        }
        return scannedPaths;
    }

    @Unique
    public List<String> warpload$getCachedFilePaths(PackType packType, String resourceNamespace) {
        return warpload$getRelativeFilePathsMap(packType).get(resourceNamespace);
    }

    @Unique
    private CompletableFuture<List<String>> warpload$scheduleFilePathScan(PackType packType, String resourceNamespace, Path root) {
        if (!GlobalCache.isEnabled || warpload$id == null) {
            return null;
        }
        String key = packType.name() + "|" + resourceNamespace;
        return warpload$scanFutures().computeIfAbsent(key, ignored ->
                GlobalCache.supplyCacheAfterPersistedLoad(
                        "scan resource paths " + warpload$id + " " + packType + " " + resourceNamespace,
                        () -> {
                            List<String> cached = warpload$getCachedFilePaths(packType, resourceNamespace);
                            return cached != null ? cached : warpload$getFilePaths(packType, resourceNamespace, root);
                        }));
    }

    @Unique
    private Set<String> warpload$getResourcePathSet(PackType packType, String namespace, List<String> paths) {
        return warpload$resourcePathSets()
                .computeIfAbsent(packType, ignored -> Maps.newConcurrentMap())
                .computeIfAbsent(namespace, ignored -> Set.copyOf(paths));
    }

    @Unique
    private Map<PackType, Map<String, Set<String>>> warpload$resourcePathSets() {
        if (warpload$indexedResourcePathsByPackType == null) {
            warpload$indexedResourcePathsByPackType = Maps.newConcurrentMap();
        }
        return warpload$indexedResourcePathsByPackType;
    }

    @Unique
    private Map<String, CompletableFuture<List<String>>> warpload$scanFutures() {
        if (warpload$resourceListScans == null) {
            warpload$resourceListScans = Maps.newConcurrentMap();
        }
        return warpload$resourceListScans;
    }

    @Unique
    private void warpload$requestExistenceCacheLoad() {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence || warpload$id == null || warpload$existenceCacheLoadRequested) {
            return;
        }
        warpload$existenceCacheLoadRequested = true;
        GlobalCache.loadPersistedCacheAsync(CacheUtil.HAS_RESOURCE_CACHE_DIR, warpload$id, warpload$getExistenceByResource());
    }

    @Unique
    private Map<String, List<String>> warpload$getRelativeFilePathsMap(PackType packType) {
        return warpload$relativeFilePaths().computeIfAbsent(packType, ignored -> Maps.newConcurrentMap());
    }

    @Unique
    public void warpload$cacheNamespaces(PackType packType, Set<String> namespaces) {
        warpload$namespaces().put(packType, namespaces);
    }

    @Unique
    public Set<String> warpload$getCachedNamespaces(PackType packType) {
        return warpload$namespaces().get(packType);
    }

    @Unique
    private Map<String, Path> warpload$resolvedPaths() {
        if (warpload$resolvedPathByResource == null) {
            warpload$resolvedPathByResource = Maps.newConcurrentMap();
        }
        return warpload$resolvedPathByResource;
    }

    @Unique
    private Map<PackType, Set<String>> warpload$namespaces() {
        if (warpload$namespacesByPackType == null) {
            warpload$namespacesByPackType = Maps.newConcurrentMap();
        }
        return warpload$namespacesByPackType;
    }

    @Unique
    private Map<PackType, Map<String, List<String>>> warpload$relativeFilePaths() {
        if (warpload$relativeFilePathsByPackType == null) {
            warpload$relativeFilePathsByPackType = Maps.newConcurrentMap();
            for (PackType packType : PackType.values()) {
                warpload$relativeFilePathsByPackType.put(packType, Maps.newConcurrentMap());
            }
        }
        return warpload$relativeFilePathsByPackType;
    }

    @Unique
    private List<String> warpload$scanRelativeFilePaths(Path root) {
        try (Stream<Path> stream = Files.find(root, Integer.MAX_VALUE, (candidate, attributes) -> attributes.isRegularFile())) {
            return warpload$toRelativePaths(root, stream.collect(Collectors.toList()));
        } catch (NoSuchFileException ignored) {
            return Collections.emptyList();
        } catch (IOException e) {
            LOGGER.error("Failed to list path {}", root, e);
            return null;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to list path {}; falling back to vanilla resource enumeration", root, e);
            return null;
        }
    }

    @Unique
    private static List<String> warpload$toRelativePaths(Path root, List<Path> paths) {
        String separator = root.getFileSystem().getSeparator();
        return paths.stream()
                .map(root::relativize)
                .map(Path::toString)
                .map(path -> path.replace(separator, "/"))
                .collect(Collectors.toList());
    }

    @Unique
    private Set<String> warpload$scanNamespaces(PackType type) {
        try {
            Path root = resolve(type.getDirectory());
            try (Stream<Path> walker = Files.walk(root, 1)) {
                return walker
                        .filter(Files::isDirectory)
                        .map(root::relativize)
                        .filter(path -> path.getNameCount() > 0)
                        .map(path -> path.toString().replaceAll("/$", ""))
                        .filter(namespace -> !namespace.isEmpty())
                        .collect(Collectors.toSet());
            }
        } catch (IOException e) {
            if (type == PackType.SERVER_DATA) {
                return warpload$scanNamespaces(PackType.CLIENT_RESOURCES);
            }
            return Collections.emptySet();
        }
    }

    @Unique
    private IoSupplier<InputStream> warpload$openResource(PackType type, ResourceLocation location) {
        PackType effectiveType = warpload$effectiveType(type, location);
        return () -> Files.newInputStream(resolve(warpload$getPathParts(effectiveType, location)));
    }

    @Unique
    private IoSupplier<InputStream> warpload$openRootResource(String[] paths) {
        return () -> Files.newInputStream(this.resolve(paths));
    }

    @Unique
    private static PackType warpload$effectiveType(PackType type, ResourceLocation location) {
        return location.getPath().startsWith("lang/") ? PackType.CLIENT_RESOURCES : type;
    }

    @Unique
    private static String[] warpload$getPathParts(PackType type, ResourceLocation location) {
        String[] resourceParts = location.getPath().split("/");
        String[] paths = new String[resourceParts.length + 2];
        paths[0] = type.getDirectory();
        paths[1] = location.getNamespace();
        System.arraycopy(resourceParts, 0, paths, 2, resourceParts.length);
        return paths;
    }

    @Unique
    private static String warpload$fileStamp(Path path) {
        try {
            if (path != null && Files.isRegularFile(path)) {
                return "-" + Files.size(path) + "x" + Files.getLastModifiedTime(path).toMillis();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    @Unique
    private static boolean warpload$isUsablePath(Path path) {
        try {
            return path != null && path.getFileSystem().isOpen();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
