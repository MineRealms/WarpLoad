package com.warpload.integration.gt;

import com.gregtechceu.gtceu.api.data.chemical.material.ItemMaterialData;
import com.gregtechceu.gtceu.common.data.GTRecipes;
import com.gregtechceu.gtceu.data.pack.GTDynamicDataPack;
import com.gregtechceu.gtceu.data.recipe.MaterialInfoLoader;
import com.gregtechceu.gtceu.data.recipe.misc.ComposterRecipes;
import com.gregtechceu.gtceu.data.recipe.misc.RecyclingRecipes;
import com.warpload.WarpLoad;
import com.warpload.cache.CacheIO;
import com.warpload.cache.CachePaths;
import com.warpload.cache.GlobalCache;
import com.warpload.cache.PackFingerprint;
import com.warpload.config.WarpLoadConfig;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.ComposterBlock;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public final class GTRecipeDataCache {
    private static final int VERSION = 1;
    private static final String DATA_NAME = "recipes.bin.gz";
    private static final String FP_NAME = "fingerprint.txt";
    private static final AtomicBoolean CAPTURING = new AtomicBoolean(false);
    private static volatile Map<ResourceLocation, byte[]> captured;
    private static final AtomicBoolean LOGGED_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean LOGGED_MISS = new AtomicBoolean(false);

    private GTRecipeDataCache() {
    }

    public static boolean tryApply(Consumer<FinishedRecipe> originalConsumer) {
        if (!WarpLoadConfig.gtRecipeDataCache || !WarpLoadConfig.enabled) {
            return false;
        }
        try {
            Path dir = cacheDir();
            Path dataFile = dir.resolve(DATA_NAME);
            Path fpFile = dir.resolve(FP_NAME);
            if (!Files.isRegularFile(dataFile, new LinkOption[0]) || !Files.isRegularFile(fpFile, new LinkOption[0])) {
                return false;
            }
            String fingerprint = fingerprint();
            if (!fingerprint.equals(Files.readString(fpFile, StandardCharsets.UTF_8).trim())) {
                if (LOGGED_MISS.compareAndSet(false, true)) {
                    WarpLoad.LOGGER.info("GT recipe data cache MISS (fingerprint changed), regenerating GregTech data");
                }
                return false;
            }
            long start = System.nanoTime();
            Map<ResourceLocation, byte[]> data = read(dataFile);
            if (data.isEmpty()) {
                return false;
            }
            applySideEffects();
            for (Map.Entry<ResourceLocation, byte[]> entry : data.entrySet()) {
                invokeAddToData(entry.getKey(), entry.getValue());
            }
            if (LOGGED_HIT.compareAndSet(false, true) || WarpLoadConfig.logCacheEvents) {
                double ms = (System.nanoTime() - start) / 1_000_000.0d;
                WarpLoad.LOGGER.info("GT recipe data cache DISK HIT: injected {} entries in {} ms, skipped GregTech recipe generation",
                        data.size(), String.format(Locale.ROOT, "%.1f", ms));
            }
            return true;
        } catch (Throwable throwable) {
            WarpLoad.LOGGER.warn("GT recipe data cache failed to apply, falling back to live generation", throwable);
            return false;
        }
    }

    public static void beginCapture() {
        if (!WarpLoadConfig.gtRecipeDataCache || !WarpLoadConfig.enabled) {
            CAPTURING.set(false);
            captured = null;
            return;
        }
        captured = new ConcurrentHashMap<>(131072);
        CAPTURING.set(true);
    }

    public static void capture(ResourceLocation location, byte[] bytes) {
        if (!CAPTURING.get()) {
            return;
        }
        Map<ResourceLocation, byte[]> map = captured;
        if (map != null && location != null && bytes != null) {
            map.put(location, bytes);
        }
    }

    public static void finishCapture() {
        if (!CAPTURING.compareAndSet(true, false)) {
            return;
        }
        Map<ResourceLocation, byte[]> map = captured;
        captured = null;
        if (map == null || map.isEmpty()) {
            return;
        }
        String fingerprint = fingerprint();
        store(cacheDir(), fingerprint, map);
    }

    private static void invokeAddToData(ResourceLocation location, byte[] bytes) {
        com.warpload.mixin.gt.GTDynamicDataPackMixin.warpload$addToData(location, bytes);
    }

    private static void applySideEffects() {
        ComposterRecipes.addComposterRecipes(ComposterBlock.COMPOSTABLES::put);
        ItemMaterialData.reinitializeMaterialData();
        MaterialInfoLoader.init();
        if (!recipeEventHasListeners()) {
            RecyclingRecipes.init(recipe -> {
            });
            ItemMaterialData.resolveItemMaterialInfos(recipe -> {
            });
        }
    }

    private static boolean recipeEventHasListeners() {
        try {
            Class<?> wrapper = Class.forName("com.gregtechceu.gtceu.common.data.GTRecipes$KJSCallWrapper");
            Method method = wrapper.getDeclaredMethod("recipeEventHasListeners");
            method.setAccessible(true);
            return Boolean.TRUE.equals(method.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String fingerprint() {
        return PackFingerprint.currentGlobal("gt-recipes|kjs=" + recipeEventHasListeners() + "|cfg=" + gtConfigHash());
    }

    private static String gtConfigHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path configDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
            if (!Files.isDirectory(configDir, new LinkOption[0])) {
                return "none";
            }
            java.util.List<Path> files = new java.util.ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.walk(configDir, 3)) {
                stream.filter(path -> Files.isRegularFile(path, new LinkOption[0]))
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).contains("gtceu"))
                        .forEach(files::add);
            }
            files.sort(java.util.Comparator.comparing(path -> configDir.relativize(path).toString()));
            byte[] buffer = new byte[65536];
            for (Path file : files) {
                digest.update(configDir.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (java.io.InputStream in = Files.newInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
                digest.update((byte) 0xFF);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "err";
        }
    }

    private static Path cacheDir() {
        return CachePaths.gameCacheRoot().resolve("gt");
    }

    private static Map<ResourceLocation, byte[]> read(Path file) throws IOException {
        try (DataInputStream in = CacheIO.gzipIn(file)) {
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported GT cache version " + version);
            }
            int count = in.readInt();
            if (count < 0 || count > 2_000_000) {
                throw new IOException("Invalid GT cache entry count " + count);
            }
            HashMap<ResourceLocation, byte[]> map = new HashMap<>(CacheIO.mapCapacity(count));
            for (int i = 0; i < count; i++) {
                ResourceLocation location = ResourceLocation.tryParse(CacheIO.readString(in));
                int length = in.readInt();
                if (length < 0 || length > 64 * 1024 * 1024) {
                    throw new IOException("Invalid GT cache entry length " + length);
                }
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                if (location != null) {
                    map.put(location, bytes);
                }
            }
            return map;
        }
    }

    private static void store(Path dir, String fingerprint, Map<ResourceLocation, byte[]> data) {
        Path dataFile = dir.resolve(DATA_NAME);
        Path fpFile = dir.resolve(FP_NAME);
        ReentrantLock lock = CacheIO.lockFor(dataFile);
        if (!lock.tryLock()) {
            return;
        }
        try {
            Files.createDirectories(dir, new FileAttribute[0]);
            Path temp = dir.resolve("recipes.bin.gz.tmp-" + Thread.currentThread().getId());
            long start = System.nanoTime();
            try (DataOutputStream out = CacheIO.gzipOut(temp)) {
                out.writeInt(VERSION);
                out.writeInt(data.size());
                for (Map.Entry<ResourceLocation, byte[]> entry : data.entrySet()) {
                    CacheIO.writeString(out, entry.getKey().toString());
                    byte[] bytes = entry.getValue();
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
            }
            CacheIO.moveReplace(temp, dataFile);
            Files.writeString(fpFile, fingerprint + "\n", StandardCharsets.UTF_8);
            if (WarpLoadConfig.logCacheEvents) {
                double ms = (System.nanoTime() - start) / 1_000_000.0d;
                WarpLoad.LOGGER.info("GT recipe data cache STORE: {} entries, {} MB on disk, {} ms",
                        data.size(), Files.size(dataFile) / 1048576, String.format(Locale.ROOT, "%.1f", ms));
            }
        } catch (Throwable throwable) {
            WarpLoad.LOGGER.warn("GT recipe data cache failed to store", throwable);
        } finally {
            lock.unlock();
        }
    }
}
