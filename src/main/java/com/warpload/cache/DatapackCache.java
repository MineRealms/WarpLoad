package com.warpload.cache;

import com.warpload.WarpLoad;
import com.warpload.config.WarpLoadConfig;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class DatapackCache {
    private static final String META_FILE = ".warpload_meta";
    private static volatile Path worldRoot;
    private static volatile Path datapacksDir;
    private static volatile Path cacheRoot;
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Integer> SERVER_DATA_DEPTH = ThreadLocal.withInitial(() -> 0);

    private DatapackCache() {
    }

    public static void activate(Path worldDatapacksDir) {
        if (worldDatapacksDir == null) {
            return;
        }
        Path absoluteDatapacks = worldDatapacksDir.toAbsolutePath().normalize();
        Path absoluteWorld = absoluteDatapacks.getParent();
        if (absoluteWorld == null) {
            return;
        }
        String folder = CachePaths.worldFolderName();
        datapacksDir = absoluteDatapacks;
        worldRoot = absoluteWorld;
        cacheRoot = absoluteWorld.resolve(folder).normalize();
        PackFingerprint.invalidate();
        if (WarpLoadConfig.logCacheEvents) {
            WarpLoad.LOGGER.info("Datapack cache active at {}", cacheRoot);
        }
    }

    public static void deactivate() {
        worldRoot = null;
        datapacksDir = null;
        cacheRoot = null;
        SERVER_DATA_DEPTH.remove();
        PackFingerprint.invalidate();
        clearLocks();
    }

    public static void clearLocks() {
        LOCKS.entrySet().removeIf(entry -> !entry.getValue().isLocked());
    }

    public static void enterServerData() {
        SERVER_DATA_DEPTH.set(SERVER_DATA_DEPTH.get() + 1);
    }

    public static void exitServerData() {
        int depth = SERVER_DATA_DEPTH.get() - 1;
        if (depth <= 0) {
            SERVER_DATA_DEPTH.remove();
        } else {
            SERVER_DATA_DEPTH.set(depth);
        }
    }

    public static boolean isActive() {
        return WarpLoadConfig.enabled && WarpLoadConfig.datapackExtractionEnabled && cacheRoot != null;
    }

    @Nullable
    public static Path getCacheRoot() {
        return cacheRoot;
    }

    @Nullable
    public static Path getWorldRoot() {
        return worldRoot;
    }

    @Nullable
    public static Path getDatapacksDir() {
        return datapacksDir;
    }

    public static boolean shouldCache(Path zipPath) {
        if (!isActive() || zipPath == null) {
            return false;
        }
        Path absolute = zipPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute, new LinkOption[0])) {
            return false;
        }
        String name = absolute.getFileName().toString();
        if (!name.regionMatches(true, name.length() - 4, ".zip", 0, 4)) {
            return false;
        }
        if (datapacksDir == null || !absolute.startsWith(datapacksDir)) {
            return WarpLoadConfig.cacheExternalZips && SERVER_DATA_DEPTH.get() > 0;
        }
        return true;
    }

    @Nullable
    public static Path getOrExtract(Path zipPath) {
        if (!shouldCache(zipPath)) {
            return null;
        }
        Path absoluteZip = zipPath.toAbsolutePath().normalize();
        String key = cacheKey(absoluteZip);
        ReentrantLock lock = LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Path target = cacheRoot.resolve(key);
            if (isCacheValid(target, absoluteZip)) {
                if (WarpLoadConfig.logCacheEvents) {
                    WarpLoad.LOGGER.info("Using cached datapack {} -> {}", absoluteZip.getFileName(), target);
                }
                return target;
            }
            extractZip(absoluteZip, target);
            if (WarpLoadConfig.logCacheEvents) {
                WarpLoad.LOGGER.info("Extracted datapack {} -> {}", absoluteZip.getFileName(), target);
            }
            return target;
        } catch (Exception exception) {
            WarpLoad.LOGGER.error("Failed to cache datapack {}, falling back to zip", absoluteZip, exception);
            return null;
        } finally {
            lock.unlock();
        }
    }

    private static String cacheKey(Path zipPath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(zipPath.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(zipPath.toString().hashCode());
        }
    }

    private static boolean isCacheValid(Path cacheDir, Path zipPath) throws IOException {
        Path metaPath = cacheDir.resolve(META_FILE);
        if (!Files.isDirectory(cacheDir, new LinkOption[0])
                || !Files.isRegularFile(metaPath, new LinkOption[0])
                || !Files.isRegularFile(cacheDir.resolve("pack.mcmeta"), new LinkOption[0])) {
            return false;
        }
        CacheMeta expected = CacheMeta.fromZip(zipPath);
        return expected.equals(CacheMeta.read(metaPath));
    }

    private static void extractZip(Path zipPath, Path target) throws IOException {
        Files.createDirectories(cacheRoot, new FileAttribute[0]);
        Path staging = cacheRoot.resolve(target.getFileName().toString() + ".tmp-" + Thread.currentThread().getId());
        deleteRecursively(staging);
        Files.createDirectories(staging, new FileAttribute[0]);
        try {
            try (InputStream fileStream = Files.newInputStream(zipPath);
                 ZipInputStream zipStream = new ZipInputStream(new BufferedInputStream(fileStream, 65536))) {
                byte[] buffer = new byte[65536];
                ZipEntry entry;
                while ((entry = zipStream.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        Path dir = resolveSafe(staging, entry.getName());
                        if (Files.exists(dir, new LinkOption[0]) && !Files.isDirectory(dir, new LinkOption[0])) {
                            Files.delete(dir);
                        }
                        Files.createDirectories(dir, new FileAttribute[0]);
                    } else {
                        Path out = resolveSafe(staging, entry.getName());
                        if (Files.isDirectory(out, new LinkOption[0])) {
                            continue;
                        }
                        prepareParentDirectories(out.getParent());
                        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(out), 65536)) {
                            int read;
                            while ((read = zipStream.read(buffer)) != -1) {
                                output.write(buffer, 0, read);
                            }
                        }
                    }
                }
            }
            if (!Files.isRegularFile(staging.resolve("pack.mcmeta"), new LinkOption[0])) {
                throw new IOException("Extracted pack is missing pack.mcmeta: " + zipPath);
            }
            CacheMeta.fromZip(zipPath).write(staging.resolve(META_FILE));
            deleteRecursively(target);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            deleteRecursively(staging);
            throw exception;
        }
    }

    private static Path resolveSafe(Path root, String entryName) throws IOException {
        Path resolved = root.resolve(entryName).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Zip entry escapes target dir: " + entryName);
        }
        return resolved;
    }

    private static void prepareParentDirectories(Path parent) throws IOException {
        Path existing = parent;
        while (existing != null && !Files.exists(existing, new LinkOption[0])) {
            existing = existing.getParent();
        }
        if (existing != null && !Files.isDirectory(existing, new LinkOption[0])) {
            deleteRecursively(existing);
        }
        Files.createDirectories(parent, new FileAttribute[0]);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, new LinkOption[0])) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record CacheMeta(String source, long size, long lastModified) {

        static CacheMeta fromZip(Path zipPath) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(zipPath, BasicFileAttributes.class, new LinkOption[0]);
            return new CacheMeta(zipPath.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(),
                    attributes.size(), attributes.lastModifiedTime().toMillis());
        }

        static CacheMeta read(Path metaPath) throws IOException {
            String source = null;
            long size = -1;
            long lastModified = -1;
            for (String line : Files.readAllLines(metaPath, StandardCharsets.UTF_8)) {
                int split = line.indexOf('=');
                if (split > 0) {
                    String key = line.substring(0, split);
                    String value = line.substring(split + 1);
                    switch (key) {
                        case "source" -> source = value.toLowerCase();
                        case "size" -> size = Long.parseLong(value);
                        case "lastModified" -> lastModified = Long.parseLong(value);
                        default -> {
                        }
                    }
                }
            }
            return new CacheMeta(source, size, lastModified);
        }

        void write(Path metaPath) throws IOException {
            String content = "source=" + this.source + "\nsize=" + this.size + "\nlastModified=" + this.lastModified + "\n";
            Files.writeString(metaPath, content, StandardCharsets.UTF_8);
        }
    }
}
