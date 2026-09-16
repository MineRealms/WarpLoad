package com.warpload.client;

import com.warpload.cache.ModelBakeCache;

public final class ClientModelCacheHooks {
    private ClientModelCacheHooks() {
    }

    public static void clear() {
        ModelBakeCache.clearMemory();
    }

    public static void cancelStore() {
        ModelBakeCache.cancelStore();
    }
}
