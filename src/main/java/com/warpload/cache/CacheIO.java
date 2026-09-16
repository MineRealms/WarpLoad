package com.warpload.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public final class CacheIO {
    public static final int IO_BUFFER = 65536;
    private static final Map<String, ReentrantLock> KEY_LOCKS = new ConcurrentHashMap<>();

    private CacheIO() {
    }

    public static DataInputStream gzipIn(Path file) throws IOException {
        return new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file), IO_BUFFER), IO_BUFFER));
    }

    public static DataOutputStream gzipOut(Path file) throws IOException {
        return new DataOutputStream(new FastGzipOutputStream(new BufferedOutputStream(Files.newOutputStream(file), IO_BUFFER), IO_BUFFER));
    }

    public static ReentrantLock lockFor(Path target) {
        return KEY_LOCKS.computeIfAbsent(target.toAbsolutePath().normalize().toString(), ignored -> new ReentrantLock());
    }

    public static void clearLocks() {
        KEY_LOCKS.entrySet().removeIf(entry -> !entry.getValue().isLocked());
    }

    public static void writeString(DataOutput out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInput in) throws IOException {
        byte[] bytes = readBytes(in);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static JsonElement readJsonElement(DataInput in) throws IOException {
        byte[] bytes = readBytes(in);
        return JsonParser.parseReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
    }

    public static int mapCapacity(int count) {
        if (count <= 0) {
            return 16;
        }
        return Math.max(16, ((int) (count / 0.75f)) + 1);
    }

    private static byte[] readBytes(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 67108864) {
            throw new IOException("Invalid cached string length: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    public static void moveReplace(Path temp, Path target) throws IOException {
        Files.createDirectories(target.getParent(), new FileAttribute[0]);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            if (Files.isRegularFile(target, new LinkOption[0])) {
                Files.deleteIfExists(temp);
                return;
            }
            throw exception;
        }
    }

    public static void withFileLock(Path lockFile, Runnable action) throws IOException {
        Files.createDirectories(lockFile.getParent(), new FileAttribute[0]);
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            try (FileLock ignored = channel.lock()) {
                action.run();
            }
        }
    }

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        FastGzipOutputStream(OutputStream out, int size) throws IOException {
            super(out, size);
            this.def.setLevel(1);
        }
    }
}
