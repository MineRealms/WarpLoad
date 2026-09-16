package com.warpload.debug;

import com.warpload.WarpLoad;
import com.warpload.cache.GlobalCache;
import com.warpload.config.WarpLoadConfig;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReloadSampler {
    private static final AtomicInteger ACTIVE_LISTENERS = new AtomicInteger();
    private static volatile Thread samplerThread;
    private static volatile long firstStartNanos;

    private ReloadSampler() {
    }

    public static void listenerStarted() {
        activityStarted();
    }

    public static void listenerFinished() {
        activityStopped();
    }

    public static void generationStarted() {
        activityStarted();
    }

    public static void generationFinished() {
        activityStopped();
    }

    private static void activityStarted() {
        if (!WarpLoadConfig.debugReloadTimeline) {
            return;
        }
        if (ACTIVE_LISTENERS.incrementAndGet() == 1) {
            firstStartNanos = System.nanoTime();
            startSampler();
        }
    }

    private static void activityStopped() {
        if (!WarpLoadConfig.debugReloadTimeline) {
            return;
        }
        if (ACTIVE_LISTENERS.decrementAndGet() <= 0) {
            ACTIVE_LISTENERS.set(0);
            long millis = (System.nanoTime() - firstStartNanos) / 1_000_000L;
            WarpLoad.LOGGER.info("[WarpLoad-DBG] reload sampler stopped after {} ms | {} | moonlightPool={}",
                    millis, GlobalCache.poolStats(), MoonlightProbe.poolStats());
        }
    }

    private static void startSampler() {
        Thread thread = new Thread(ReloadSampler::run, "WarpLoad-ReloadSampler");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        samplerThread = thread;
        thread.start();
    }

    private static void run() {
        while (ACTIVE_LISTENERS.get() > 0) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return;
            }
            WarpLoad.LOGGER.info("[WarpLoad-DBG] reload sample: cpu={}% {} | moonlightPool={} | jvmThreads={}",
                    cpuLoad(), GlobalCache.poolStats(), MoonlightProbe.poolStats(), Thread.activeCount());
        }
    }

    private static long cpuLoad() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double load = sunBean.getProcessCpuLoad();
                if (load >= 0.0d) {
                    return Math.round(load * 100.0d);
                }
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }
}
