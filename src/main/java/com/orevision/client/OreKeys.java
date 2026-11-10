package com.orevision.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class OreKeys {
    public static final String CATEGORY = "key.categories.orevision";

    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.orevision.toggle",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            CATEGORY
    );

    /** 缩短扫描半径（每次 4 格），用于游戏内随手调。 */
    public static final KeyMapping RANGE_DOWN = new KeyMapping(
            "key.orevision.range_down",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_BRACKET,
            CATEGORY
    );

    /** 增大扫描半径。 */
    public static final KeyMapping RANGE_UP = new KeyMapping(
            "key.orevision.range_up",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_BRACKET,
            CATEGORY
    );

    /** 打开追踪方块选择界面（小键盘 3）。 */
    public static final KeyMapping OPEN_UI = new KeyMapping(
            "key.orevision.open_ui",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_3,
            CATEGORY
    );

    private OreKeys() {
    }
}
