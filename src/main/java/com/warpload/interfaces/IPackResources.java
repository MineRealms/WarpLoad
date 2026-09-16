package com.warpload.interfaces;

import java.util.Map;

public interface IPackResources extends ICache {

    Map<String, Boolean> warpload$getExistenceByResource();

    void warpload$setExistenceByResource(Map<String, Boolean> existenceByResource);
}
