package com.orevision.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.orevision.OreVision;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 追踪目标列表：玩家在界面里勾选的方块 + 各自的框线颜色。
 *
 * <p>存在 {@code config/orevision-targets.json}，和 Forge 的 toml 配置分开，
 * 因为这里要频繁地增删改，用 JSON 自己读写更直接。</p>
 */
public final class TargetStore {
    /** 上限 8：再多颜色就分不清了。 */
    public static final int MAX_TARGETS = 8;

    /** 预设调色板，顺序即"自动分配"的优先级，8 色对应 8 个上限。 */
    public static final int[] PALETTE = {
            0x4AEDD9, // 青
            0xFF9F1C, // 橙
            0xFF4FD8, // 品红
            0xFFE14D, // 黄
            0x5CE65C, // 绿
            0xB37BFF, // 紫
            0x4D9BFF, // 蓝
            0xFF5A5A  // 红
    };

    public record Target(ResourceLocation id, int color) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Target> TARGETS = new ArrayList<>();
    /** 最近选过的方块，界面里"最近"标签直接用它。 */
    private static final List<ResourceLocation> RECENT = new ArrayList<>();
    private static final int RECENT_MAX = 24;
    private static final Map<Block, Target> LOOKUP = new HashMap<>();
    private static boolean loaded;
    /** 每次改动 +1，扫描器靠它发现自己需要重扫。 */
    private static int version;

    public static List<Target> targets() {
        load();
        return TARGETS;
    }

    /** 最近选过的方块，新的在前。 */
    public static List<ResourceLocation> recent() {
        load();
        return RECENT;
    }

    /** 记录一次选择，供"最近"标签使用。 */
    public static void pushRecent(ResourceLocation id) {
        RECENT.remove(id);
        RECENT.add(0, id);
        while (RECENT.size() > RECENT_MAX) {
            RECENT.remove(RECENT.size() - 1);
        }
    }

    public static int version() {
        return version;
    }

    /** 扫描热路径用：方块 → 目标（没被追踪返回 null）。 */
    public static Target lookup(Block block) {
        load();
        return LOOKUP.get(block);
    }

    public static boolean contains(ResourceLocation id) {
        load();
        for (Target t : TARGETS) {
            if (t.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public static Target find(ResourceLocation id) {
        load();
        for (Target t : TARGETS) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }

    /** 勾选/取消勾选。返回 false 表示因为到达上限而没加上。 */
    public static boolean toggle(ResourceLocation id) {
        load();
        Target existing = find(id);
        if (existing != null) {
            TARGETS.remove(existing);
            rebuild();
            return true;
        }
        if (TARGETS.size() >= MAX_TARGETS) {
            return false;
        }
        TARGETS.add(new Target(id, nextColor()));
        pushRecent(id);
        rebuild();
        return true;
    }

    public static void remove(ResourceLocation id) {
        load();
        Target existing = find(id);
        if (existing != null) {
            TARGETS.remove(existing);
            rebuild();
        }
    }

    /** 手动换色：在调色板里循环，跳过其他目标正在用的颜色。 */
    public static void cycleColor(ResourceLocation id) {
        load();
        Target target = find(id);
        if (target == null) {
            return;
        }
        int index = 0;
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i] == target.color()) {
                index = i;
                break;
            }
        }
        for (int step = 1; step <= PALETTE.length; step++) {
            int candidate = PALETTE[(index + step) % PALETTE.length];
            if (!isColorUsed(candidate, id)) {
                replace(id, new Target(id, candidate));
                return;
            }
        }
    }

    public static void clear() {
        load();
        TARGETS.clear();
        rebuild();
    }

    private static void replace(ResourceLocation id, Target replacement) {
        for (int i = 0; i < TARGETS.size(); i++) {
            if (TARGETS.get(i).id().equals(id)) {
                TARGETS.set(i, replacement);
                rebuild();
                return;
            }
        }
    }

    private static boolean isColorUsed(int color, ResourceLocation except) {
        for (Target t : TARGETS) {
            if (t.color() == color && !t.id().equals(except)) {
                return true;
            }
        }
        return false;
    }

    private static int nextColor() {
        for (int color : PALETTE) {
            if (!isColorUsed(color, null)) {
                return color;
            }
        }
        return PALETTE[TARGETS.size() % PALETTE.length];
    }

    private static void rebuild() {
        LOOKUP.clear();
        for (Target t : TARGETS) {
            Block block = ForgeRegistries.BLOCKS.getValue(t.id());
            if (block != null) {
                LOOKUP.put(block, t);
            }
        }
        version++;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("orevision-targets.json");
    }

    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return;
            }
            JsonArray array = root.getAsJsonObject().getAsJsonArray("targets");
            if (array != null) {
                for (JsonElement element : array) {
                    if (!element.isJsonObject() || TARGETS.size() >= MAX_TARGETS) {
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    if (!object.has("block")) {
                        continue;
                    }
                    ResourceLocation id = ResourceLocation.tryParse(object.get("block").getAsString());
                    if (id == null || find(id) != null) {
                        continue;
                    }
                    int color = object.has("color") ? object.get("color").getAsInt() : nextColor();
                    TARGETS.add(new Target(id, color));
                }
            }

            JsonArray recentArray = root.getAsJsonObject().getAsJsonArray("recent");
            if (recentArray != null) {
                for (JsonElement element : recentArray) {
                    if (RECENT.size() >= RECENT_MAX || !element.isJsonPrimitive()) {
                        continue;
                    }
                    ResourceLocation id = ResourceLocation.tryParse(element.getAsString());
                    if (id != null && !RECENT.contains(id)) {
                        RECENT.add(id);
                    }
                }
            }
        } catch (Exception e) {
            OreVision.LOGGER.warn("[orevision] failed to read targets file", e);
        }
        rebuild();
    }

    public static void save() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (Target t : TARGETS) {
            JsonObject object = new JsonObject();
            object.addProperty("block", t.id().toString());
            object.addProperty("color", t.color());
            array.add(object);
        }
        root.add("targets", array);

        JsonArray recentArray = new JsonArray();
        for (ResourceLocation id : RECENT) {
            recentArray.add(id.toString());
        }
        root.add("recent", recentArray);

        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            OreVision.LOGGER.warn("[orevision] failed to write targets file", e);
        }
    }

    private TargetStore() {
    }
}
