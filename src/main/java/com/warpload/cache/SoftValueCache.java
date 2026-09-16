package com.warpload.cache;

import com.warpload.config.WarpLoadConfig;

import javax.annotation.Nullable;
import java.lang.ref.SoftReference;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class SoftValueCache<V> {
    private final ConcurrentHashMap<String, SoftReference<V>> map = new ConcurrentHashMap<>();

    SoftValueCache() {
    }

    @Nullable
    V get(String key) {
        SoftReference<V> ref = this.map.get(key);
        if (ref == null) {
            return null;
        }
        V value = ref.get();
        if (value == null) {
            this.map.remove(key, ref);
        }
        return value;
    }

    void put(String key, V value) {
        if (!WarpLoadConfig.memoryCacheEnabled || value == null) {
            return;
        }
        pruneDead();
        if (this.map.size() >= Math.max(1, WarpLoadConfig.memoryCacheMaxEntries)) {
            Iterator<Map.Entry<String, SoftReference<V>>> iterator = this.map.entrySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        this.map.put(key, new SoftReference<>(value));
    }

    void clear() {
        this.map.clear();
    }

    int size() {
        return this.map.size();
    }

    private void pruneDead() {
        this.map.entrySet().removeIf(entry -> {
            SoftReference<V> ref = entry.getValue();
            return ref == null || ref.get() == null;
        });
    }
}
