package com.warpload.compat;

import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FusionPackCompat {
    private static final String[] OVERRIDE_FIELD_SUFFIXES = {
            "overridesFolderName",
            "overridesFolderRoot",
            "overridesFolder"
    };
    private static final ConcurrentMap<Class<?>, List<Field>> OVERRIDE_FIELDS_BY_CLASS = new ConcurrentHashMap<>();
    private static final java.util.Map<Object, Boolean> OVERRIDES_BY_INSTANCE = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static volatile Boolean fusionLoaded;

    private FusionPackCompat() {
    }

    public static boolean isFusionLoaded() {
        Boolean cached = fusionLoaded;
        if (cached == null) {
            cached = detectFusion();
            fusionLoaded = cached;
        }
        return cached;
    }

    private static boolean detectFusion() {
        try {
            return ModList.get().isLoaded("fusion");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static boolean hasOverrides(Object packResources) {
        if (packResources == null || !isFusionLoaded()) {
            return false;
        }
        Boolean cached = OVERRIDES_BY_INSTANCE.get(packResources);
        if (cached != null) {
            return cached;
        }
        boolean computed = computeOverrides(packResources);
        OVERRIDES_BY_INSTANCE.put(packResources, computed);
        return computed;
    }

    private static boolean computeOverrides(Object packResources) {
        for (Field field : OVERRIDE_FIELDS_BY_CLASS.computeIfAbsent(packResources.getClass(), FusionPackCompat::findOverrideFields)) {
            try {
                if (field.get(packResources) != null) {
                    return true;
                }
            } catch (IllegalAccessException | RuntimeException ignored) {
            }
        }
        return false;
    }

    public static String getOverridesFolder(Object pack) {
        if (pack == null || !isFusionLoaded()) {
            return null;
        }
        try {
            Class<?> packExtension = Class.forName("com.supermartijn642.fusion.extensions.PackExtension", false, FusionPackCompat.class.getClassLoader());
            if (!packExtension.isInstance(pack)) {
                return null;
            }
            Object metadata = packExtension.getMethod("getFusionMetadata").invoke(pack);
            if (metadata == null) {
                return null;
            }
            Object hasFolder = metadata.getClass().getMethod("hasOverridesFolder").invoke(metadata);
            if (!Boolean.TRUE.equals(hasFolder)) {
                return null;
            }
            Object folder = metadata.getClass().getMethod("getOverridesFolder").invoke(metadata);
            return folder instanceof String value && !value.isBlank() ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean applyOverridesFolder(Object packResources, String folder) {
        if (packResources == null || folder == null || !isFusionLoaded()) {
            return false;
        }
        try {
            Class<?> extension = Class.forName("com.supermartijn642.fusion.extensions.PackResourcesExtension", false, FusionPackCompat.class.getClassLoader());
            if (!extension.isInstance(packResources)) {
                return false;
            }
            extension.getMethod("setFusionOverridesFolder", String.class).invoke(packResources, folder);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<Field> findOverrideFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!isFusionOverrideField(field.getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return List.copyOf(fields);
    }

    private static boolean isFusionOverrideField(String fieldName) {
        for (String suffix : OVERRIDE_FIELD_SUFFIXES) {
            if (fieldName.equals(suffix) || fieldName.endsWith("$" + suffix) || fieldName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
