package com.warpload.cache;

import com.warpload.WarpLoad;
import com.warpload.compat.ModernFixCompat;
import com.warpload.config.WarpLoadConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

public final class ModelBakeCache {
    private static final int VERSION = 3;
    private static final String DATA = "models.bin.gz";
    private static final String FP = "fingerprint.txt";
    private static final int MAX_QUADS_PER_MODEL = 8192;
    private static final AtomicBoolean APPLIED_THIS_CYCLE = new AtomicBoolean(false);
    private static final AtomicBoolean STORE_CANCELLED = new AtomicBoolean(false);
    private static final AtomicBoolean STORE_IN_PROGRESS = new AtomicBoolean(false);
    private static final Direction[] DIRECTIONS = Direction.values();

    private ModelBakeCache() {
    }

    public static void clearMemory() {
    }

    public static void beginReloadCycle() {
        APPLIED_THIS_CYCLE.set(false);
    }

    public static void cancelStore() {
        STORE_CANCELLED.set(true);
    }

    public static boolean trySkipBake(Map<ResourceLocation, BakedModel> bakedTopLevel, int expectedModelCount,
                                      BiFunction<ResourceLocation, Material, TextureAtlasSprite> spriteGetter) {
        beginReloadCycle();
        if (!WarpLoadConfig.modelBakeCacheEnabled || bakedTopLevel == null || spriteGetter == null) {
            return false;
        }
        if (ModernFixCompat.isDynamicResources()) {
            ModernFixCompat.logRefuseOnce();
            return false;
        }
        if (expectedModelCount <= 0) {
            return false;
        }
        if (WarpLoadConfig.modelBakeMaxModels > 0 && expectedModelCount > WarpLoadConfig.modelBakeMaxModels) {
            if (WarpLoadConfig.logCacheEvents) {
                WarpLoad.LOGGER.info("Model bake cache SKIP blocked ({} models > max {})",
                        expectedModelCount, WarpLoadConfig.modelBakeMaxModels);
            }
            return false;
        }
        Path dir = cacheDir();
        Path dataFile = dir.resolve(DATA);
        Path fpFile = dir.resolve(FP);
        String fingerprint = fingerprint();
        try {
            if (!Files.isRegularFile(dataFile, new LinkOption[0]) || !Files.isRegularFile(fpFile, new LinkOption[0])) {
                return false;
            }
            if (!fingerprint.equals(Files.readString(fpFile, StandardCharsets.UTF_8).trim())) {
                if (WarpLoadConfig.logCacheEvents) {
                    WarpLoad.LOGGER.info("Model bake cache MISS (fingerprint changed)");
                }
                return false;
            }
            long start = System.nanoTime();
            int applied = fillFromDisk(dataFile, bakedTopLevel, spriteGetter);
            if (applied <= 0 || applied < expectedModelCount * 0.9d) {
                bakedTopLevel.clear();
                if (WarpLoadConfig.logCacheEvents) {
                    WarpLoad.LOGGER.info("Model bake cache MISS (only {}/{} models on disk)", applied, expectedModelCount);
                }
                return false;
            }
            if (!bakedTopLevel.containsKey(ModelBakery.MISSING_MODEL_LOCATION)) {
                bakedTopLevel.clear();
                WarpLoad.LOGGER.warn("Model bake cache MISS (missing model not in cache)");
                return false;
            }
            APPLIED_THIS_CYCLE.set(true);
            if (WarpLoadConfig.logCacheEvents) {
                double ms = (System.nanoTime() - start) / 1_000_000.0d;
                WarpLoad.LOGGER.info("Model bake cache DISK HIT - skipped bake (filled {}/{} models, {} ms) @ {}",
                        applied, expectedModelCount, String.format(Locale.ROOT, "%.1f", ms), dir);
            }
            return true;
        } catch (Exception exception) {
            bakedTopLevel.clear();
            WarpLoad.LOGGER.warn("Failed applying model bake cache (skip-bake)", exception);
            return false;
        } catch (OutOfMemoryError error) {
            bakedTopLevel.clear();
            System.gc();
            WarpLoad.LOGGER.warn("Model bake cache SKIP aborted (out of memory); falling back to live bake");
            return false;
        }
    }

    public static void tryStore(Map<ResourceLocation, BakedModel> models) {
        if (!WarpLoadConfig.modelBakeCacheEnabled || models == null || models.isEmpty() || APPLIED_THIS_CYCLE.get()) {
            return;
        }
        if (ModernFixCompat.isDynamicResources()) {
            ModernFixCompat.logRefuseOnce();
            return;
        }
        if (WarpLoadConfig.modelBakeMaxModels > 0 && models.size() > WarpLoadConfig.modelBakeMaxModels) {
            if (WarpLoadConfig.logCacheEvents) {
                WarpLoad.LOGGER.info("Model bake cache STORE skipped ({} models > max {})",
                        models.size(), WarpLoadConfig.modelBakeMaxModels);
            }
            return;
        }
        if (!STORE_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }
        try {
            Path dir = cacheDir();
            Path dataFile = dir.resolve(DATA);
            Path fpFile = dir.resolve(FP);
            String fingerprint = fingerprint();
            if (Files.isRegularFile(dataFile, new LinkOption[0]) && Files.isRegularFile(fpFile, new LinkOption[0])
                    && fingerprint.equals(Files.readString(fpFile, StandardCharsets.UTF_8).trim())) {
                STORE_IN_PROGRESS.set(false);
                return;
            }
            List<ResourceLocation> keys = List.copyOf(models.keySet());
            STORE_CANCELLED.set(false);
            Thread thread = new Thread(() -> {
                try {
                    storeAsync(models, keys, dir, dataFile, fpFile, fingerprint);
                } finally {
                    STORE_IN_PROGRESS.set(false);
                }
            }, "warpload-model-store");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception exception) {
            STORE_IN_PROGRESS.set(false);
            WarpLoad.LOGGER.warn("Failed starting model bake cache STORE", exception);
        }
    }

    private static void storeAsync(Map<ResourceLocation, BakedModel> models, List<ResourceLocation> keys, Path dir,
                                   Path dataFile, Path fpFile, String fingerprint) {
        Path temp = dir.resolve("models.bin.gz.tmp");
        try {
            long start = System.nanoTime();
            if (WarpLoadConfig.logCacheEvents) {
                WarpLoad.LOGGER.info("Model bake cache STORE starting ({} models)...", keys.size());
            }
            Files.createDirectories(dir, new FileAttribute[0]);
            int[] counts = writeStreaming(temp, models, keys);
            if (STORE_CANCELLED.get()) {
                Files.deleteIfExists(temp);
                return;
            }
            int written = counts[0];
            int skipped = counts[1];
            if (written <= 0 || written < keys.size() * 0.75d) {
                Files.deleteIfExists(temp);
                if (WarpLoadConfig.logCacheEvents) {
                    WarpLoad.LOGGER.info("Model bake cache STORE skipped (too many uncacheable models: {}/{})",
                            skipped, keys.size());
                }
                return;
            }
            CacheIO.moveReplace(temp, dataFile);
            Files.writeString(fpFile, fingerprint + "\n", StandardCharsets.UTF_8);
            if (WarpLoadConfig.logCacheEvents) {
                double ms = (System.nanoTime() - start) / 1_000_000.0d;
                WarpLoad.LOGGER.info("Model bake cache STORE ({} models, skipped {}, {} MB, {} ms) @ {}",
                        written, skipped, Files.size(dataFile) / 1048576, String.format(Locale.ROOT, "%.1f", ms), dir);
            }
        } catch (Exception exception) {
            WarpLoad.LOGGER.warn("Failed writing model bake cache", exception);
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
            }
        } catch (OutOfMemoryError error) {
            System.gc();
            WarpLoad.LOGGER.warn("Model bake cache STORE aborted (out of memory); game continues without disk cache");
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
            }
        }
    }

    private static int fillFromDisk(Path file, Map<ResourceLocation, BakedModel> target,
                                    BiFunction<ResourceLocation, Material, TextureAtlasSprite> spriteGetter) throws IOException {
        HashMap<String, String> intern = new HashMap<>(256);
        try (DataInputStream in = CacheIO.gzipIn(file)) {
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("bad version " + version);
            }
            int applied = 0;
            while (in.readBoolean()) {
                ResourceLocation id = ResourceLocation.tryParse(CacheIO.readString(in));
                BakedModel model = readAndRebuild(in, id, spriteGetter, intern);
                if (id != null && model != null) {
                    target.put(id, model);
                    applied++;
                }
            }
            intern.clear();
            return applied;
        }
    }

    private static int[] writeStreaming(Path file, Map<ResourceLocation, BakedModel> models, List<ResourceLocation> keys) throws IOException {
        int written = 0;
        int skipped = 0;
        RandomSource random = RandomSource.create(42L);
        try (DataOutputStream out = CacheIO.gzipOut(file)) {
            out.writeInt(VERSION);
            for (ResourceLocation id : keys) {
                if (STORE_CANCELLED.get()) {
                    break;
                }
                BakedModel model = models.get(id);
                if (!writeLiveModel(out, id, model, random)) {
                    skipped++;
                } else {
                    written++;
                }
            }
            out.writeBoolean(false);
        }
        return new int[]{written, skipped};
    }

    private static Path cacheDir() {
        return CachePaths.gameCacheRoot().resolve("client").resolve("models");
    }

    private static String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("warpload-model-v3-skipbake".getBytes(StandardCharsets.UTF_8));
            digest.update(("dr=" + (ModernFixCompat.isDynamicResources() ? "1" : "0")).getBytes(StandardCharsets.UTF_8));
            List<IModInfo> mods = ModList.get().getMods().stream()
                    .sorted(Comparator.comparing(IModInfo::getModId))
                    .toList();
            for (IModInfo mod : mods) {
                digest.update((mod.getModId() + "@" + mod.getVersion()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.getResourcePackRepository() != null) {
                for (String id : minecraft.getResourcePackRepository().getSelectedIds().stream().sorted().toList()) {
                    digest.update(id.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static boolean writeLiveModel(DataOutputStream out, ResourceLocation id, BakedModel model, RandomSource random) {
        if (id == null || model == null || model.isCustomRenderer()) {
            return false;
        }
        try {
            List<BakedQuad> unculled = model.getQuads(null, null, random);
            if (unculled != null && unculled.size() > MAX_QUADS_PER_MODEL) {
                return false;
            }
            List<BakedQuad>[] faces = new List[DIRECTIONS.length];
            int total = unculled == null ? 0 : unculled.size();
            for (int i = 0; i < DIRECTIONS.length; i++) {
                List<BakedQuad> face = model.getQuads(null, DIRECTIONS[i], random);
                if (face != null) {
                    total += face.size();
                    if (total > MAX_QUADS_PER_MODEL) {
                        return false;
                    }
                }
                faces[i] = face;
            }
            TextureAtlasSprite particle = model.getParticleIcon();
            if (particle == null) {
                return false;
            }
            out.writeBoolean(true);
            CacheIO.writeString(out, id.toString());
            out.writeBoolean(model.useAmbientOcclusion());
            out.writeBoolean(model.isGui3d());
            out.writeBoolean(model.usesBlockLight());
            CacheIO.writeString(out, particle.atlasLocation().toString());
            CacheIO.writeString(out, particle.contents().name().toString());
            float[] transforms = flattenTransforms(model.getTransforms());
            out.writeInt(transforms.length);
            for (float value : transforms) {
                out.writeFloat(value);
            }
            writeQuadList(out, unculled);
            out.writeInt(faces.length);
            for (int i = 0; i < faces.length; i++) {
                out.writeByte(DIRECTIONS[i].get3DDataValue());
                writeQuadList(out, faces[i]);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeQuadList(DataOutputStream out, List<BakedQuad> quads) throws IOException {
        if (quads == null || quads.isEmpty()) {
            out.writeInt(0);
            return;
        }
        int count = 0;
        for (BakedQuad quad : quads) {
            if (quad != null && quad.getSprite() != null) {
                count++;
            }
        }
        out.writeInt(count);
        for (BakedQuad quad : quads) {
            TextureAtlasSprite sprite;
            if (quad != null && (sprite = quad.getSprite()) != null) {
                int[] vertices = quad.getVertices();
                out.writeInt(vertices.length);
                for (int vertex : vertices) {
                    out.writeInt(vertex);
                }
                out.writeInt(quad.getTintIndex());
                out.writeByte(quad.getDirection().get3DDataValue());
                CacheIO.writeString(out, sprite.atlasLocation().toString());
                CacheIO.writeString(out, sprite.contents().name().toString());
                out.writeBoolean(quad.isShade());
                out.writeBoolean(quad.hasAmbientOcclusion());
            }
        }
    }

    @Nullable
    private static BakedModel readAndRebuild(DataInputStream in, @Nullable ResourceLocation modelId,
                                             BiFunction<ResourceLocation, Material, TextureAtlasSprite> spriteGetter,
                                             Map<String, String> intern) throws IOException {
        boolean ao = in.readBoolean();
        boolean gui3d = in.readBoolean();
        boolean blockLight = in.readBoolean();
        String particleAtlas = intern(intern, CacheIO.readString(in));
        String particleName = intern(intern, CacheIO.readString(in));
        int transformLength = in.readInt();
        float[] transforms = new float[transformLength];
        for (int i = 0; i < transformLength; i++) {
            transforms[i] = in.readFloat();
        }
        List<BakedQuad> unculled = readBakedQuads(in, modelId, spriteGetter, intern);
        int culledCount = in.readInt();
        EnumMap<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
        for (int i = 0; i < culledCount; i++) {
            Direction direction = Direction.from3DDataValue(in.readUnsignedByte());
            List<BakedQuad> face = readBakedQuads(in, modelId, spriteGetter, intern);
            if (direction != null) {
                culled.put(direction, face);
            }
        }
        for (Direction direction : DIRECTIONS) {
            culled.putIfAbsent(direction, List.of());
        }
        if (modelId == null) {
            return null;
        }
        try {
            TextureAtlasSprite particle = resolve(spriteGetter, modelId, particleAtlas, particleName);
            if (particle == null) {
                return null;
            }
            return new SimpleBakedModel(unculled, culled, ao, blockLight, gui3d, particle, rebuildTransforms(transforms), ItemOverrides.EMPTY);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<BakedQuad> readBakedQuads(DataInputStream in, @Nullable ResourceLocation modelId,
                                                  BiFunction<ResourceLocation, Material, TextureAtlasSprite> spriteGetter,
                                                  Map<String, String> intern) throws IOException {
        int count = in.readInt();
        if (count <= 0) {
            return List.of();
        }
        ArrayList<BakedQuad> quads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int vertexLength = in.readInt();
            int[] vertices = new int[vertexLength];
            for (int v = 0; v < vertexLength; v++) {
                vertices[v] = in.readInt();
            }
            int tint = in.readInt();
            Direction direction = Direction.from3DDataValue(in.readUnsignedByte());
            String atlas = intern(intern, CacheIO.readString(in));
            String spriteName = intern(intern, CacheIO.readString(in));
            boolean shade = in.readBoolean();
            boolean ao = in.readBoolean();
            TextureAtlasSprite sprite;
            if (modelId != null && direction != null && (sprite = resolve(spriteGetter, modelId, atlas, spriteName)) != null) {
                quads.add(new BakedQuad(vertices, tint, direction, sprite, shade, ao));
            }
        }
        return quads;
    }

    private static float[] flattenTransforms(ItemTransforms transforms) {
        float[] data = new float[96];
        ItemTransform[] ordered = {
                transforms.thirdPersonLeftHand, transforms.thirdPersonRightHand,
                transforms.firstPersonLeftHand, transforms.firstPersonRightHand,
                transforms.head, transforms.gui, transforms.ground, transforms.fixed};
        int i = 0;
        for (ItemTransform transform : ordered) {
            Vector3f rotation = transform.rotation;
            Vector3f translation = transform.translation;
            Vector3f scale = transform.scale;
            Vector3f rightRotation = transform.rightRotation;
            data[i++] = rotation.x();
            data[i++] = rotation.y();
            data[i++] = rotation.z();
            data[i++] = translation.x();
            data[i++] = translation.y();
            data[i++] = translation.z();
            data[i++] = scale.x();
            data[i++] = scale.y();
            data[i++] = scale.z();
            data[i++] = rightRotation.x();
            data[i++] = rightRotation.y();
            data[i++] = rightRotation.z();
        }
        return data;
    }

    private static ItemTransforms rebuildTransforms(float[] data) {
        ItemTransform[] transforms = new ItemTransform[8];
        int i = 0;
        for (int n = 0; n < 8; n++) {
            Vector3f rotation = new Vector3f(data[i], data[i + 1], data[i + 2]);
            Vector3f translation = new Vector3f(data[i + 3], data[i + 4], data[i + 5]);
            Vector3f scale = new Vector3f(data[i + 6], data[i + 7], data[i + 8]);
            Vector3f rightRotation = new Vector3f(data[i + 9], data[i + 10], data[i + 11]);
            i += 12;
            transforms[n] = new ItemTransform(rotation, translation, scale, rightRotation);
        }
        return new ItemTransforms(transforms[0], transforms[1], transforms[2], transforms[3],
                transforms[4], transforms[5], transforms[6], transforms[7]);
    }

    @Nullable
    private static TextureAtlasSprite resolve(BiFunction<ResourceLocation, Material, TextureAtlasSprite> spriteGetter,
                                              ResourceLocation modelId, String atlas, String name) {
        ResourceLocation atlasLocation = ResourceLocation.tryParse(atlas);
        ResourceLocation spriteLocation = ResourceLocation.tryParse(name);
        if (atlasLocation == null || spriteLocation == null) {
            return null;
        }
        try {
            return spriteGetter.apply(modelId, new Material(atlasLocation, spriteLocation));
        } catch (Exception e) {
            return null;
        }
    }

    private static String intern(Map<String, String> intern, String value) {
        if (value == null) {
            return "";
        }
        String existing = intern.putIfAbsent(value, value);
        return existing != null ? existing : value;
    }
}
