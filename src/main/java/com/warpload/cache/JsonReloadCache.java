package com.warpload.cache;

import com.warpload.WarpLoad;
import com.warpload.config.WarpLoadConfig;

import javax.annotation.Nullable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public final class JsonReloadCache {
    private static final int VERSION = 6;
    private static final String DATA_NAME = "data.bin.gz";
    private static final String FP_NAME = "fingerprint.txt";
    private static final Set<String> DEFAULT_DIRECTORIES = Set.of("recipes", "advancements", "loot_tables");
    private static final SoftValueCache<Map<String, byte[]>> MEMORY = new SoftValueCache<>();
    private static final Set<String> SERVED_THIS_SESSION = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> STORED_THIS_SESSION = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private JsonReloadCache() {
    }

    public static void clearMemory() {
        MEMORY.clear();
    }

    public static boolean shouldCache(String directory) {
        if (!WarpLoadConfig.jsonReloadCacheEnabled || directory == null || directory.isBlank()) {
            return false;
        }
        String normalized = directory.toLowerCase(Locale.ROOT);
        return WarpLoadConfig.jsonReloadCacheAllDirectories
                || DEFAULT_DIRECTORIES.contains(normalized)
                || WarpLoadConfig.jsonReloadExtraDirectories.contains(normalized);
    }

    @Nullable
    public static Map<String, byte[]> tryLoadRaw(String directory) {
        if (!shouldCache(directory)) {
            return null;
        }
        if (!SERVED_THIS_SESSION.add(directory)) {
            return null;
        }
        String fingerprint = PackFingerprint.current(directory);
        String memoryKey = directory + "|" + fingerprint;
        Map<String, byte[]> memory = MEMORY.get(memoryKey);
        if (memory != null) {
            if (WarpLoadConfig.logCacheEvents) {
                WarpLoad.LOGGER.info("JSON cache MEMORY HIT for '{}' ({} entries)", directory, memory.size());
            }
            return memory;
        }
        Path dir = cacheDir(directory);
        Path dataFile = dir.resolve(DATA_NAME);
        Path fpFile = dir.resolve(FP_NAME);
        try {
            if (!Files.isRegularFile(dataFile, new LinkOption[0]) || !Files.isRegularFile(fpFile, new LinkOption[0])) {
                return null;
            }
            String storedFp = Files.readString(fpFile, StandardCharsets.UTF_8).trim();
            if (!fingerprint.equals(storedFp)) {
                if (WarpLoadConfig.logCacheEvents) {
                    WarpLoad.LOGGER.info("JSON cache MISS for '{}' (fingerprint changed)", directory);
                }
                return null;
            }
            long start = System.nanoTime();
            Map<String, byte[]> raw = read(dataFile);
            MEMORY.put(memoryKey, raw);
            if (WarpLoadConfig.logCacheEvents) {
                double ms = (System.nanoTime() - start) / 1_000_000.0d;
                WarpLoad.LOGGER.info("JSON cache DISK HIT for '{}' ({} entries, {} ms) @ {}",
                        directory, raw.size(), String.format(Locale.ROOT, "%.1f", ms), dir);
            }
            return raw;
        } catch (Exception exception) {
            WarpLoad.LOGGER.warn("Failed reading JSON reload cache for {}", directory, exception);
            return null;
        }
    }

    public static void storeRaw(String directory, Map<String, byte[]> raw) {
        if (!shouldCache(directory) || raw == null || raw.isEmpty()) {
            return;
        }
        if (!STORED_THIS_SESSION.add(directory)) {
            return;
        }
        String fingerprint = PackFingerprint.current(directory);
        Path dir = cacheDir(directory);
        Path dataFile = dir.resolve(DATA_NAME);
        Path fpFile = dir.resolve(FP_NAME);
        ReentrantLock lock = CacheIO.lockFor(dataFile);
        if (!lock.tryLock()) {
            return;
        }
        try {
            if (Files.isRegularFile(dataFile, new LinkOption[0]) && Files.isRegularFile(fpFile, new LinkOption[0])
                    && fingerprint.equals(Files.readString(fpFile, StandardCharsets.UTF_8).trim())) {
                return;
            }
            Files.createDirectories(dir, new FileAttribute[0]);
            Path temp = dir.resolve("data.bin.gz.tmp-" + Thread.currentThread().getId());
            long start = System.nanoTime();
            write(temp, raw);
            CacheIO.moveReplace(temp, dataFile);
            Files.writeString(fpFile, fingerprint + "\n", StandardCharsets.UTF_8);
            MEMORY.put(directory + "|" + fingerprint, raw);
            if (WarpLoadConfig.logCacheEvents) {
                double ms = (System.nanoTime() - start) / 1_000_000.0d;
                WarpLoad.LOGGER.info("JSON cache STORE for '{}' ({} entries, {} ms) @ {}",
                        directory, raw.size(), String.format(Locale.ROOT, "%.1f", ms), dir);
            }
        } catch (Exception exception) {
            WarpLoad.LOGGER.warn("Failed writing JSON reload cache for {}", directory, exception);
        } finally {
            lock.unlock();
        }
    }

    private static Path cacheDir(String directory) {
        String safeDir = directory.replace('/', '_').replace('\\', '_');
        return CachePaths.reloadCacheRoot().resolve("json").resolve(safeDir);
    }

    private static Map<String, byte[]> read(Path file) throws IOException {
        try (DataInputStream in = CacheIO.gzipIn(file)) {
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported cache version " + version);
            }
            int count = in.readInt();
            HashMap<String, byte[]> map = new HashMap<>(CacheIO.mapCapacity(count));
            for (int i = 0; i < count; i++) {
                String id = CacheIO.readString(in);
                int length = in.readInt();
                if (length < 0 || length > 268435456) {
                    throw new IOException("Invalid cached JSON length: " + length);
                }
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                map.put(id, bytes);
            }
            return map;
        }
    }

    private static void write(Path file, Map<String, byte[]> raw) throws IOException {
        try (DataOutputStream out = CacheIO.gzipOut(file)) {
            out.writeInt(VERSION);
            out.writeInt(raw.size());
            for (Map.Entry<String, byte[]> entry : raw.entrySet()) {
                CacheIO.writeString(out, entry.getKey());
                byte[] bytes = entry.getValue();
                out.writeInt(bytes.length);
                out.write(bytes);
            }
        }
    }
}
