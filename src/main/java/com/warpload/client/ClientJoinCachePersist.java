package com.warpload.client;

import com.warpload.ModConstants;
import com.warpload.cache.GlobalCache;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ModConstants.MOD_ID, value = Dist.CLIENT)
public class ClientJoinCachePersist {

    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        GlobalCache.persistOnce();
        com.warpload.compat.LdlCtmCache.persist();
    }
}
