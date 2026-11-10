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
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 追踪方块选择界面：分类标签 + 搜索 + 图标网格 + 已选列表。
 *
 * <p>左边是原版全部方块（约 800 个，已排除空气/液体/技术方块），可以按分类筛选、滚动、搜索；
 * 左键勾选或取消，右键给已选方块换颜色；右边列出已选项和它当前的框线颜色，
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

    /** 这些方块没有"形状"或不适合追踪，直接不进列表。 */
    private static final Set<String> HIDDEN = new LinkedHashSet<>(List.of(
            "air", "cave_air", "void_air", "moving_piston", "piston_head", "bubble_column",
            "light", "structure_void", "fire", "soul_fire", "nether_portal", "end_portal", "end_gateway"));

    private static final String[] ORE_WORDS = {"_ore", "ancient_debris"};
    /** 没带 _ore 但明显属于矿物类的：原矿块、矿物压缩块、紫水晶。 */
    private static final Set<String> ORE_EXTRA = Set.of(
            "coal_block", "iron_block", "copper_block", "gold_block", "diamond_block", "emerald_block",
            "lapis_block", "redstone_block", "netherite_block", "quartz_block",
            "raw_iron_block", "raw_copper_block", "raw_gold_block",
            "amethyst_block", "budding_amethyst", "amethyst_cluster",
            "large_amethyst_bud", "medium_amethyst_bud", "small_amethyst_bud");
    private static final String[] PLANT_WORDS = {
            "leaves", "_log", "_wood", "planks", "sapling", "flower", "tulip", "grass", "fern",
            "wheat", "carrot", "potato", "beetroot", "melon", "pumpkin", "bamboo", "cactus", "vine",
            "mushroom", "roots", "kelp", "coral", "wart", "azalea", "moss", "lily", "sugar_cane",
            "dripleaf", "lichen", "sea_pickle", "chorus", "cocoa", "spore_blossom", "mangrove",
            "propagule", "hanging_roots", "pale_moss", "bush"};
    private static final String[] REDSTONE_WORDS = {
            "redstone", "piston", "observer", "dispenser", "dropper", "hopper", "repeater",
            "comparator", "_rail", "button", "lever", "pressure_plate", "tripwire", "daylight",
            "target_block", "note_block", "jukebox", "crafter", "sculk_sensor", "sculk_shrieker",
            "tnt", "_door", "_trapdoor", "fence_gate", "bell", "respawn_anchor", "beacon", "conduit",
            "lodestone", "lectern"};
    private static final String[] BUILDING_WORDS = {
            "stone", "brick", "concrete", "terracotta", "glass", "wool", "sandstone", "quartz",
            "copper", "deepslate", "slab", "stairs", "wall", "fence", "carpet", "glazed", "tuff",
            "mud", "basalt", "blackstone", "dripstone", "calcite", "andesite", "diorite", "granite",
            "prismarine", "purpur", "netherrack", "soul_sand", "soul_soil", "magma", "obsidian",
            "bedrock", "_block", "bone_block", "honey", "hay_block", "snow", "ice", "amethyst",
            "sculk", "pale_", "resin"};

    private static List<ResourceLocation> allBlocks;
    private static final Map<ResourceLocation, String> NAMES = new HashMap<>();
    /** 每个方块中文名的拼音搜索键（全拼变体 + 首字母变体），懒计算后缓存。 */
    private static final Map<ResourceLocation, String> PINYIN = new HashMap<>();
    private static final Map<ResourceLocation, ItemStack> STACKS = new HashMap<>();
    private static final Map<ResourceLocation, Integer> CATEGORIES = new HashMap<>();

    private enum Tab {
        ALL("screen.orevision.tab.all", -1),
        ORE("screen.orevision.tab.ore", 0),
        BUILDING("screen.orevision.tab.building", 1),
        PLANT("screen.orevision.tab.plant", 2),
        REDSTONE("screen.orevision.tab.redstone", 3),
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

    private void refreshFilter(String text) {
        filtered.clear();
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);

        List<ResourceLocation> source;
        switch (tab) {
            case SELECTED -> {
                source = new ArrayList<>();
                for (TargetStore.Target target : TargetStore.targets()) {
                    source.add(target.id());
                }
            }
            default -> source = allBlocks();
        }

        for (ResourceLocation id : source) {
            if (tab.category == 0 && categoryOf(id) != 0) {
                continue;
            }
            if (tab.category == 1 && categoryOf(id) != 1) {
                continue;
            }
            if (tab.category == 2 && categoryOf(id) != 2) {
                continue;
            }
            if (tab.category == 3 && categoryOf(id) != 3) {
                continue;
            }
            if (!needle.isEmpty()
                    && !nameOf(id).toLowerCase(Locale.ROOT).contains(needle)
                    && !pinyinOf(id).contains(needle)
                    && !id.toString().contains(needle)) {
                continue;
            }
            filtered.add(id);
        }
        scrollRow = Mth.clamp(scrollRow, 0, maxScrollRow());
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
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
            gg.drawString(this.font, Component.translatable("screen.orevision.nomatch"),
                    gridLeft() + 4, TOP + 4, 0xAAAAAA);
        }

        renderSelectedPanel(gg, mouseX, mouseY);

        if (hovered != null) {
            Block block = ForgeRegistries.BLOCKS.getValue(hovered);
            if (block != null) {
                gg.renderTooltip(this.font, block.getName(), mouseX, mouseY);
            }
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    private void renderTabs(GuiGraphics gg, int mouseX, int mouseY) {
        int x = gridLeft();
        for (Tab candidate : Tab.values()) {
            Component label = candidate.label();
            int w = this.font.width(label) + 10;
            boolean active = candidate == tab;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= TAB_Y && mouseY < TAB_Y + TAB_H;
            int background = active ? 0xFF2F5FBF : (hover ? 0x60FFFFFF : 0x40000000);
            gg.fill(x, TAB_Y, x + w, TAB_Y + TAB_H, background);
            gg.drawString(this.font, label, x + 5, TAB_Y + 3, active ? 0xFFFFFF : 0xBBBBBB, false);
            x += w + 3;
        }
    }

    private void renderSelectedPanel(GuiGraphics gg, int mouseX, int mouseY) {
        int x = this.width - MARGIN - RIGHT_W;
        gg.fill(x - 6, SEARCH_Y, x + RIGHT_W, this.height - BOTTOM, 0x60000000);

        List<TargetStore.Target> targets = TargetStore.targets();
        gg.drawString(this.font, Component.translatable("screen.orevision.selected", targets.size()),
                x, SEARCH_Y + 4, 0xFFFFFF);

        if (targets.isEmpty()) {
            gg.drawString(this.font, Component.translatable("screen.orevision.empty"), x, SEARCH_Y + 18, 0xAAAAAA);
        } else {
            for (int i = 0; i < targets.size(); i++) {
                TargetStore.Target target = targets.get(i);
                int y = SEARCH_Y + 20 + i * ENTRY_H;

                // 颜色块：点它换色
                gg.fill(x, y, x + 12, y + 12, 0xFF000000 | target.color());
                gg.renderOutline(x, y, 12, 12, 0xFF000000);

                // 名字：点它跳到列表里
                String name = this.font.plainSubstrByWidth(nameOf(target.id()), RIGHT_W - 26);
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
        return SEARCH_Y + 20 + Math.max(1, targets.size()) * ENTRY_H + 8;
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

        int x = gridLeft();
        for (Tab candidate : Tab.values()) {
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
                        if (this.minecraft != null && this.minecraft.player != null) {
                            this.minecraft.player.displayClientMessage(
                                    Component.translatable("message.orevision.limit", TargetStore.MAX_TARGETS), true);
                        }
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
        if (mouseX >= panelX && mouseX <= panelX + RIGHT_W && mouseY >= firstRowY) {
            int index = (int) ((mouseY - firstRowY) / ENTRY_H);
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
                if (!TargetStore.toggle(id) && this.minecraft != null && this.minecraft.player != null) {
                    this.minecraft.player.displayClientMessage(
                            Component.translatable("message.orevision.limit", TargetStore.MAX_TARGETS), true);
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

    /** 0 = 矿石，1 = 建筑，2 = 植物，3 = 红石/功能，4 = 其他。 */
    private static int categoryOf(ResourceLocation id) {
        Integer cached = CATEGORIES.get(id);
        if (cached != null) {
            return cached;
        }
        String path = id.getPath();
        int category = 4;
        if (containsAny(path, ORE_WORDS) || ORE_EXTRA.contains(path)) {
            category = 0;
        } else if (containsAny(path, PLANT_WORDS)) {
            category = 2;
        } else if (containsAny(path, REDSTONE_WORDS)) {
            category = 3;
        } else if (containsAny(path, BUILDING_WORDS)) {
            category = 1;
        }
        CATEGORIES.put(id, category);
        return category;
    }

    private static boolean containsAny(String path, String[] words) {
        for (String word : words) {
            if (path.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String nameOf(ResourceLocation id) {
        String cached = NAMES.get(id);
        if (cached != null) {
            return cached;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        String name = block == null ? id.toString() : block.getName().getString();
        NAMES.put(id, name);
        return name;
    }

    /** 拼音键：全拼和首字母的多个变体拼在一起，直接 contains 即可。 */
    private static String pinyinOf(ResourceLocation id) {
        return PINYIN.computeIfAbsent(id, key -> Pinyin.key(nameOf(key)));
    }

    private static ItemStack stackOf(Block block) {
        return STACKS.computeIfAbsent(block.builtInRegistryHolder().key().location(),
                key -> new ItemStack(block.asItem()));
    }

    /** 原版全部方块，按本地化名字排序；只取一次。 */
    private static List<ResourceLocation> allBlocks() {
        if (allBlocks == null) {
            List<ResourceLocation> list = new ArrayList<>();
            for (Block block : ForgeRegistries.BLOCKS.getValues()) {
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
                if (id == null || !"minecraft".equals(id.getNamespace())) {
                    continue;
                }
                if (HIDDEN.contains(id.getPath()) || block instanceof LiquidBlock) {
                    continue;
                }
                list.add(id);
            }
            list.sort(Comparator.comparing(TargetScreen::nameOf));
            allBlocks = list;
        }
        return allBlocks;
    }
}
