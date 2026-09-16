package com.warpload.compat;

import com.warpload.WarpLoad;
import com.warpload.cache.CacheIO;
import com.warpload.cache.CachePaths;
import com.warpload.cache.GlobalCache;
import com.warpload.cache.PackFingerprint;
import com.warpload.config.WarpLoadConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LdlCtmCache {
    private static final String TARGET_CLASS = "com.lowdragmc.lowdraglib.client.model.custommodel.LDLMetadataSection";
    private static final int VERSION = 1;
    private static final Object LOCK = new Object();

    private static volatile boolean attempted;
    private static volatile Class<?> targetClass;
    private static volatile Field metadataCacheField;
    private static volatile Field connectionField;
    private static volatile Field emissiveField;
    private static volatile Object missingInstance;
    private static volatile Constructor<?> sectionConstructor;

    private LdlCtmCache() {
    }

    public static void preloadOnRenderThread() {
        if (attempted || !WarpLoadConfig.ldlCtmCache || !isLdlibLoaded()) {
            return;
        }
        attempted = true;
        try {
            Path dir = cacheDir();
            Path dataFile = dir.resolve("ctm_metadata.bin.gz");
            Path fpFile = dir.resolve("ctm_fingerprint.txt");
            if (!Files.isRegularFile(dataFile) || !Files.isRegularFile(fpFile)) {
                return;
            }
            if (!fingerprint().equals(Files.readString(fpFile, StandardCharsets.UTF_8).trim())) {
                if (WarpLoadConfig.logCacheEvents) {
                    WarpLoad.LOGGER.info("LDL CTM cache MISS (resource state changed)");
                }
                return;
            }
            Map<ResourceLocation, Object> loaded = read(dataFile);
            synchronized (LOCK) {
                cacheMap().putAll(loaded);
            }
            if (WarpLoadConfig.logCacheEvents) {
                WarpLoad.LOGGER.info("LDL CTM cache LOADED {} entries", loaded.size());
            }
        } catch (Throwable throwable) {
            WarpLoad.LOGGER.warn("LDL CTM cache preload failed; continuing without it", throwable);
        }
    }

    public static void persist() {
        if (!attempted || !WarpLoadConfig.ldlCtmCache || !isLdlibLoaded()) {
            return;
        }
        final Map<ResourceLocation, Object> snapshot;
        try {
            synchronized (LOCK) {
                snapshot = new HashMap<>(cacheMap());
            }
        } catch (Throwable throwable) {
            return;
        }
        if (snapshot.isEmpty()) {
            return;
        }
        GlobalCache.executeCacheLogged("persist LDL CTM metadata", () -> writeToDisk(snapshot));
    }

    private static void writeToDisk(Map<ResourceLocation, Object> snapshot) {
        try {
            Path dir = cacheDir();
            Files.createDirectories(dir);
            Path dataFile = dir.resolve("ctm_metadata.bin.gz");
            Path temp = dir.resolve("ctm_metadata.bin.gz.tmp");
            write(temp, snapshot);
            CacheIO.moveReplace(temp, dataFile);
            Files.writeString(dir.resolve("ctm_fingerprint.txt"), fingerprint() + "\n", StandardCharsets.UTF_8);
            if (WarpLoadConfig.logCacheEvents) {
                WarpLoad.LOGGER.info("LDL CTM cache STORED {} entries", snapshot.size());
            }
        } catch (Throwable throwable) {
            WarpLoad.LOGGER.warn("LDL CTM cache persist failed", throwable);
        }
    }

    private static Map<ResourceLocation, Object> read(Path file) throws Exception {
        Map<ResourceLocation, Object> map = new HashMap<>();
        try (DataInputStream in = CacheIO.gzipIn(file)) {
            if (in.readInt() != VERSION) {
                throw new IllegalStateException("Unsupported LDL CTM cache version");
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                ResourceLocation location = ResourceLocation.tryParse(CacheIO.readString(in));
                int kind = in.readUnsignedByte();
                if (location == null) {
                    continue;
                }
                if (kind == 1) {
                    ResourceLocation connection = ResourceLocation.tryParse(CacheIO.readString(in));
                    boolean emissive = in.readBoolean();
                    map.put(location, sectionConstructor().newInstance(emissive, connection));
                } else {
                    map.put(location, missing());
                }
            }
        }
        return map;
    }

    private static void write(Path file, Map<ResourceLocation, Object> snapshot) throws Exception {
        Object missing = missing();
        try (DataOutputStream out = CacheIO.gzipOut(file)) {
            out.writeInt(VERSION);
            out.writeInt(snapshot.size());
            for (Map.Entry<ResourceLocation, Object> entry : snapshot.entrySet()) {
                Object value = entry.getValue();
                Object connection = value == null || value == missing ? null : connectionField().get(value);
                if (connection == null) {
                    CacheIO.writeString(out, entry.getKey().toString());
                    out.writeByte(0);
                    continue;
                }
                CacheIO.writeString(out, entry.getKey().toString());
                out.writeByte(1);
                CacheIO.writeString(out, connection.toString());
                out.writeBoolean(Boolean.TRUE.equals(emissiveField().get(value)));
            }
        }
    }

    private static Path cacheDir() {
        return CachePaths.gameCacheRoot().resolve("client");
    }

    private static String fingerprint() {
        return PackFingerprint.currentGlobal("ldl_ctm") + "|rp=" + resourcePacksHash();
    }

    private static String resourcePacksHash() {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("resourcepacks");
            if (!Files.isDirectory(dir)) {
                return "none";
            }
            List<Path> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    entries.add(entry);
                }
            }
            entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
            StringBuilder builder = new StringBuilder();
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                builder.append(name);
                if (Files.isRegularFile(entry)) {
                    builder.append('=').append(Files.size(entry)).append('@')
                            .append(Files.getLastModifiedTime(entry).toMillis());
                } else if (Files.isDirectory(entry)) {
                    Path meta = entry.resolve("pack.mcmeta");
                    if (Files.isRegularFile(meta)) {
                        builder.append("=d").append(Files.size(meta)).append('@')
                                .append(Files.getLastModifiedTime(meta).toMillis());
                    }
                }
                builder.append(';');
            }
            return Integer.toHexString(builder.toString().hashCode());
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static boolean isLdlibLoaded() {
        try {
            return ModList.get().isLoaded("ldlib");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Class<?> target() throws Exception {
        Class<?> clazz = targetClass;
        if (clazz == null) {
            clazz = Class.forName(TARGET_CLASS);
            targetClass = clazz;
        }
        return clazz;
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, Object> cacheMap() throws Exception {
        Field field = metadataCacheField;
        if (field == null) {
            field = target().getDeclaredField("METADATA_CACHE");
            field.setAccessible(true);
            metadataCacheField = field;
        }
        return (Map<ResourceLocation, Object>) field.get(null);
    }

    private static Object missing() throws Exception {
        Object value = missingInstance;
        if (value == null) {
            Field field = target().getDeclaredField("MISSING");
            field.setAccessible(true);
            value = field.get(null);
            missingInstance = value;
        }
        return value;
    }

    private static Constructor<?> sectionConstructor() throws Exception {
        Constructor<?> constructor = sectionConstructor;
        if (constructor == null) {
            constructor = target().getDeclaredConstructor(boolean.class, ResourceLocation.class);
            constructor.setAccessible(true);
            sectionConstructor = constructor;
        }
        return constructor;
    }

    private static Field connectionField() throws Exception {
        Field field = connectionField;
        if (field == null) {
            field = target().getDeclaredField("connection");
            field.setAccessible(true);
            connectionField = field;
        }
        return field;
    }

    private static Field emissiveField() throws Exception {
        Field field = emissiveField;
        if (field == null) {
            field = target().getDeclaredField("emissive");
            field.setAccessible(true);
            emissiveField = field;
        }
        return field;
    }
}
