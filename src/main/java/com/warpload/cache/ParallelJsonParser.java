package com.warpload.cache;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ParallelJsonParser {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ParallelJsonParser() {
    }

    public static Map<ResourceLocation, JsonElement> parseDirectory(String directory, Gson gson, ResourceManager resourceManager, ProfilerFiller profiler) {
        FileToIdConverter converter = FileToIdConverter.json(directory);
        profiler.push("warpload_list_json");
        Map<ResourceLocation, Resource> resources = converter.listMatchingResources(resourceManager);
        profiler.popPush("warpload_parse_json");
        boolean cache = JsonReloadCache.shouldCache(directory);
        Map<String, byte[]> raw = cache ? new ConcurrentHashMap<>(CacheIO.mapCapacity(resources.size())) : null;
        Map<ResourceLocation, JsonElement> parsed = new ConcurrentHashMap<>(CacheIO.mapCapacity(resources.size()));

        resources.entrySet().parallelStream().forEach(entry -> {
            ResourceLocation file = entry.getKey();
            ResourceLocation id = converter.fileToId(file);
            try (InputStream input = entry.getValue().open()) {
                byte[] bytes = input.readAllBytes();
                if (raw != null) {
                    raw.put(id.toString(), bytes);
                }
                JsonElement element = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonElement.class);
                if (element != null && element.isJsonObject()) {
                    parsed.put(id, element);
                }
            } catch (Exception exception) {
                LOGGER.error("Couldn't parse data file {} from {}", id, file, exception);
            }
        });

        profiler.pop();
        if (raw != null) {
            JsonReloadCache.storeRaw(directory, raw);
        }
        return parsed;
    }

    public static Map<ResourceLocation, JsonElement> parseRaw(Gson gson, Map<String, byte[]> raw) {
        Map<ResourceLocation, JsonElement> parsed = new ConcurrentHashMap<>(CacheIO.mapCapacity(raw.size()));
        raw.entrySet().parallelStream().forEach(entry -> {
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if (id == null) {
                return;
            }
            try {
                JsonElement element = gson.fromJson(new String(entry.getValue(), StandardCharsets.UTF_8), JsonElement.class);
                if (element != null && element.isJsonObject()) {
                    parsed.put(id, element);
                }
            } catch (Exception exception) {
                LOGGER.error("Couldn't parse cached data file {}", id, exception);
            }
        });
        return parsed;
    }
}
