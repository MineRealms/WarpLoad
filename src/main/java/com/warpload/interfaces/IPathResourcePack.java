package com.warpload.interfaces;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraftforge.forgespi.locating.IModFile;

public interface IPathResourcePack extends PackResources, IPackResources, IIndexedPack {
    void warpload$setModFile(IModFile modFile);

    void warpload$startAsyncPreload();
}
