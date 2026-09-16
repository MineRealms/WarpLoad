package com.warpload.mixin.server;

import com.warpload.cache.DatapackCache;
import com.warpload.compat.FusionPackCompat;
import com.warpload.config.WarpLoadConfig;
import com.warpload.mixin.resources.FilePackResourcesAccessor;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

@Mixin(Pack.class)
public abstract class PackMixin {
    @Inject(method = "open", at = @At("RETURN"), cancellable = true)
    private void warpload$openCached(CallbackInfoReturnable<PackResources> cir) {
        PackResources opened = cir.getReturnValue();
        if (!(opened instanceof FilePackResources filePack)) {
            return;
        }
        Path zipPath = ((FilePackResourcesAccessor) filePack).warpload$getFile().toPath();
        Path cached = DatapackCache.getOrExtract(zipPath);
        if (cached == null) {
            return;
        }
        String overridesFolder = WarpLoadConfig.fusionCompatibilityMode
                ? FusionPackCompat.getOverridesFolder(this) : null;
        opened.close();
        PathPackResources replacement = new PathPackResources(filePack.packId(), cached, filePack.isBuiltin());
        if (overridesFolder != null) {
            FusionPackCompat.applyOverridesFolder(replacement, overridesFolder);
        }
        cir.setReturnValue(replacement);
    }
}
