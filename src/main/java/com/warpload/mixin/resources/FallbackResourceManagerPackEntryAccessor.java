package com.warpload.mixin.resources;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Predicate;

@Mixin(targets = "net.minecraft.server.packs.resources.FallbackResourceManager$PackEntry")
public interface FallbackResourceManagerPackEntryAccessor {
    @Invoker("name")
    String warpload$name();

    @Invoker("resources")
    PackResources warpload$resources();

    @Invoker("filter")
    Predicate<ResourceLocation> warpload$filter();

    @Invoker("isFiltered")
    boolean warpload$isFiltered(ResourceLocation location);
}
