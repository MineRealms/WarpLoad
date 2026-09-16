package com.warpload.compat;

import com.warpload.WarpLoad;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ModernFixCompat {
    private static final String MOD_ID = "modernfix";
    private static final String OPTION = "mixin.perf.dynamic_resources";
    private static Boolean cached;
    private static boolean loggedRefuse;

    private ModernFixCompat() {
    }

    public static boolean isDynamicResources() {
        if (cached == null) {
            cached = detect();
        }
        return cached;
    }

    public static void logRefuseOnce() {
        if (loggedRefuse) {
            return;
        }
        loggedRefuse = true;
        WarpLoad.LOGGER.info("Model bake cache disabled (ModernFix dynamic_resources is active)");
    }

    private static boolean detect() {
        try {
            if (!ModList.get().isLoaded(MOD_ID)) {
                return false;
            }
            Boolean fromFile = readPropertiesFile();
            if (fromFile != null) {
                return fromFile;
            }
            Boolean fromPlugin = readMixinPlugin();
            return fromPlugin != null && fromPlugin;
        } catch (Exception e) {
            return false;
        }
    }

    private static Boolean readPropertiesFile() {
        try {
            Path config = FMLPaths.CONFIGDIR.get().resolve("modernfix-mixins.properties");
            if (!Files.isRegularFile(config)) {
                return null;
            }
            for (String line : Files.readAllLines(config)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int split = trimmed.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, split).trim();
                if (!OPTION.equals(key)) {
                    continue;
                }
                String value = trimmed.substring(split + 1).trim().toLowerCase();
                if (List.of("true", "on", "1").contains(value)) {
                    return Boolean.TRUE;
                }
                if (List.of("false", "off", "0").contains(value)) {
                    return Boolean.FALSE;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean readMixinPlugin() {
        try {
            Class<?> plugin = Class.forName("org.embeddedt.modernfix.core.ModernFixMixinPlugin");
            Object instance = plugin.getField("instance").get(null);
            if (instance == null) {
                return null;
            }
            Object result = plugin.getMethod("isOptionEnabled", String.class).invoke(instance, OPTION);
            return result instanceof Boolean value ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
