package com.orevision.client;

import com.orevision.OreVision;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块索引：选择界面里能搜到的方块全集，以及名字 / 拼音 / 分类的缓存。
 *
 * <p>原版方块（约 800 个）在第一次打开界面时同步建好，代价是一次注册表遍历加排序。
 * 模组方块动辄几万个，构建放在后台线程，界面用 {@link #progress()} 显示进度；
 * 构建期间界面继续用原版列表，玩家随时可以关掉。</p>
 */
public final class BlockIndex {

    /** 这些方块没有"形状"或不适合追踪，直接不进列表。 */
    private static final Set<String> HIDDEN = Set.of(
            "air", "cave_air", "void_air", "moving_piston", "piston_head", "bubble_column",
            "light", "structure_void", "fire", "soul_fire", "nether_portal", "end_portal", "end_gateway");

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

    /** 硬上限，防止极端整合包把内存吃穿（正常 500 mods 大约 2–4 万）。 */
    private static final int HARD_CAP = 80000;

    private static final Map<ResourceLocation, String> NAMES = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, String> LOWER = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, String> PINYIN = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Integer> CATEGORIES = new ConcurrentHashMap<>();

    /** 只含 minecraft 命名空间的方块。 */
    private static volatile List<ResourceLocation> vanilla;
    /** 含全部命名空间的方块；null 表示还没构建过。 */
    private static volatile List<ResourceLocation> full;

    private static volatile boolean building;
    private static volatile int progress;
    private static volatile int moddedCount;
    private static volatile String failure;
    /** 每次开始构建 +1，用来丢弃过期的构建结果。 */
    private static volatile int generation;

    private BlockIndex() {
    }

    /** 当前该给界面看的列表：开了模组方块且已建好就用全量，否则原版。 */
    public static List<ResourceLocation> current() {
        List<ResourceLocation> all = full;
        return TargetStore.includeModded() && all != null ? all : vanilla();
    }

    public static List<ResourceLocation> vanilla() {
        List<ResourceLocation> list = vanilla;
        if (list == null) {
            list = build(true);
            vanilla = list;
        }
        return list;
    }

    public static boolean building() {
        return building;
    }

    /** 0–100。 */
    public static int progress() {
        return progress;
    }

    /** 上一轮构建出来的模组方块数量（不含原版）。 */
    public static int moddedCount() {
        return moddedCount;
    }

    public static String failure() {
        return failure;
    }

    /** 已经建好过全量列表。 */
    public static boolean hasFull() {
        return full != null;
    }

    /** 界面上显示的数量，例如 "23,481"。 */
    public static String countLabel() {
        List<ResourceLocation> all = full;
        int size = all != null ? all.size() : vanilla().size();
        return String.format(Locale.ROOT, "%,d", size);
    }

    /**
     * 后台构建全量列表。重复调用是安全的，正在跑就直接返回。
     */
    public static void startFullBuild() {
        if (building) {
            return;
        }
        building = true;
        progress = 0;
        failure = null;
        final int gen = ++generation;
        Thread thread = new Thread(() -> {
            try {
                List<ResourceLocation> list = build(false);
                if (gen == generation) {
                    full = list;
                    progress = 100;
                }
            } catch (Throwable t) {
                OreVision.LOGGER.warn("[orevision] failed to build block index", t);
                if (gen == generation) {
                    failure = t.getClass().getSimpleName();
                }
            } finally {
                if (gen == generation) {
                    building = false;
                }
            }
        }, "orevision-block-index");
        thread.setDaemon(true);
        thread.start();
    }

    /** 丢弃全量列表（关掉模组方块时调用，释放几万个字符串占用的内存）。 */
    public static void dropFull() {
        generation++;
        building = false;
        progress = 0;
        full = null;
    }

    public static boolean isModded(ResourceLocation id) {
        return !"minecraft".equals(id.getNamespace());
    }

    public static String nameOf(ResourceLocation id) {
        String cached = NAMES.get(id);
        if (cached != null) {
            return cached;
        }
        String name = computeName(id);
        NAMES.put(id, name);
        return name;
    }

    /** 小写名字，搜索热路径用：每次按键要比较几万条，不能每条都 toLowerCase。 */
    public static String nameLower(ResourceLocation id) {
        return LOWER.computeIfAbsent(id, key -> nameOf(key).toLowerCase(Locale.ROOT));
    }

    /** 拼音键：全拼和首字母的多个变体拼在一起，直接 contains 即可。 */
    public static String pinyinOf(ResourceLocation id) {
        return PINYIN.computeIfAbsent(id, key -> Pinyin.key(nameOf(key)));
    }

    /** 0 = 矿石，1 = 建筑，2 = 植物，3 = 红石/功能，4 = 其他。 */
    public static int categoryOf(ResourceLocation id) {
        Integer cached = CATEGORIES.get(id);
        if (cached != null) {
            return cached;
        }
        int category = computeCategory(id);
        CATEGORIES.put(id, category);
        return category;
    }

    private static List<ResourceLocation> build(boolean onlyVanilla) {
        List<Block> blocks = new ArrayList<>(ForgeRegistries.BLOCKS.getValues());
        int total = Math.max(1, blocks.size());
        List<ResourceLocation> list = new ArrayList<>(total);
        int modded = 0;
        int processed = 0;
        for (Block block : blocks) {
            processed++;
            if (!onlyVanilla && processed % 256 == 0) {
                progress = Math.min(99, processed * 100 / total);
            }
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            if (id == null || HIDDEN.contains(id.getPath()) || block instanceof LiquidBlock) {
                continue;
            }
            if (onlyVanilla && !"minecraft".equals(id.getNamespace())) {
                continue;
            }
            if (list.size() >= HARD_CAP) {
                break;
            }
            // 预热名字/拼音/分类，搜索时不再现算
            nameOf(id);
            nameLower(id);
            pinyinOf(id);
            categoryOf(id);
            if (!"minecraft".equals(id.getNamespace())) {
                modded++;
            }
            list.add(id);
        }
        if (!onlyVanilla) {
            moddedCount = modded;
        }
        list.sort(Comparator.comparing(BlockIndex::nameOf));
        OreVision.LOGGER.info("[orevision] block index built: {} entries ({} modded){}",
                list.size(), onlyVanilla ? 0 : modded, onlyVanilla ? "" : " [modded enabled]");
        return list;
    }

    private static String computeName(ResourceLocation id) {
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        if (block == null) {
            return id.toString();
        }
        String name = block.getName().getString();
        // 模组方块没做当前语言的高清化时，getString() 会直接返回 block.modid.path
        if (name.startsWith("block.") || name.startsWith("item.") || name.equals(id.toString())) {
            return humanize(id.getPath()) + " (" + id.getNamespace() + ")";
        }
        return name;
    }

    /** raw_titanium_ore → Raw Titanium Ore。 */
    private static String humanize(String path) {
        String[] parts = path.split("_");
        StringBuilder out = new StringBuilder(path.length());
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part, 1, part.length());
            }
        }
        return out.length() == 0 ? path : out.toString();
    }

    private static int computeCategory(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        if (block != null) {
            try {
                if (block.defaultBlockState().is(Tags.Blocks.ORES)) {
                    return 0;
                }
            } catch (Throwable ignored) {
                // 标签还没加载（极少见），退回关键词判断
            }
        }
        if (containsAny(path, ORE_WORDS) || ORE_EXTRA.contains(path)) {
            return 0;
        }
        if (containsAny(path, PLANT_WORDS)) {
            return 2;
        }
        if (containsAny(path, REDSTONE_WORDS)) {
            return 3;
        }
        if (containsAny(path, BUILDING_WORDS)) {
            return 1;
        }
        return 4;
    }

    private static boolean containsAny(String path, String[] words) {
        for (String word : words) {
            if (path.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
