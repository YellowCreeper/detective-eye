package com.orevision.client;

import com.orevision.OreVision;
import com.orevision.config.OreVisionConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

/**
 * 客户端初始化：注册配置文件和按键（都在 MOD 事件总线上）。
 */
@Mod.EventBusSubscriber(modid = OreVision.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class OreVisionClient {

    public static void init() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, OreVisionConfig.SPEC, "orevision-client.toml");
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OreKeys.TOGGLE);
        event.register(OreKeys.RANGE_DOWN);
        event.register(OreKeys.RANGE_UP);
        event.register(OreKeys.OPEN_UI);
    }

    private OreVisionClient() {
    }
}
