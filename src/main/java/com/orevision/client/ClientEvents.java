package com.orevision.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.orevision.config.OreVisionConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 事件入口：按键轮询、扫描节拍、世界内绘制。
 *
 * <p>绘制不用 GL 线（{@code Mode.LINES} + 线宽着色器）：
 * 1.20.1 的 rendertype_lines 靠"顶点法线 + LineWidth uniform"在屏幕空间撑开线段，
 * uniform 没设成期望值时线段会错位、角和角对不上，看上去就是断的/歪的。
 * 这里改成用普通 QUADS 直接画"细长方体"当描边，粗细是真实世界尺寸，
 * 由我们自己算，和显卡驱动、线宽支持度都无关。</p>
 */
@Mod.EventBusSubscriber(modid = "orevision", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {

    /** 描边基础厚度（方块），远处会按距离加粗，避免远看消失。 */
    private static final float BEAM_BASE = 0.018F;
    private static final float BEAM_PER_BLOCK = 0.0016F;
    private static final float BEAM_MAX = 0.11F;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        OreOverlay.tick(mc.level, mc.player);
    }

    /**
     * 热键走事件而不是每 tick 轮询。
     *
     * <p>{@code InputEvent.Key} 是 GLFW 键盘回调转发的真实按键事件，只有真的按下时才会来；
     * 用 {@code KeyMapping.matches} 判断而不是硬编码按键码，玩家改键后依然有效。</p>
     */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        // GLFW_PRESS = 1；按住产生的 GLFW_REPEAT(2) 和松开(0) 都不算一次触发
        if (event.getAction() != GLFW.GLFW_PRESS || !canUseHotkeys()) {
            return;
        }
        int key = event.getKey();
        int scan = event.getScanCode();
        if (OreKeys.OPEN_UI.matches(key, scan)) {
            Minecraft.getInstance().setScreen(new TargetScreen());
        } else if (OreKeys.TOGGLE.matches(key, scan)) {
            OreOverlay.toggle();
        } else if (OreKeys.RANGE_DOWN.matches(key, scan)) {
            OreOverlay.adjustDistance(-4);
        } else if (OreKeys.RANGE_UP.matches(key, scan)) {
            OreOverlay.adjustDistance(4);
        }
    }

    /** 鼠标键绑定的补口：InputEvent.Key 只覆盖键盘。 */
    @SubscribeEvent
    public static void onMouseInput(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != GLFW.GLFW_PRESS || !canUseHotkeys()) {
            return;
        }
        int button = event.getButton();
        if (OreKeys.TOGGLE.matchesMouse(button)) {
            OreOverlay.toggle();
        } else if (OreKeys.RANGE_DOWN.matchesMouse(button)) {
            OreOverlay.adjustDistance(-4);
        } else if (OreKeys.RANGE_UP.matchesMouse(button)) {
            OreOverlay.adjustDistance(4);
        }
    }

    /**
     * 只有"正在游戏里、没有任何界面、鼠标已锁定"时才算热键。
     *
     * <p>{@code InputEvent} 是底层回调，开背包 / 聊天框 / 暂停菜单时照样会收到，
     * 不拦就会在打字时误触发。{@code KeyConflictContext.IN_GAME} 在这里不起作用
     * （{@code matches()} 不查上下文），所以这层判断是必需的。</p>
     */
    private static boolean canUseHotkeys() {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen == null && mc.level != null && mc.player != null && mc.mouseHandler.isMouseGrabbed();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!OreOverlay.enabled()) {
            return;
        }
        Map<ResourceLocation, List<OreOverlay.Seg>> hulls = OreOverlay.hulls();
        if (hulls.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();

        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = poseStack.last().pose();

        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (List<OreOverlay.Seg> segments : hulls.values()) {
            drawSegments(builder, matrix, cam, segments);
        }
        BufferUploader.drawWithShader(builder.end());
        poseStack.popPose();

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /**
     * 左上角的计数面板：每种目标现在框住多少个、扫到过多少个。
     *
     * <p>挂在原版快捷栏渲染之后画，这样它会跟着游戏界面一起被 F1 隐藏，
     * 也不会被动作条消息盖住。</p>
     */
    @SubscribeEvent
    public static void onRenderHud(RenderGuiOverlayEvent.Post event) {
        if (!VanillaGuiOverlay.HOTBAR.id().equals(event.getOverlay().id())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!OreOverlay.enabled() || mc.player == null || mc.options.hideGui) {
            return;
        }
        if (!OreVisionConfig.SHOW_HUD.get()) {
            return;
        }
        List<OreOverlay.Stat> stats = OreOverlay.stats();
        if (stats.isEmpty()) {
            return;
        }

        GuiGraphics gg = event.getGuiGraphics();
        int x = 6;
        int y = 6;
        for (OreOverlay.Stat stat : stats) {
            String line = "\u25C6 " + stat.name() + " x" + stat.shown();
            if (stat.matched() > stat.shown()) {
                line = line + " (+" + (stat.matched() - stat.shown()) + ")";
            }
            gg.drawString(mc.font, line, x, y, 0xFF000000 | stat.color(), true);
            y += 10;
        }
        if (OreVisionConfig.DEBUG_HUD.get()) {
            String debug = String.format(Locale.ROOT, "scan %dms | edges %d | shown %d/%d",
                    OreOverlay.lastScanMillis(), OreOverlay.edgeTotal(),
                    OreOverlay.entries().size(), OreOverlay.matchedTotal());
            gg.drawString(mc.font, debug, x, y + 2, 0xFFAAAAAA, true);
        }
    }

    /** 把一组轮廓边按当前相机距离算粗细后画成细长方体。 */
    private static void drawSegments(BufferBuilder builder, Matrix4f matrix, Vec3 cam,
                                     List<OreOverlay.Seg> segments) {
        for (OreOverlay.Seg seg : segments) {
            int color = seg.color();
            float r = ((color >> 16) & 0xFF) / 255.0F;
            float g = ((color >> 8) & 0xFF) / 255.0F;
            float b = (color & 0xFF) / 255.0F;

            double mx = (seg.x1() + seg.x2()) * 0.5D - cam.x;
            double my = (seg.y1() + seg.y2()) * 0.5D - cam.y;
            double mz = (seg.z1() + seg.z2()) * 0.5D - cam.z;
            float dist = (float) Math.sqrt(mx * mx + my * my + mz * mz);
            float t = Math.min(BEAM_MAX, BEAM_BASE + BEAM_PER_BLOCK * dist);

            float x0 = (float) Math.min(seg.x1(), seg.x2()) - t;
            float y0 = (float) Math.min(seg.y1(), seg.y2()) - t;
            float z0 = (float) Math.min(seg.z1(), seg.z2()) - t;
            float x1 = (float) Math.max(seg.x1(), seg.x2()) + t;
            float y1 = (float) Math.max(seg.y1(), seg.y2()) + t;
            float z1 = (float) Math.max(seg.z1(), seg.z2()) + t;

            emitBox(builder, matrix, x0, y0, z0, x1, y1, z1, r, g, b);
        }
    }

    /** 六个面画一个长方体（关闭剔除，正反面都会画到）。 */
    private static void emitBox(BufferBuilder b, Matrix4f m,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float r, float g, float bl) {
        // 底面 y0 / 顶面 y1
        quad(b, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, bl);
        quad(b, m, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, bl);
        // 北面 z0 / 南面 z1
        quad(b, m, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, bl);
        quad(b, m, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, bl);
        // 西面 x0 / 东面 x1
        quad(b, m, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, bl);
        quad(b, m, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, bl);
    }

    private static void quad(BufferBuilder b, Matrix4f m,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz,
                             float r, float g, float bl) {
        b.vertex(m, ax, ay, az).color(r, g, bl, 1.0F).endVertex();
        b.vertex(m, bx, by, bz).color(r, g, bl, 1.0F).endVertex();
        b.vertex(m, cx, cy, cz).color(r, g, bl, 1.0F).endVertex();
        b.vertex(m, dx, dy, dz).color(r, g, bl, 1.0F).endVertex();
    }

    private ClientEvents() {
    }
}
