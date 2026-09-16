package com.warpload.debug;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadPoolExecutor;

public final class MoonlightProbe {

    private MoonlightProbe() {
    }

    public static String poolStats() {
        try {
            Class<?> clazz = Class.forName("net.mehvahdjukaar.moonlight.api.resources.pack.DynResourceGenerator");
            Field field = clazz.getDeclaredField("EXECUTOR_SERVICE");
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof ThreadPoolExecutor executor) {
                return "[size=" + executor.getPoolSize()
                        + " largest=" + executor.getLargestPoolSize()
                        + " active=" + executor.getActiveCount()
                        + " completed=" + executor.getCompletedTaskCount() + "]";
            }
        } catch (Throwable ignored) {
        }
        return "[n/a]";
    }
}
