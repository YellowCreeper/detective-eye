package com.orevision.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 客户端配置文件，生成在 config/orevision-client.toml。
 * 改完保存后游戏内会自动重载（Forge 客户端配置支持热重载）。
 */
public final class OreVisionConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue MAX_DISTANCE;
    public static final ForgeConfigSpec.IntValue MAX_ORES;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_HELMET;
    public static final ForgeConfigSpec.BooleanValue SHOW_HUD;
    public static final ForgeConfigSpec.BooleanValue DEBUG_HUD;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Detective Eye - client only settings").push("general");

        MAX_DISTANCE = builder
                .comment("扫描并高亮的半径（方块）。默认 16，游戏内用 [ 和 ] 可以临时加减。")
                .defineInRange("maxDistance", 16.0D, 4.0D, 128.0D);

        MAX_ORES = builder
                .comment("所有追踪目标合计最多显示多少个方块，超出时只保留离你最近的。"
                        + "这个数字是总上限，不是每种方块各占一份。")
                .defineInRange("maxOres", 300, 8, 4000);

        REQUIRE_HELMET = builder
                .comment("开启时只有戴着任意头盔才会显示高亮（替代原模组'必须戴眼镜'的设定）。")
                .define("requireHelmet", false);

        SHOW_HUD = builder
                .comment("在屏幕左上角显示每种目标的命中数量。")
                .define("showHud", true);

        DEBUG_HUD = builder
                .comment("额外显示扫描耗时、轮廓边数等调参用的读数。")
                .define("debugHud", false);

        builder.pop();
        SPEC = builder.build();
    }

    private OreVisionConfig() {
    }
}
