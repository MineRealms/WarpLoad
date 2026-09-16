package com.warpload.mixin.server;

import com.warpload.cache.DatapackCache;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(FolderRepositorySource.class)
public abstract class FolderRepositorySourceMixin {

    @Shadow
    @Final
    private PackType packType;

    @Shadow
    @Final
    private java.nio.file.Path folder;

    @Inject(method = "loadPacks", at = @At("HEAD"))
    private void warpload$enter(Consumer<Pack> onLoad, CallbackInfo ci) {
        if (this.packType == PackType.SERVER_DATA) {
            DatapackCache.activate(this.folder);
            DatapackCache.enterServerData();
        }
    }

    @Inject(method = "loadPacks", at = @At("RETURN"))
    private void warpload$exit(Consumer<Pack> onLoad, CallbackInfo ci) {
        if (this.packType == PackType.SERVER_DATA) {
            DatapackCache.exitServerData();
        }
    }
}
