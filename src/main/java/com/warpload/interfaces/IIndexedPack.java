package com.warpload.interfaces;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;

public interface IIndexedPack {
    Boolean warpload$hasIndexedResource(PackType type, ResourceLocation location);
}
