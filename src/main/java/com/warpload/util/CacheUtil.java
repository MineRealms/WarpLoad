package com.warpload.util;

import com.mojang.logging.LogUtils;
import com.warpload.cache.CachePaths;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class CacheUtil {

    public static final File HAS_RESOURCE_CACHE_DIR = CachePaths.gameCacheRoot().resolve("hasResource").toFile();
    public static final File NAMESPACE_CACHE_DIR = CachePaths.gameCacheRoot().resolve("namespaces").toFile();
    public static final File RESOURCE_LIST_CACHE_DIR = CachePaths.gameCacheRoot().resolve("resourceLists").toFile();

    public static Stream<File> getCacheFiles(File dir) {
        if (!dir.isDirectory()) {
            return Stream.empty();
        }
        File[] caches = dir.listFiles((dir1, name) -> name.toLowerCase().endsWith(".ser"));
        if (caches == null) {
            return Stream.empty();
        }
        return Arrays.stream(caches).filter(file -> !file.isDirectory());
    }

    public static void persist(Map<?, ?> toPersist, File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            LogUtils.getLogger().warn("WarpLoad cannot create cache directory: {}", parent);
        }
        try (FileOutputStream fileOut = new FileOutputStream(file);
             ObjectOutputStream objectOut = new ObjectOutputStream(new BufferedOutputStream(fileOut))) {
            objectOut.writeObject(toPersist);
            objectOut.flush();
        } catch (Exception exception) {
            LogUtils.getLogger().error("WarpLoad cannot create cache file: {}", file, exception);
        }
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> load(File file) {
        try (FileInputStream fileIn = new FileInputStream(file);
             ObjectInputStream objectIn = new ObjectInputStream(new BufferedInputStream(fileIn))) {
            Object loaded = objectIn.readObject();
            if (loaded instanceof Map<?, ?> map) {
                return new ConcurrentHashMap<>((Map<K, V>) map);
            }
            LogUtils.getLogger().warn("WarpLoad cache file did not contain a map: {}", file.getName());
        } catch (Exception exception) {
            LogUtils.getLogger().error("WarpLoad cannot load cache file: {}", file.getName(), exception);
        }
        return new ConcurrentHashMap<>();
    }
}
