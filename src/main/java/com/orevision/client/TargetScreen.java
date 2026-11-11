package com.orevision.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 追踪方块选择界面：分类标签 + 搜索 + 图标网格 + 已选列表。
 *
 * <p>左边默认只有原版方块（约 800 个）。右上角的开关会把模组方块也载入（后台构建，
 * 整合包里通常有几千到几万个），载入后多出一个"模组"标签页专门筛这部分。</p>
 *
 * <p>左键勾选或取消，右键给已选方块换颜色；右边列出已选项和它当前的框线颜色，
 * 点名字会跳到列表里那一项。</p>
 */
public class TargetScreen extends Screen {
    private static final int MARGIN = 16;
    private static final int CELL = 24;
    private static final int RIGHT_W = 150;
    private static final int TITLE_Y = 6;
    private static final int TAB_Y = 20;
    private static final int TAB_H = 14;
    private static final int SEARCH_Y = 38;
    private static final int SEARCH_H = 16;
    private static final int TOP = 60;
    private static final int BOTTOM = 40;
    private static final int ENTRY_H = 22;
    /** 右侧"最近"区域的图标格子边长。 */
    private static final int RECENT_CELL = 18;
    /** 搜索结果上限，避免在几万个方块里一次渲染太多行。 */
    private static final int SEARCH_RESULT_CAP = 6000;

    private static final Map<ResourceLocation, ItemStack> STACKS = new HashMap<>();

    private enum Tab {
        ALL("screen.orevision.tab.all", -1),
        ORE("screen.orevision.tab.ore", 0),
        BUILDING("screen.orevision.tab.building", 1),
        PLANT("screen.orevision.tab.plant", 2),
        REDSTONE("screen.orevision.tab.redstone", 3),
        MODDED("screen.orevision.tab.modded", -2),
        SELECTED("screen.orevision.tab.selected", -3);

        private final String key;
        private final int category;

        Tab(String key, int category) {
            this.key = key;
            this.category = category;
        }

        private Component label() {
            return Component.translatable(key);
        }
    }

    private final List<ResourceLocation> filtered = new ArrayList<>();
    private EditBox searchBox;
    private Tab tab = Tab.ALL;
    private int scrollRow;
    /** 上一帧的构建状态，用来发现"刚好建完"并刷新列表。 */
    private boolean wasBuilding;

    public TargetScreen() {
        super(Component.translatable("screen.orevision.targets.title"));
    }

    @Override
    protected void init() {
        int gridLeft = gridLeft();
        int gridRight = gridRight();

        searchBox = new EditBox(this.font, gridLeft, SEARCH_Y, gridRight - gridLeft, SEARCH_H,
                Component.translatable("screen.orevision.search"));
        searchBox.setMaxLength(40);
        searchBox.setResponder(text -> {
            scrollRow = 0;
            refreshFilter(text);
        });
        addRenderableWidget(searchBox);

        addRenderableWidget(Button.builder(Component.translatable("screen.orevision.clear"), b -> {
            TargetStore.clear();
            OreOverlay.requestRescan();
            refreshFilter(searchBox.getValue());
        }).bounds(gridLeft, this.height - 30, 70, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.orevision.done"), b -> this.onClose())
                .bounds(gridRight - 70, this.height - 30, 70, 20).build());

        // 上次开着模组方块但没有走完构建（比如中途退出游戏），进来就继续建
        if (TargetStore.includeModded() && !BlockIndex.hasFull() && !BlockIndex.building()) {
            BlockIndex.startFullBuild();
        }
        wasBuilding = BlockIndex.building();

        if (filtered.isEmpty()) {
            refreshFilter(searchBox.getValue());
        }
    }

    private int gridLeft() {
        return MARGIN;
    }

    private int gridRight() {
        return Math.max(gridLeft() + CELL * 4, this.width - MARGIN - RIGHT_W - 12);
    }

    private int columns() {
        return Math.max(1, (gridRight() - gridLeft()) / CELL);
    }

    private int rowsVisible() {
        return Math.max(1, (this.height - BOTTOM - TOP) / CELL);
    }

    private int maxScrollRow() {
        int rows = (filtered.size() + columns() - 1) / columns();
        return Math.max(0, rows - rowsVisible());
    }

    /** 已选列表的行高：目标多的时候自动压扁，保证 16 个也放得下。 */
    private int entryH() {
        int available = this.height - BOTTOM - (SEARCH_Y + 20) - 90;
        return Mth.clamp(available / TargetStore.MAX_TARGETS, 12, ENTRY_H);
    }

    private void refreshFilter(String text) {
        filtered.clear();
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);

        List<ResourceLocation> source;
        if (tab == Tab.SELECTED) {
            source = new ArrayList<>();
            for (TargetStore.Target target : TargetStore.targets()) {
                source.add(target.id());
            }
        } else {
            source = BlockIndex.current();
        }

        for (ResourceLocation id : source) {
            if (tab == Tab.MODDED && !BlockIndex.isModded(id)) {
                continue;
            }
            if (tab.category >= 0 && BlockIndex.categoryOf(id) != tab.category) {
                continue;
            }
            if (!needle.isEmpty() && !matches(id, needle)) {
                continue;
            }
            filtered.add(id);
            if (filtered.size() >= SEARCH_RESULT_CAP) {
                break;
            }
        }
        scrollRow = Mth.clamp(scrollRow, 0, maxScrollRow());
    }

    private static boolean matches(ResourceLocation id, String needle) {
        if (BlockIndex.nameLower(id).contains(needle)) {
            return true;
        }
        if (BlockIndex.pinyinOf(id).contains(needle)) {
            return true;
        }
        return id.getPath().contains(needle) || id.getNamespace().contains(needle);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // 后台构建刚好结束 → 换成全量列表
        if (wasBuilding && !BlockIndex.building()) {
            wasBuilding = false;
            refreshFilter(searchBox.getValue());
        } else if (!wasBuilding && BlockIndex.building()) {
            wasBuilding = true;
        }

        renderBackground(gg);
        gg.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);
        renderTabs(gg, mouseX, mouseY);

        ResourceLocation hovered = null;

        int cols = columns();
        int rows = rowsVisible();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int index = (scrollRow + row) * cols + col;
                if (index >= filtered.size()) {
                    break;
                }
                ResourceLocation id = filtered.get(index);
                int x = gridLeft() + col * CELL;
                int y = TOP + row * CELL;
                boolean inside = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
                if (inside) {
                    hovered = id;
                    gg.fill(x, y, x + CELL, y + CELL, 0x40FFFFFF);
                }
                Block block = ForgeRegistries.BLOCKS.getValue(id);
                if (block != null) {
                    drawBlockIcon(gg, block, x + 4, y + 4);
                }
                TargetStore.Target target = TargetStore.find(id);
                if (target != null) {
                    gg.renderOutline(x, y, CELL, CELL, 0xFF000000 | target.color());
                }
            }
        }

        if (filtered.isEmpty()) {
            gg.drawString(this.font, Component.translatable(emptyKey()),
                    gridLeft() + 4, TOP + 4, 0xAAAAAA);
        }

        renderSelectedPanel(gg, mouseX, mouseY);

        if (hovered != null) {
            Block block = ForgeRegistries.BLOCKS.getValue(hovered);
            if (block != null) {
                gg.renderTooltip(this.font, Component.literal(BlockIndex.nameOf(hovered)), mouseX, mouseY);
            }
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    private String emptyKey() {
        if (tab == Tab.MODDED && !TargetStore.includeModded()) {
            return "screen.orevision.modded.hint";
        }
        return "screen.orevision.nomatch";
    }

    private void renderTabs(GuiGraphics gg, int mouseX, int mouseY) {
        int x = gridLeft();
        for (Tab candidate : Tab.values()) {
            if (candidate == Tab.MODDED && !TargetStore.includeModded() && !BlockIndex.building()) {
                continue;
            }
            Component label = candidate.label();
            int w = this.font.width(label) + 10;
            boolean active = candidate == tab;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= TAB_Y && mouseY < TAB_Y + TAB_H;
            int background = active ? 0xFF2F5FBF : (hover ? 0x60FFFFFF : 0x40000000);
            gg.fill(x, TAB_Y, x + w, TAB_Y + TAB_H, background);
            gg.drawString(this.font, label, x + 5, TAB_Y + 3, active ? 0xFFFFFF : 0xBBBBBB, false);
            x += w + 3;
        }

        Component toggle = toggleLabel();
        int toggleW = this.font.width(toggle) + 10;
        int toggleX = Math.max(x + 4, gridRight() - toggleW);
        boolean hover = mouseX >= toggleX && mouseX < toggleX + toggleW
                && mouseY >= TAB_Y && mouseY < TAB_Y + TAB_H;
        boolean on = TargetStore.includeModded() || BlockIndex.building();
        int background = on ? 0xFF2F7F4F : (hover ? 0x60FFFFFF : 0x40000000);
        gg.fill(toggleX, TAB_Y, toggleX + toggleW, TAB_Y + TAB_H, background);
        gg.drawString(this.font, toggle, toggleX + 5, TAB_Y + 3, on ? 0xFFFFFF : 0xBBBBBB, false);
    }

    private Component toggleLabel() {
        if (BlockIndex.building()) {
            return Component.translatable("screen.orevision.modded.building", BlockIndex.progress());
        }
        if (BlockIndex.failure() != null) {
            return Component.translatable("screen.orevision.modded.failed");
        }
        if (TargetStore.includeModded()) {
            return Component.translatable("screen.orevision.modded.on", BlockIndex.countLabel());
        }
        return Component.translatable("screen.orevision.modded.off");
    }

    private int toggleX() {
        int x = gridLeft();
        for (Tab candidate : Tab.values()) {
            if (candidate == Tab.MODDED && !TargetStore.includeModded() && !BlockIndex.building()) {
                continue;
            }
            x += this.font.width(candidate.label()) + 10 + 3;
        }
        int w = this.font.width(toggleLabel()) + 10;
        return Math.max(x + 4, gridRight() - w);
    }

    private void renderSelectedPanel(GuiGraphics gg, int mouseX, int mouseY) {
        int x = this.width - MARGIN - RIGHT_W;
        gg.fill(x - 6, SEARCH_Y, x + RIGHT_W, this.height - BOTTOM, 0x60000000);

        List<TargetStore.Target> targets = TargetStore.targets();
        gg.drawString(this.font, Component.translatable("screen.orevision.selected", targets.size()),
                x, SEARCH_Y + 4, 0xFFFFFF);

        int rowH = entryH();
        if (targets.isEmpty()) {
            gg.drawString(this.font, Component.translatable("screen.orevision.empty"), x, SEARCH_Y + 18, 0xAAAAAA);
        } else {
            for (int i = 0; i < targets.size(); i++) {
                TargetStore.Target target = targets.get(i);
                int y = SEARCH_Y + 20 + i * rowH;
                if (y + 12 > this.height - BOTTOM) {
                    break;
                }

                // 颜色块：点它换色
                gg.fill(x, y, x + 12, y + 12, 0xFF000000 | target.color());
                gg.renderOutline(x, y, 12, 12, 0xFF000000);

                // 名字：点它跳到列表里
                String name = this.font.plainSubstrByWidth(BlockIndex.nameOf(target.id()), RIGHT_W - 26);
                gg.drawString(this.font, name, x + 16, y + 2, 0xFFFFFF);

                // 删除按钮
                gg.drawString(this.font, "\u2715", x + RIGHT_W - 10, y + 2, 0xFFFF8080);
            }
        }

        renderRecent(gg, x, recentTop(targets), mouseX, mouseY);

        gg.drawString(this.font, Component.translatable("screen.orevision.hint"),
                x, this.height - BOTTOM + 4, 0x888888);
    }

    /** "最近"区域顶端的 y（紧跟已选列表）。 */
    private int recentTop(List<TargetStore.Target> targets) {
        return SEARCH_Y + 20 + Math.max(1, targets.size()) * entryH() + 8;
    }

    private int recentColumns() {
        return Math.max(1, RIGHT_W / RECENT_CELL);
    }

    private int recentRows(int top) {
        int available = this.height - BOTTOM - 4 - (top + 12);
        return Mth.clamp(available / RECENT_CELL, 0, 3);
    }

    private void renderRecent(GuiGraphics gg, int x, int top, int mouseX, int mouseY) {
        int rows = recentRows(top);
        if (rows <= 0) {
            return;
        }
        gg.drawString(this.font, Component.translatable("screen.orevision.recent.title"), x, top, 0xFFFFFF);

        List<ResourceLocation> recent = TargetStore.recent();
        if (recent.isEmpty()) {
            gg.drawString(this.font, Component.translatable("screen.orevision.recent.empty"), x, top + 12, 0x888888);
            return;
        }

        int columns = recentColumns();
        int count = Math.min(recent.size(), columns * rows);
        for (int i = 0; i < count; i++) {
            int cellX = x + (i % columns) * RECENT_CELL;
            int cellY = top + 12 + (i / columns) * RECENT_CELL;
            boolean hover = mouseX >= cellX && mouseX < cellX + RECENT_CELL
                    && mouseY >= cellY && mouseY < cellY + RECENT_CELL;
            if (hover) {
                gg.fill(cellX, cellY, cellX + RECENT_CELL, cellY + RECENT_CELL, 0x40FFFFFF);
            }
            ResourceLocation id = recent.get(i);
            Block block = ForgeRegistries.BLOCKS.getValue(id);
            if (block != null) {
                drawBlockIcon(gg, block, cellX + 1, cellY + 1);
            }
            TargetStore.Target target = TargetStore.find(id);
            if (target != null) {
                gg.renderOutline(cellX, cellY, RECENT_CELL, RECENT_CELL, 0xFF000000 | target.color());
            }
        }
    }

    private void drawBlockIcon(GuiGraphics gg, Block block, int x, int y) {
        ItemStack stack = stackOf(block);
        if (!stack.isEmpty()) {
            gg.renderItem(stack, x, y);
            return;
        }
        // 没有对应物品的方块（流体、技术方块）退化成模型粒子贴图
        TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer()
                .getBlockModelShaper().getParticleIcon(block.defaultBlockState());
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        gg.blit(x, y, 0, 16, 16, sprite);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // 右上角的"模组方块"开关
        int toggleX = toggleX();
        int toggleW = this.font.width(toggleLabel()) + 10;
        if (mouseX >= toggleX && mouseX < toggleX + toggleW && mouseY >= TAB_Y && mouseY < TAB_Y + TAB_H) {
            toggleModdedBlocks();
            return true;
        }

        int x = gridLeft();
        for (Tab candidate : Tab.values()) {
            if (candidate == Tab.MODDED && !TargetStore.includeModded() && !BlockIndex.building()) {
                continue;
            }
            int w = this.font.width(candidate.label()) + 10;
            if (mouseX >= x && mouseX < x + w && mouseY >= TAB_Y && mouseY < TAB_Y + TAB_H) {
                tab = candidate;
                refreshFilter(searchBox.getValue());
                scrollRow = 0;
                return true;
            }
            x += w + 3;
        }

        int cols = columns();
        if (mouseX >= gridLeft() && mouseX < gridRight() && mouseY >= TOP) {
            int col = (int) ((mouseX - gridLeft()) / CELL);
            int row = (int) ((mouseY - TOP) / CELL);
            int index = (scrollRow + row) * cols + col;
            if (col < cols && row < rowsVisible() && index >= 0 && index < filtered.size()) {
                ResourceLocation id = filtered.get(index);
                if (button == 1) {
                    TargetStore.cycleColor(id);
                } else {
                    if (!TargetStore.toggle(id)) {
                        limitMessage();
                    }
                }
                OreOverlay.requestRescan();
                if (tab == Tab.SELECTED) {
                    refreshFilter(searchBox.getValue());
                }
                return true;
            }
        }

        // 右侧已选列表：色块换色、名字定位、✕ 删除
        int panelX = this.width - MARGIN - RIGHT_W;
        int firstRowY = SEARCH_Y + 20;
        List<TargetStore.Target> targets = TargetStore.targets();
        int rowH = entryH();
        if (mouseX >= panelX && mouseX <= panelX + RIGHT_W && mouseY >= firstRowY) {
            int index = (int) ((mouseY - firstRowY) / rowH);
            if (index >= 0 && index < targets.size()) {
                ResourceLocation id = targets.get(index).id();
                if (mouseX >= panelX + RIGHT_W - 14) {
                    TargetStore.remove(id);
                    refreshFilter(searchBox.getValue());
                } else if (mouseX <= panelX + 14) {
                    TargetStore.cycleColor(id);
                } else {
                    focusOn(id);
                }
                OreOverlay.requestRescan();
                return true;
            }
        }

        // 右侧"最近"区域：点图标 = 加入/取消追踪
        int top = recentTop(targets);
        if (mouseX >= panelX && mouseX < panelX + RIGHT_W && mouseY >= top + 12) {
            int columns = recentColumns();
            int rows = recentRows(top);
            int col = (int) ((mouseX - panelX) / RECENT_CELL);
            int row = (int) ((mouseY - (top + 12)) / RECENT_CELL);
            int index = row * columns + col;
            List<ResourceLocation> recent = TargetStore.recent();
            if (col >= 0 && col < columns && row >= 0 && row < rows && index < recent.size()) {
                ResourceLocation id = recent.get(index);
                if (!TargetStore.toggle(id)) {
                    limitMessage();
                }
                OreOverlay.requestRescan();
                if (tab == Tab.SELECTED) {
                    refreshFilter(searchBox.getValue());
                }
                return true;
            }
        }
        return false;
    }

    /** 开关模组方块：打开就后台建索引，关闭就清掉已选的模组方块并释放索引。 */
    private void toggleModdedBlocks() {
        if (BlockIndex.building()) {
            return;
        }
        if (TargetStore.includeModded()) {
            TargetStore.setIncludeModded(false);
            int removed = TargetStore.removeModded();
            TargetStore.save();
            BlockIndex.dropFull();
            tab = Tab.ALL;
            refreshFilter(searchBox.getValue());
            OreOverlay.requestRescan();
            message(Component.translatable("message.orevision.modded.off", removed));
        } else {
            TargetStore.setIncludeModded(true);
            TargetStore.save();
            if (BlockIndex.hasFull()) {
                refreshFilter(searchBox.getValue());
                message(Component.translatable("message.orevision.modded.on", BlockIndex.countLabel()));
            } else {
                BlockIndex.startFullBuild();
                message(Component.translatable("message.orevision.modded.scanning"));
            }
        }
    }

    private void message(Component text) {
        Minecraft mc = this.minecraft;
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(text, true);
        }
    }

    private void limitMessage() {
        message(Component.translatable("message.orevision.limit", TargetStore.MAX_TARGETS));
    }

    /** 在右侧点某个已选项 → 切到"已选"标签并把它滚到看得见的位置。 */
    private void focusOn(ResourceLocation id) {
        tab = Tab.SELECTED;
        if (searchBox != null && !searchBox.getValue().isEmpty()) {
            searchBox.setValue("");
        }
        refreshFilter("");
        int index = filtered.indexOf(id);
        if (index >= 0) {
            scrollRow = Mth.clamp(index / columns(), 0, maxScrollRow());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (super.mouseScrolled(mouseX, mouseY, delta)) {
            return true;
        }
        if (mouseX >= gridLeft() && mouseX < gridRight() && mouseY >= TOP) {
            scrollRow = Mth.clamp(scrollRow - (int) Math.signum(delta), 0, maxScrollRow());
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        TargetStore.save();
        OreOverlay.requestRescan();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static ItemStack stackOf(Block block) {
        return STACKS.computeIfAbsent(block.builtInRegistryHolder().key().location(),
                key -> new ItemStack(block.asItem()));
    }
}
