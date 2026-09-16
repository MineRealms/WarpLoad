package com.warpload.cache;

import com.warpload.WarpLoad;
import com.warpload.config.WarpLoadConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagEntry;
import net.minecraft.tags.TagLoader;

import javax.annotation.Nullable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class TagReloadCache {
    private static final int VERSION = 5;
    private static final String DATA_NAME = "data.bin.gz";
    private static final String FP_NAME = "fingerprint.txt";
    private static final Set<String> HEAVY_DIRECTORIES = Set.of(
            "tags/items", "tags/blocks", "tags/entity_types", "tags/fluids",
            "tags/worldgen/biome", "tags/damage_type", "tags/banner_pattern",
            "tags/worldgen/structure", "tags/game_events", "tags/painting_variant");
    private static final ThreadLocal<Boolean> LOADED_FROM_CACHE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final SoftValueCache<Map<ResourceLocation, List<TagLoader.EntryWithSource>>> MEMORY = new SoftValueCache<>();
    private static final Set<String> SERVED_THIS_SESSION = ConcurrentHashMap.newKeySet();
    private static final Set<String> STORED_THIS_SESSION = ConcurrentHashMap.newKeySet();
    private static final Map<String, BuiltTags> BUILT = new ConcurrentHashMap<>();

    private record BuiltTags(long fingerprint, Map<ResourceLocation, Collection<?>> map) {
    }

    private TagReloadCache() {
    }

    public static void clearMemory() {
        MEMORY.clear();
    }

    public static void clearBuildReuse() {
        BUILT.clear();
    }

    public static Map<ResourceLocation, Collection<?>> tryReuseBuilt(String directory,
                                                                    Map<ResourceLocation, List<TagLoader.EntryWithSource>> raw) {
        if (!WarpLoadConfig.reuseBuiltTags || raw == null || !shouldCache(directory)) {
            return null;
        }
        BuiltTags built = BUILT.get(directory);
        if (built == null || built.fingerprint() != builtFingerprint(raw)) {
            return null;
        }
        if (WarpLoadConfig.logCacheEvents) {
            WarpLoad.LOGGER.info("Tag build reuse HIT for '{}' ({} tags)", directory, built.map().size());
        }
        return built.map();
    }

    public static void storeBuilt(String directory,
                                  Map<ResourceLocation, List<TagLoader.EntryWithSource>> raw,
                                  Map<ResourceLocation, Collection<?>> built) {
        if (!WarpLoadConfig.reuseBuiltTags || raw == null || built == null || !shouldCache(directory)) {
            return;
        }
        BUILT.put(directory, new BuiltTags(builtFingerprint(raw), built));
    }

    private static long builtFingerprint(Map<ResourceLocation, List<TagLoader.EntryWithSource>> raw) {
        long hash = 0L;
        for (Map.Entry<ResourceLocation, List<TagLoader.EntryWithSource>> tag : raw.entrySet()) {
            long tagHash = tag.getKey().hashCode();
            List<TagLoader.EntryWithSource> entries = tag.getValue();
            if (entries != null) {
                for (TagLoader.EntryWithSource entry : entries) {
                    TagEntry tagEntry = entry.entry();
                    tagHash = tagHash * 31L + entry.source().hashCode();
                    tagHash = tagHash * 31L + (entry.remove() ? 1L : 0L);
                    tagHash = tagHash * 31L + tagEntry.getId().hashCode();
                    tagHash = tagHash * 31L + (tagEntry.isTag() ? 2L : 0L) + (tagEntry.isRequired() ? 4L : 0L);
                }
            }
            hash += tagHash;
        }
        return hash;
    }

    public static boolean shouldCache(String directory) {
        if (!WarpLoadConfig.tagReloadCacheEnabled || directory == null || directory.isBlank()) {
            return false;
        }
        if (WarpLoadConfig.tagReloadCacheAllDirectories) {
            return true;
        }
        String normalized = directory.toLowerCase(Locale.ROOT).replace('\\', '/');
        return HEAVY_DIRECTORIES.contains(normalized) || WarpLoadConfig.tagReloadExtraDirectories.contains(normalized);
    }

    public static void markLoadedFromCache() {
        LOADED_FROM_CACHE.set(Boolean.TRUE);
    }

    public static boolean consumeLoadedFromCache() {
        boolean value = Boolean.TRUE.equals(LOADED_FROM_CACHE.get());
        LOADED_FROM_CACHE.set(Boolean.FALSE);
        return value;
    }

    @Nullable
    public static Map<ResourceLocation, List<TagLoader.EntryWithSource>> tryLoad(String directory) {
        if (!shouldCache(directory)) {
            return null;
        }
        if (!SERVED_THIS_SESSION.add(directory)) {
            return null;
        }
        String fingerprint = PackFingerprint.current(directory);
        String memoryKey = directory + "|" + fingerprint;
        Map<ResourceLocation, List<TagLoader.EntryWithSource>> memory = MEMORY.get(memoryKey);
        if (memory != null) {
            if (WarpLoadConfig.logCacheEvents) {
                WarpLoad.LOGGER.info("Tag cache MEMORY HIT for '{}' ({} tags)", directory, memory.size());
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
                    WarpLoad.LOGGER.info("Tag cache MISS for '{}' (fingerprint changed)", directory);
                }
                return null;
            }
            long start = System.nanoTime();
            Map<ResourceLocation, List<TagLoader.EntryWithSource>> map = read(dataFile);
            MEMORY.put(memoryKey, map);
            if (WarpLoadConfig.logCacheEvents) {
                double ms = (System.nanoTime() - start) / 1_000_000.0d;
                WarpLoad.LOGGER.info("Tag cache DISK HIT for '{}' ({} tags, {} ms) @ {}",
                        directory, map.size(), String.format(Locale.ROOT, "%.1f", ms), dir);
            }
            return map;
        } catch (Exception exception) {
            WarpLoad.LOGGER.warn("Failed reading tag reload cache for {}", directory, exception);
            return null;
        }
    }

    public static void trySave(String directory, Map<ResourceLocation, List<TagLoader.EntryWithSource>> data) {
        if (!shouldCache(directory) || data == null || data.isEmpty()) {
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
            write(temp, data);
            CacheIO.moveReplace(temp, dataFile);
            Files.writeString(fpFile, fingerprint + "\n", StandardCharsets.UTF_8);
            MEMORY.put(directory + "|" + fingerprint, data);
            if (WarpLoadConfig.logCacheEvents) {
                double ms = (System.nanoTime() - start) / 1_000_000.0d;
                WarpLoad.LOGGER.info("Tag cache STORE for '{}' ({} tags, {} ms) @ {}",
                        directory, data.size(), String.format(Locale.ROOT, "%.1f", ms), dir);
            }
        } catch (Exception exception) {
            WarpLoad.LOGGER.warn("Failed writing tag reload cache for {}", directory, exception);
        } finally {
            lock.unlock();
        }
    }

    private static Path cacheDir(String directory) {
        String safeDir = directory.replace('/', '_').replace('\\', '_');
        return CachePaths.reloadCacheRoot().resolve("tags").resolve(safeDir);
    }

    private static Map<ResourceLocation, List<TagLoader.EntryWithSource>> read(Path file) throws IOException {
        try (DataInputStream in = CacheIO.gzipIn(file)) {
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported cache version " + version);
            }
            int tagCount = in.readInt();
            HashMap<ResourceLocation, List<TagLoader.EntryWithSource>> map = new HashMap<>(CacheIO.mapCapacity(tagCount));
            for (int i = 0; i < tagCount; i++) {
                ResourceLocation id = ResourceLocation.tryParse(CacheIO.readString(in));
                int entryCount = in.readInt();
                ArrayList<TagLoader.EntryWithSource> entries = new ArrayList<>(entryCount);
                for (int j = 0; j < entryCount; j++) {
                    boolean remove = in.readBoolean();
                    String source = CacheIO.readString(in);
                    String entryId = CacheIO.readString(in);
                    boolean tag = in.readBoolean();
                    boolean required = in.readBoolean();
                    ResourceLocation entryLocation = ResourceLocation.tryParse(entryId);
                    if (entryLocation != null) {
                        TagEntry tagEntry = tag
                                ? (required ? TagEntry.tag(entryLocation) : TagEntry.optionalTag(entryLocation))
                                : (required ? TagEntry.element(entryLocation) : TagEntry.optionalElement(entryLocation));
                        entries.add(new TagLoader.EntryWithSource(tagEntry, source, remove));
                    }
                }
                if (id != null) {
                    map.put(id, entries);
                }
            }
            return map;
        }
    }

    private static void write(Path file, Map<ResourceLocation, List<TagLoader.EntryWithSource>> data) throws IOException {
        try (DataOutputStream out = CacheIO.gzipOut(file)) {
            out.writeInt(VERSION);
            out.writeInt(data.size());
            for (Map.Entry<ResourceLocation, List<TagLoader.EntryWithSource>> tag : data.entrySet()) {
                CacheIO.writeString(out, tag.getKey().toString());
                List<TagLoader.EntryWithSource> entries = tag.getValue();
                out.writeInt(entries.size());
                for (TagLoader.EntryWithSource entry : entries) {
                    out.writeBoolean(entry.remove());
                    CacheIO.writeString(out, entry.source());
                    TagEntry tagEntry = entry.entry();
                    CacheIO.writeString(out, tagEntry.getId().toString());
                    out.writeBoolean(tagEntry.isTag());
                    out.writeBoolean(tagEntry.isRequired());
                }
            }
        }
    }
}
