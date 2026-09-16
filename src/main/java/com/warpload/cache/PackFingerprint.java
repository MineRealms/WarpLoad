package com.warpload.cache;

import com.warpload.config.WarpLoadConfig;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class PackFingerprint {
    private static volatile Path hashedDatapacksDir;
    private static volatile String modListFingerprint;
    private static final ConcurrentHashMap<String, String> DIR_CACHE = new ConcurrentHashMap<>();
    private static volatile String datapackHash = "none";
    private static volatile String kubejsHashValue;

    private PackFingerprint() {
    }

    public static void invalidate() {
        DIR_CACHE.clear();
        hashedDatapacksDir = null;
        datapackHash = "none";
        modListFingerprint = null;
    }

    public static String current(String directory) {
        String dir = directory == null ? "" : directory;
        String cached = DIR_CACHE.get(dir);
        if (cached != null) {
            return cached;
        }
        String computed = compute(dir);
        DIR_CACHE.put(dir, computed);
        return computed;
    }

    public static String currentGlobal(String salt) {
        String key = "global|" + salt;
        String cached = DIR_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String computed = computeGlobal(salt);
        DIR_CACHE.put(key, computed);
        return computed;
    }

    private static String computeGlobal(String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "gv=1");
            update(digest, "salt=" + salt);
            update(digest, "mods=" + modListFingerprint());
            update(digest, "kubejs=" + kubejsHash());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString((salt + modListFingerprint()).hashCode());
        }
    }

    private static String compute(String directory) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "v=6");
            update(digest, "dir=" + directory);
            update(digest, "mods=" + modListFingerprint());
            update(digest, "datapacks=" + datapackHash());
            update(digest, "kubejs=" + kubejsHash());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString((directory + modListFingerprint()).hashCode());
        }
    }

    private static String kubejsHash() {
        String cached = kubejsHashValue;
        if (cached != null) {
            return cached;
        }
        synchronized (PackFingerprint.class) {
            if (kubejsHashValue == null) {
                kubejsHashValue = computeKubejsHash();
            }
            return kubejsHashValue;
        }
    }

    private static String computeKubejsHash() {
        try {
            if (!ModList.get().isLoaded("kubejs")) {
                return "absent";
            }
            Path dir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("kubejs");
            if (!Files.isDirectory(dir, new LinkOption[0])) {
                return "none";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
                stream.filter(path -> Files.isRegularFile(path, new LinkOption[0])).forEach(files::add);
            }
            files.sort(Comparator.comparing(path -> dir.relativize(path).toString()));
            byte[] buffer = new byte[65536];
            for (Path file : files) {
                String relative = dir.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith("logs/") || relative.startsWith("exported/")) {
                    continue;
                }
                update(digest, relative);
                try (java.io.InputStream in = Files.newInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
                digest.update((byte) 0xFF);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "err";
        }
    }

    private static String datapackHash() {
        Path datapacks = DatapackCache.getDatapacksDir();
        Path hashed = hashedDatapacksDir;
        if (datapacks == null) {
            hashedDatapacksDir = null;
            datapackHash = "none";
            return "none";
        }
        if (datapacks.equals(hashed) && datapackHash != null) {
            return datapackHash;
        }
        String hash = Files.isDirectory(datapacks, new LinkOption[0]) ? hashDirectoryEntries(datapacks) : "none";
        hashedDatapacksDir = datapacks;
        datapackHash = hash;
        return hash;
    }

    private static String modListFingerprint() {
        String cached = modListFingerprint;
        if (cached != null) {
            return cached;
        }
        synchronized (PackFingerprint.class) {
            if (modListFingerprint == null) {
                modListFingerprint = buildModListFingerprint();
            }
            return modListFingerprint;
        }
    }

    private static String buildModListFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<IModInfo> mods = ModList.get().getMods().stream()
                    .sorted(Comparator.comparing(IModInfo::getModId))
                    .toList();
            update(digest, "count=" + mods.size());
            for (IModInfo mod : mods) {
                update(digest, mod.getModId() + "@" + mod.getVersion());
            }
            for (String modId : WarpLoadConfig.contentHashedMods) {
                update(digest, "jar:" + modId + "=" + hashModJar(modId));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String hashModJar(String modId) {
        try {
            var fileInfo = ModList.get().getModFileById(modId);
            if (fileInfo == null) {
                return "absent";
            }
            Path path = fileInfo.getFile().getFilePath();
            if (path == null || !Files.isRegularFile(path, new LinkOption[0])) {
                return "nofile";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            try (java.io.InputStream in = Files.newInputStream(path)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "err";
        }
    }

    private static String hashDirectoryEntries(Path dir) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                Iterator<Path> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    entries.add(iterator.next());
                }
            }
            entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                update(digest, name);
                if (Files.isRegularFile(entry, new LinkOption[0])) {
                    update(digest, "f=" + Files.size(entry) + "@" + Files.getLastModifiedTime(entry, new LinkOption[0]).toMillis());
                } else if (Files.isDirectory(entry, new LinkOption[0])) {
                    Path meta = entry.resolve("pack.mcmeta");
                    if (Files.isRegularFile(meta, new LinkOption[0])) {
                        update(digest, "d=" + Files.size(meta) + "@" + Files.getLastModifiedTime(meta, new LinkOption[0]).toMillis());
                    } else {
                        update(digest, "d");
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "err";
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
