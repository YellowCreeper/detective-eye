package com.orevision;

import com.orevision.client.OreVisionClient;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(OreVision.MODID)
public class OreVision {
    public static final String MODID = "orevision";
    public static final Logger LOGGER = LogUtils.getLogger();

    public OreVision() {
        LOGGER.info("[orevision] mod constructed, loading client hooks");
        // 纯客户端：只有 dist 是 CLIENT 时才初始化客户端代码。
        // 这样即使有人把这个 jar 丢进专用服务端，也只会加载到主类，不会因为没有 client 类而崩。
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> OreVisionClient::init);
    }
}
