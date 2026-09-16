package com.warpload.cache;

import com.warpload.compat.FusionPackCompat;
import com.warpload.interfaces.ICache;
import com.warpload.interfaces.IIndexedPack;
import com.warpload.util.CacheUtil;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.slf4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class GlobalCache {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final AtomicInteger RELOAD_THREAD_ID = new AtomicInteger();
    private static final AtomicBoolean PERSISTED_CACHE_LOAD_STARTED = new AtomicBoolean(false);
    private static volatile CompletableFuture<Void> persistedCacheLoad = CompletableFuture.completedFuture(null);

    public static volatile boolean isEnabled = true;
    public static volatile boolean shouldCacheWalkedPaths = true;
    public static volatile boolean shouldCacheEmptyNamespaces = true;
    public static volatile boolean shouldCacheResourceExistence = true;
    public static volatile boolean shouldCacheMaterials = true;
    public static volatile boolean shouldAsyncPreloadPacks = true;
    public static volatile boolean shouldParallelizeResourcePackLookup = true;
    public static volatile boolean shouldUseDedicatedResourceReloadExecutor = true;
    public static volatile int parallelLookupMinPacks = 4;
    public static volatile boolean shouldIsolateModdedResourceReloadFailures = true;
    public static volatile boolean shouldUseConnectorCompatibilityMode = true;
    public static volatile List<String> isolatedResourceReloadListenerPatterns = List.of("*");
    public static final Map<String, String> CANONICAL_PATH_PER_FILE = Maps.newConcurrentMap();
    private static final Set<ICache> CACHES = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));
    public static final Map<String, Map<String, Boolean>> PERSISTED_EXISTENCES_BY_MOD = Maps.newConcurrentMap();
    public static final Map<String, Map<PackType, Set<String>>> PERSISTED_NAMESPACES_BY_MOD = Maps.newConcurrentMap();
    public static final Map<String, Map<PackType, Map<String, List<String>>>> PERSISTED_RESOURCE_LISTS_BY_MOD = Maps.newConcurrentMap();
    public static final int WORKER_COUNT = getWorkerCount();
    private static final AtomicInteger CACHE_THREAD_ID = new AtomicInteger();
    private static final Set<CompletableFuture<?>> BACKGROUND_CACHE_TASKS = Sets.newConcurrentHashSet();
    private static final int CACHE_WORKER_COUNT = getCacheWorkerCount();
    public static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(WORKER_COUNT,
            runnable -> newDaemonThread(runnable, "WarpLoad-", THREAD_ID, Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1)));
    public static final ExecutorService CACHE_EXECUTOR = Executors.newFixedThreadPool(CACHE_WORKER_COUNT,
            runnable -> newDaemonThread(runnable, "WarpLoad-Cache-", CACHE_THREAD_ID, Thread.MIN_PRIORITY));
    private static volatile int reloadWorkersOverride = 0;
    private static volatile ForkJoinPool RESOURCE_RELOAD_EXECUTOR = createReloadPool();

    public static void add(ICache cache) {
        CACHES.add(cache);
    }

    public static CompletableFuture<Void> loadPersistedCachesAsync() {
        if (PERSISTED_CACHE_LOAD_STARTED.compareAndSet(false, true)) {
            persistedCacheLoad = CompletableFuture.allOf(
                    loadPersistedCaches(CacheUtil.NAMESPACE_CACHE_DIR, PERSISTED_NAMESPACES_BY_MOD),
                    loadPersistedCaches(CacheUtil.RESOURCE_LIST_CACHE_DIR, PERSISTED_RESOURCE_LISTS_BY_MOD)
            ).exceptionally(throwable -> {
                LOGGER.error("WarpLoad failed to load persisted caches", throwable);
                return null;
            });
        }
        return persistedCacheLoad;
    }

    public static <K, V> CompletableFuture<Void> loadPersistedCacheAsync(File dir, String id, Map<K, V> targetMap) {
        if (id == null || id.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        File file = new File(dir, id + ".ser");
        if (!file.isFile()) {
            return CompletableFuture.completedFuture(null);
        }
        return executeCacheLogged("load cache file " + file.getName(), () -> targetMap.putAll(CacheUtil.load(file)));
    }

    public static CompletableFuture<Void> executeLogged(String taskName, Runnable task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("WarpLoad task failed: {}", taskName, e);
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<Void> executeCacheLogged(String taskName, Runnable task) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                if (isEnabled) {
                    task.run();
                }
            } catch (Exception e) {
                LOGGER.error("WarpLoad cache task failed: {}", taskName, e);
            }
        }, CACHE_EXECUTOR);
        return trackBackgroundTask(future);
    }

    public static <T> CompletableFuture<T> supplyCacheAfterPersistedLoad(String taskName, Supplier<T> task) {
        CompletableFuture<T> future = loadPersistedCachesAsync().thenApplyAsync(ignored -> {
            try {
                return isEnabled ? task.get() : null;
            } catch (Exception e) {
                LOGGER.error("WarpLoad cache task failed: {}", taskName, e);
                return null;
            }
        }, CACHE_EXECUTOR);
        return trackBackgroundTask(future);
    }

    public static ExecutorService resourceReloadExecutor(ExecutorService fallback) {
        return shouldUseDedicatedResourceReloadExecutor ? RESOURCE_RELOAD_EXECUTOR : fallback;
    }

    private static <T> CompletableFuture<T> trackBackgroundTask(CompletableFuture<T> future) {
        BACKGROUND_CACHE_TASKS.add(future);
        future.whenComplete((ignored, throwable) -> BACKGROUND_CACHE_TASKS.remove(future));
        return future;
    }

    public static IoSupplier<InputStream> findFirstResource(List<PackResources> packs, PackType type, ResourceLocation location) {
        if (packs.isEmpty()) {
            return null;
        }
        Optional<Optional<IoSupplier<InputStream>>> indexed = findFirstIndexedResource(packs, type, location);
        if (indexed.isPresent()) {
            return indexed.get().orElse(null);
        }
        if (packs.size() < parallelLookupMinPacks || !shouldParallelizeResourcePackLookup || packs.stream().anyMatch(pack -> !isSafeForParallelLookup(pack))) {
            return findFirstResourceSequential(packs, type, location);
        }

        List<CompletableFuture<IoSupplier<InputStream>>> futures = new ArrayList<>(packs.size());
        try {
            for (PackResources pack : packs) {
                futures.add(CompletableFuture.supplyAsync(() -> pack.getResource(type, location), EXECUTOR));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("WarpLoad parallel resource lookup rejected; falling back to sequential lookup", e);
            return findFirstResourceSequential(packs, type, location);
        }

        for (CompletableFuture<IoSupplier<InputStream>> future : futures) {
            try {
                IoSupplier<InputStream> supplier = future.get();
                if (supplier != null) {
                    return supplier;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return findFirstResourceSequential(packs, type, location);
            } catch (ExecutionException e) {
                LOGGER.warn("WarpLoad parallel resource lookup failed for {}", location, e);
            }
        }
        return null;
    }

    public static void persistAndTrimCaches() {
        awaitPersistedCachesLoaded();
        awaitBackgroundCacheTasks();

        List<CompletableFuture<Void>> persistTasks = new ArrayList<>();
        for (ICache cache : snapshotCaches()) {
            persistTasks.add(runPersistTask(cache));
        }
        CompletableFuture.allOf(persistTasks.toArray(new CompletableFuture[0])).join();
        CANONICAL_PATH_PER_FILE.clear();
        PERSISTED_EXISTENCES_BY_MOD.clear();
        PERSISTED_NAMESPACES_BY_MOD.clear();
        PERSISTED_RESOURCE_LISTS_BY_MOD.clear();
    }

    public static void clearCaches() {
        isEnabled = false;
        awaitPersistedCachesLoaded();
        awaitBackgroundCacheTasks();
        synchronized (CACHES) {
            CACHES.clear();
        }
        CANONICAL_PATH_PER_FILE.clear();
        PERSISTED_EXISTENCES_BY_MOD.clear();
        PERSISTED_NAMESPACES_BY_MOD.clear();
        PERSISTED_RESOURCE_LISTS_BY_MOD.clear();
    }

    private static List<ICache> snapshotCaches() {
        synchronized (CACHES) {
            return new ArrayList<>(CACHES);
        }
    }

    private static int getWorkerCount() {
        int configured = Integer.getInteger("warpload.workers", 0);
        if (configured > 0) {
            return configured;
        }
        return Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 32));
    }

    private static int getCacheWorkerCount() {
        int configured = Integer.getInteger("warpload.cacheWorkers", 0);
        if (configured > 0) {
            return Math.max(1, Math.min(configured, 8));
        }
        return Math.max(2, Math.min(Runtime.getRuntime().availableProcessors() / 4, 8));
    }

    private static int getReloadWorkerCount() {
        int override = reloadWorkersOverride;
        if (override > 0) {
            return Math.max(2, Math.min(override, 64));
        }
        int configured = Integer.getInteger("warpload.reloadWorkers", 0);
        if (configured > 0) {
            return Math.max(2, Math.min(configured, 32));
        }
        return Math.max(2, Math.min(Runtime.getRuntime().availableProcessors() - 2, 32));
    }

    private static ForkJoinPool createReloadPool() {
        return new ForkJoinPool(
                getReloadWorkerCount(),
                GlobalCache::newReloadWorker,
                (thread, throwable) -> LOGGER.error("WarpLoad resource reload worker failed: {}", thread.getName(), throwable),
                true);
    }

    public static synchronized void setReloadWorkersOverride(int workers) {
        int clamped = workers <= 0 ? 0 : Math.max(2, Math.min(workers, 64));
        if (clamped == reloadWorkersOverride) {
            return;
        }
        reloadWorkersOverride = clamped;
        ForkJoinPool previous = RESOURCE_RELOAD_EXECUTOR;
        ForkJoinPool replacement = createReloadPool();
        if (previous.getParallelism() == replacement.getParallelism()) {
            replacement.shutdown();
            return;
        }
        RESOURCE_RELOAD_EXECUTOR = replacement;
        previous.shutdown();
        LOGGER.info("WarpLoad reload executor parallelism changed from {} to {}",
                previous.getParallelism(), replacement.getParallelism());
    }

    public static String poolStats() {
        ForkJoinPool reload = RESOURCE_RELOAD_EXECUTOR;
        return "reloadPool[active=" + reload.getActiveThreadCount()
                + " running=" + reload.getRunningThreadCount()
                + " parallelism=" + reload.getParallelism()
                + " queuedSubmission=" + reload.getQueuedSubmissionCount()
                + " queuedTask=" + reload.getQueuedTaskCount() + "]"
                + " wlPool=" + executorStats(EXECUTOR)
                + " cachePool=" + executorStats(CACHE_EXECUTOR);
    }

    private static String executorStats(ExecutorService service) {
        if (service instanceof ThreadPoolExecutor executor) {
            return "[active=" + executor.getActiveCount() + " queue=" + executor.getQueue().size() + "]";
        }
        return "[n/a]";
    }

    private static ForkJoinWorkerThread newReloadWorker(ForkJoinPool pool) {
        ForkJoinWorkerThread thread = new ForkJoinWorkerThread(pool) {
        };
        thread.setContextClassLoader(GlobalCache.class.getClassLoader());
        thread.setName("WarpLoad-Reload-" + RELOAD_THREAD_ID.incrementAndGet());
        return thread;
    }

    private static Thread newDaemonThread(Runnable runnable, String namePrefix, AtomicInteger id, int priority) {
        Thread thread = new Thread(runnable, namePrefix + id.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(priority);
        return thread;
    }

    private static <K, V> CompletableFuture<Void> loadPersistedCaches(File dir, Map<String, Map<K, V>> targetMap) {
        dir.mkdirs();
        CompletableFuture<?>[] futures = CacheUtil.getCacheFiles(dir)
                .map(file -> executeCacheLogged("load cache file " + file.getName(), () -> {
                    String id = org.apache.commons.io.FilenameUtils.getBaseName(file.getName());
                    targetMap.computeIfAbsent(id, ignored -> Maps.newConcurrentMap()).putAll(CacheUtil.load(file));
                }))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    private static CompletableFuture<Void> runPersistTask(ICache cache) {
        return CompletableFuture.runAsync(() -> {
            try {
                cache.warpload$persistAndClearCache();
            } catch (Exception e) {
                LOGGER.error("WarpLoad cache persist failed: {}", cache.getClass().getName(), e);
            }
        }, CACHE_EXECUTOR);
    }

    private static void awaitPersistedCachesLoaded() {
        try {
            loadPersistedCachesAsync().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("WarpLoad cache loading interrupted; continuing without waiting for all cache files", e);
        } catch (ExecutionException e) {
            LOGGER.warn("WarpLoad cache loading failed; continuing with in-memory caches", e);
        } catch (TimeoutException e) {
            LOGGER.warn("WarpLoad cache loading timed out; continuing while remaining files load in the background", e);
        }
    }

    private static void awaitBackgroundCacheTasks() {
        while (true) {
            CompletableFuture<?>[] tasks = BACKGROUND_CACHE_TASKS.toArray(new CompletableFuture[0]);
            if (tasks.length == 0) {
                return;
            }
            try {
                CompletableFuture.allOf(tasks).join();
            } catch (CompletionException e) {
                LOGGER.warn("WarpLoad cache task failed before persist", e.getCause());
            }
        }
    }

    public static void shutdownExecutors() {
        EXECUTOR.shutdown();
        CACHE_EXECUTOR.shutdown();
    }

    private static Optional<Optional<IoSupplier<InputStream>>> findFirstIndexedResource(List<PackResources> packs, PackType type, ResourceLocation location) {
        for (PackResources pack : packs) {
            if (!(pack instanceof IIndexedPack indexedPack)) {
                return Optional.empty();
            }
            Boolean present = indexedPack.warpload$hasIndexedResource(type, location);
            if (present == null) {
                return Optional.empty();
            }
            if (present) {
                return Optional.of(Optional.ofNullable(pack.getResource(type, location)));
            }
        }
        return Optional.of(Optional.empty());
    }

    private static IoSupplier<InputStream> findFirstResourceSequential(List<PackResources> packs, PackType type, ResourceLocation location) {
        for (PackResources pack : packs) {
            IoSupplier<InputStream> supplier = pack.getResource(type, location);
            if (supplier != null) {
                return supplier;
            }
        }
        return null;
    }

    public static boolean isSafeForParallelLookup(PackResources packResources) {
        Class<?> packClass = packResources.getClass();
        boolean forgeModPathPack = packResources instanceof net.minecraftforge.resource.PathPackResources
                && (packClass == net.minecraftforge.resource.PathPackResources.class || packClass.getName().startsWith("net.minecraftforge.resource.ResourcePackLoader$"));
        boolean vanillaPathPack = packClass == PathPackResources.class;
        return (forgeModPathPack || vanillaPathPack || packClass == FilePackResources.class) && !FusionPackCompat.hasOverrides(packResources);
    }
}
