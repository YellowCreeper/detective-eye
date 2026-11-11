package com.orevision.client;

import com.orevision.OreVision;
import com.orevision.config.OreVisionConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端初始化：注册配置文件和按键（都在 MOD 事件总线上）。
 */
@Mod.EventBusSubscriber(modid = OreVision.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class OreVisionClient {

    public static void init() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, OreVisionConfig.SPEC, "orevision-client.toml");
    }

    /**
     * 开发期自检：加 {@code -Dorevision.selftest=true} 启动时会在后台把全量方块索引建一遍并打日志，
     * 用来验证整合包环境下索引构建不崩、耗时可接受。正常游玩不会触发。
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (!Boolean.getBoolean("orevision.selftest")) {
            return;
        }
        OreVision.LOGGER.info("[orevision] selftest: building full block index");
        long start = System.currentTimeMillis();
        BlockIndex.startFullBuild();
        Thread watcher = new Thread(() -> {
            while (BlockIndex.building()) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            OreVision.LOGGER.info("[orevision] selftest: index ready in {} ms, total {} blocks ({} modded)",
                    System.currentTimeMillis() - start, BlockIndex.countLabel(), BlockIndex.moddedCount());
        }, "orevision-selftest");
        watcher.setDaemon(true);
        watcher.start();
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
