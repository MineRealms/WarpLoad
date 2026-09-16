package com.warpload.mixin.resources;

import com.warpload.cache.GlobalCache;
import com.warpload.interfaces.IIndexedPack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraftforge.resource.PathPackResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Mixin(FallbackResourceManager.class)
public abstract class FallbackResourceManagerMixin {

    @Unique
    private static final Logger warpload$logger = LoggerFactory.getLogger("WarpLoad");

    @Shadow @Final public List<?> fallbacks;

    @Shadow @Final private PackType type;

    @Invoker("createStackMetadataFinder")
    protected abstract IoSupplier<ResourceMetadata> warpload$createStackMetadataFinder(ResourceLocation location, int index);

    @Invoker("createResource")
    private static Resource warpload$createResource(PackResources pack, ResourceLocation location,
                                                     IoSupplier<InputStream> resource,
                                                     IoSupplier<ResourceMetadata> metadata) {
        throw new AssertionError();
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    private void getResourceHeadInjected(ResourceLocation location, CallbackInfoReturnable<Optional<Resource>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldParallelizeResourcePackLookup || this.fallbacks.size() <= 1) {
            return;
        }

        List<IndexedPack> safeSegment = new ArrayList<>();
        for (int i = this.fallbacks.size() - 1; i >= 0; i--) {
            FallbackResourceManagerPackEntryAccessor entry = (FallbackResourceManagerPackEntryAccessor) this.fallbacks.get(i);
            PackResources packResources = entry.warpload$resources();

            if (packResources != null && entry.warpload$filter() == null && GlobalCache.isSafeForParallelLookup(packResources)) {
                safeSegment.add(new IndexedPack(i, packResources));
                continue;
            }

            Optional<Resource> resource = warpload$searchSafeSegment(safeSegment, location);
            if (resource != null) {
                cir.setReturnValue(resource);
                return;
            }
            safeSegment.clear();

            if (packResources != null) {
                IoSupplier<InputStream> supplier = packResources.getResource(this.type, location);
                if (supplier != null) {
                    cir.setReturnValue(Optional.of(warpload$createResource(
                            packResources, location, supplier, warpload$createStackMetadataFinder(location, i))));
                    return;
                }
            }

            if (entry.warpload$isFiltered(location)) {
                warpload$logger.warn("Resource {} not found, but was filtered by pack {}", location, entry.warpload$name());
                cir.setReturnValue(Optional.empty());
                return;
            }
        }

        Optional<Resource> resource = warpload$searchSafeSegment(safeSegment, location);
        cir.setReturnValue(resource == null ? Optional.empty() : resource);
    }

    @Unique
    private Optional<Resource> warpload$searchSafeSegment(List<IndexedPack> segment, ResourceLocation location) {
        if (segment.isEmpty()) {
            return null;
        }
        Optional<Optional<Resource>> indexedSearch;
        try {
            indexedSearch = warpload$searchIndexedSegment(segment, location);
        } catch (Throwable ignored) {
            indexedSearch = Optional.empty();
        }
        if (indexedSearch.isPresent()) {
            return indexedSearch.get().isPresent() ? indexedSearch.get() : null;
        }
        if (segment.size() < GlobalCache.parallelLookupMinPacks) {
            return warpload$searchSafeSegmentSequential(segment, location);
        }

        if (segment.size() == 1) {
            IndexedPack indexedPack = segment.get(0);
            IoSupplier<InputStream> supplier = indexedPack.pack().getResource(this.type, location);
            return supplier == null ? null : Optional.of(warpload$createResource(indexedPack.pack(), location,
                    supplier, warpload$createStackMetadataFinder(location, indexedPack.index())));
        }

        List<CompletableFuture<IoSupplier<InputStream>>> futures = new ArrayList<>(segment.size());
        try {
            for (IndexedPack indexedPack : segment) {
                futures.add(CompletableFuture.supplyAsync(() -> indexedPack.pack().getResource(this.type, location), GlobalCache.EXECUTOR));
            }
        } catch (RuntimeException e) {
            warpload$logger.warn("WarpLoad parallel resource lookup rejected for {}; falling back to vanilla order", location, e);
            return warpload$searchSafeSegmentSequential(segment, location);
        }

        for (int i = 0; i < segment.size(); i++) {
            IndexedPack indexedPack = segment.get(i);
            try {
                IoSupplier<InputStream> supplier = futures.get(i).get();
                if (supplier != null) {
                    return Optional.of(warpload$createResource(indexedPack.pack(), location,
                            supplier, warpload$createStackMetadataFinder(location, indexedPack.index())));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return warpload$searchSafeSegmentSequential(segment, location);
            } catch (ExecutionException e) {
                warpload$logger.warn("WarpLoad parallel resource lookup failed for {} in {}", location, indexedPack.pack().packId(), e);
            }
        }
        return null;
    }

    @Unique
    private Optional<Optional<Resource>> warpload$searchIndexedSegment(List<IndexedPack> segment, ResourceLocation location) {
        for (IndexedPack indexedPack : segment) {
            if (!(indexedPack.pack() instanceof IIndexedPack indexedPackResources)) {
                return Optional.empty();
            }
            Boolean present = indexedPackResources.warpload$hasIndexedResource(this.type, location);
            if (present == null) {
                return Optional.empty();
            }
            if (present) {
                IoSupplier<InputStream> supplier = indexedPack.pack().getResource(this.type, location);
                if (supplier == null) {
                    return Optional.empty();
                }
                return Optional.of(Optional.of(warpload$createResource(
                        indexedPack.pack(), location, supplier,
                        warpload$createStackMetadataFinder(location, indexedPack.index()))));
            }
        }
        return Optional.of(Optional.empty());
    }

    @Unique
    private Optional<Resource> warpload$searchSafeSegmentSequential(List<IndexedPack> segment, ResourceLocation location) {
        for (IndexedPack indexedPack : segment) {
            IoSupplier<InputStream> supplier = indexedPack.pack().getResource(this.type, location);
            if (supplier != null) {
                return Optional.of(warpload$createResource(indexedPack.pack(), location,
                        supplier, warpload$createStackMetadataFinder(location, indexedPack.index())));
            }
        }
        return null;
    }

    @Unique
    private record IndexedPack(int index, PackResources pack) {
    }
}
