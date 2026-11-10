package com.orevision.client;

import com.orevision.OreVision;
import com.orevision.config.OreVisionConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描状态 + 结果缓存。
 *
 * <p>渲染用的是整片矿脉的<b>外轮廓</b>，而且每个追踪目标单独一套：
 * 一个方块如果有邻居是同一个目标，它们之间的面就属于矿脉内部，不画。
 * 剩下的面再按"法线是否相同"过滤掉同一表面上的接缝，只留真正的棱。</p>
 */
public final class OreOverlay {
    public record Entry(BlockPos pos, ResourceLocation target, int color) {
    }

    /** 轮廓边：两端是方块的整数角坐标。 */
    public record Seg(int x1, int y1, int z1, int x2, int y2, int z2, int color) {
    }

    /** HUD 用的一行：某个目标这次显示了多少、扫到过多少。 */
    public record Stat(ResourceLocation id, int color, String name, int shown, int matched) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    /** 每个追踪目标一块轮廓。 */
    private static final Map<ResourceLocation, List<Seg>> HULLS = new LinkedHashMap<>();
    private static final List<Stat> STATS = new ArrayList<>();
    private static final Map<ResourceLocation, String> NAMES = new HashMap<>();
    /** 每种目标本轮扫到过多少（在截断之前统计），HUD 用来显示"还有多少没画"。 */
    private static final Map<ResourceLocation, Integer> MATCHED_COUNTS = new HashMap<>();
    private static boolean enabled = false;
    private static int scanCountdown = 0;
    private static boolean helmetBlocked = false;
    private static int lastLoggedCount = -1;
    /** 游戏内临时改半径的偏移量，不写回配置文件。 */
    private static int distanceDelta = 0;
    /** 上次扫描时目标列表的版本，玩家改了目标就要重扫。 */
    private static int seenTargetVersion = -1;
    private static long scanMillis;
    private static int matchedTotal;
    private static boolean truncated;

    public static boolean enabled() {
        return enabled;
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Map<ResourceLocation, List<Seg>> hulls() {
        return HULLS;
    }

    public static List<Stat> stats() {
        return STATS;
    }

    public static long lastScanMillis() {
        return scanMillis;
    }

    public static int matchedTotal() {
        return matchedTotal;
    }

    public static boolean truncated() {
        return truncated;
    }

    public static int edgeTotal() {
        int total = 0;
        for (List<Seg> segments : HULLS.values()) {
            total += segments.size();
        }
        return total;
    }

    /** 目标或参数变了，让下一次 tick 立刻重扫。 */
    public static void requestRescan() {
        scanCountdown = 0;
        lastLoggedCount = -1;
    }

    /** 当前生效的扫描半径 = 配置值 + 游戏内临时偏移。 */
    public static int effectiveDistance() {
        int base = (int) Math.round(OreVisionConfig.MAX_DISTANCE.get());
        return Mth.clamp(base + distanceDelta, 4, 128);
    }

    public static void adjustDistance(int step) {
        int base = (int) Math.round(OreVisionConfig.MAX_DISTANCE.get());
        int now = Mth.clamp(effectiveDistance() + step, 4, 128);
        distanceDelta = now - base;
        requestRescan();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("message.orevision.range", now), true);
        }
    }

    public static void toggle() {
        if (!enabled && TargetStore.targets().isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("message.orevision.no_targets"), true);
            }
            return;
        }
        enabled = !enabled;
        requestRescan();
        if (!enabled) {
            clearAll();
        }
        OreVision.LOGGER.info("[orevision] overlay toggled: enabled={}", enabled);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable(enabled ? "message.orevision.on" : "message.orevision.off"), true);
        }
    }

    private static void clearAll() {
        ENTRIES.clear();
        HULLS.clear();
        STATS.clear();
        MATCHED_COUNTS.clear();
        matchedTotal = 0;
        truncated = false;
    }

    public static void tick(ClientLevel level, LocalPlayer player) {
        if (!enabled) {
            return;
        }

        // 玩家在界面里改了目标列表 → 立刻重扫
        if (TargetStore.version() != seenTargetVersion) {
            seenTargetVersion = TargetStore.version();
            requestRescan();
        }
        if (TargetStore.targets().isEmpty()) {
            if (!ENTRIES.isEmpty() || !STATS.isEmpty()) {
                clearAll();
            }
            return;
        }

        if (OreVisionConfig.REQUIRE_HELMET.get()) {
            ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
            if (helmet.isEmpty()) {
                if (!helmetBlocked) {
                    clearAll();
                    helmetBlocked = true;
                }
                return;
            }
            helmetBlocked = false;
        }

        if (scanCountdown-- > 0) {
            // 每 tick 只核对已缓存的方块还在不在：挖掉一块能立刻消失，不用等 1 秒整轮重扫
            if (validate(level, player.blockPosition())) {
                lastLoggedCount = -1;
            }
            return;
        }
        scanCountdown = 20;
        scan(level, player);
        if (ENTRIES.size() != lastLoggedCount) {
            lastLoggedCount = ENTRIES.size();
            OreVision.LOGGER.info("[orevision] scan: {} shown / {} found, {} outlines, {} ms",
                    ENTRIES.size(), matchedTotal, HULLS.size(), scanMillis);
        }
    }

    /** 逐条核对已缓存的方块是否还被追踪，返回是否有变化。只查已加载区块，避免把没加载的当成被挖掉。 */
    private static boolean validate(ClientLevel level, BlockPos origin) {
        boolean changed = false;
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            Entry entry = ENTRIES.get(i);
            if (!level.isLoaded(entry.pos())) {
                continue;
            }
            TargetStore.Target target = TargetStore.lookup(level.getBlockState(entry.pos()).getBlock());
            if (target == null || !target.id().equals(entry.target())) {
                ENTRIES.remove(i);
                MATCHED_COUNTS.merge(entry.target(), -1, Integer::sum);
                changed = true;
            }
        }
        if (changed) {
            buildHulls(origin);
            rebuildStats();
        }
        return changed;
    }

    /**
     * 一圈一圈往外扫（先扫玩家脚下的区块），这样一旦凑够上限，
     * 后面更远的圈基本可以整圈跳过，选"石头"这种高频方块时成本也有上界。
     */
    private static void scan(ClientLevel level, LocalPlayer player) {
        long start = System.nanoTime();
        ENTRIES.clear();
        HULLS.clear();
        STATS.clear();

        int cap = Math.max(1, OreVisionConfig.MAX_ORES.get());
        double maxDistance = effectiveDistance();
        double maxDistanceSq = maxDistance * maxDistance;
        BlockPos origin = player.blockPosition();
        ChunkPos center = player.chunkPosition();
        int chunkRadius = (int) Math.ceil(maxDistance / 16.0D);
        int candidateCap = cap * 8;

        List<Entry> found = new ArrayList<>(Math.max(16, cap * 2));
        Map<ResourceLocation, Integer> matched = new HashMap<>();
        boolean stopped = false;

        outer:
        for (int ring = 0; ring <= chunkRadius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    LevelChunk chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
                    if (chunk != null) {
                        scanChunk(chunk, origin, maxDistanceSq, found, matched);
                    }
                    if (found.size() >= candidateCap) {
                        stopped = true;
                        break outer;
                    }
                }
            }
            if (ring < chunkRadius && found.size() >= cap) {
                // 下一圈里最近的方块也一定比已保留的最远一个更远 → 收工
                double farthestKept = farthestKept(found, cap, origin);
                double nextMin = Math.max(1.0D, (ring + 1) * 16.0D - 8.0D);
                if (nextMin * nextMin >= farthestKept) {
                    stopped = true;
                    break;
                }
            }
        }

        matchedTotal = 0;
        for (int count : matched.values()) {
            matchedTotal += count;
        }
        MATCHED_COUNTS.clear();
        MATCHED_COUNTS.putAll(matched);
        truncated = stopped || found.size() > cap;

        found.sort(Comparator.comparingDouble(e -> e.pos().distSqr(origin)));
        if (found.size() > cap) {
            found.subList(cap, found.size()).clear();
        }
        ENTRIES.addAll(found);

        buildHulls(origin);
        rebuildStats();
        scanMillis = (System.nanoTime() - start) / 1_000_000L;
    }

    /** 找出第 cap 名（1 基）的距离平方，用来判断还要不要继续往外扫。 */
    private static double farthestKept(List<Entry> found, int cap, BlockPos origin) {
        if (found.size() <= cap) {
            return Double.MAX_VALUE;
        }
        List<Entry> copy = new ArrayList<>(found);
        copy.sort(Comparator.comparingDouble(e -> e.pos().distSqr(origin)));
        return copy.get(cap - 1).pos().distSqr(origin);
    }

    /** 按目标分组，各自只保留外表面上的棱。 */
    private static void buildHulls(BlockPos origin) {
        HULLS.clear();
        if (ENTRIES.isEmpty()) {
            return;
        }

        Map<ResourceLocation, List<Entry>> groups = new HashMap<>();
        for (Entry entry : ENTRIES) {
            groups.computeIfAbsent(entry.target(), key -> new ArrayList<>()).add(entry);
        }

        for (Map.Entry<ResourceLocation, List<Entry>> group : groups.entrySet()) {
            HULLS.put(group.getKey(), buildHull(group.getValue(), origin));
        }
    }

    private static void rebuildStats() {
        STATS.clear();
        Map<ResourceLocation, Integer> shown = new HashMap<>();
        for (Entry entry : ENTRIES) {
            shown.merge(entry.target(), 1, Integer::sum);
        }
        matchedTotal = 0;
        for (Map.Entry<ResourceLocation, Integer> entry : shown.entrySet()) {
            ResourceLocation id = entry.getKey();
            int matched = Math.max(entry.getValue(), MATCHED_COUNTS.getOrDefault(id, entry.getValue()));
            matchedTotal += matched;
            TargetStore.Target target = TargetStore.find(id);
            STATS.add(new Stat(id, target == null ? 0xFFFFFF : target.color(),
                    nameOf(id), entry.getValue(), matched));
        }
        STATS.sort((a, b) -> Integer.compare(b.shown(), a.shown()));
    }

    private static String nameOf(ResourceLocation id) {
        String cached = NAMES.get(id);
        if (cached != null) {
            return cached;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        String name = block == null ? id.getPath() : block.getName().getString();
        NAMES.put(id, name);
        return name;
    }

    private static List<Seg> buildHull(List<Entry> group, BlockPos origin) {
        Set<Long> same = new HashSet<>(group.size() * 2);
        for (Entry entry : group) {
            same.add(entry.pos().asLong());
        }

        Map<Long, Acc> edges = new LinkedHashMap<>();
        for (Entry entry : group) {
            BlockPos pos = entry.pos();
            for (Direction dir : Direction.values()) {
                // 邻居也是同一个目标 → 这个面在矿脉内部，不画
                if (same.contains(pos.relative(dir).asLong())) {
                    continue;
                }
                addFaceEdges(edges, pos, dir, entry.color(), origin);
            }
        }

        List<Seg> result = new ArrayList<>(edges.size());
        for (Acc acc : edges.values()) {
            // 只有一条面用到 = 表面边界；两条面共边且法线不同 = 真折边；法线相同 = 同一表面的接缝，丢掉
            if (acc.faces == 1 || acc.crease) {
                result.add(acc.seg);
            }
        }
        return result;
    }

    /** 一条候选边，外加用到它的那些面的法线，用来判断是不是真折边。 */
    private static final class Acc {
        private final Seg seg;
        private final int nx;
        private final int ny;
        private final int nz;
        private int faces = 1;
        private boolean crease;

        private Acc(Seg seg, int nx, int ny, int nz) {
            this.seg = seg;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }
    }

    private static void addFaceEdges(Map<Long, Acc> out, BlockPos pos, Direction dir, int color, BlockPos origin) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int[][] corners = switch (dir) {
            case UP -> new int[][]{{x, y + 1, z}, {x + 1, y + 1, z}, {x + 1, y + 1, z + 1}, {x, y + 1, z + 1}};
            case DOWN -> new int[][]{{x, y, z}, {x, y, z + 1}, {x + 1, y, z + 1}, {x + 1, y, z}};
            case NORTH -> new int[][]{{x, y, z}, {x + 1, y, z}, {x + 1, y + 1, z}, {x, y + 1, z}};
            case SOUTH -> new int[][]{{x, y, z + 1}, {x, y + 1, z + 1}, {x + 1, y + 1, z + 1}, {x + 1, y, z + 1}};
            case WEST -> new int[][]{{x, y, z}, {x, y + 1, z}, {x, y + 1, z + 1}, {x, y, z + 1}};
            case EAST -> new int[][]{{x + 1, y, z}, {x + 1, y, z + 1}, {x + 1, y + 1, z + 1}, {x + 1, y + 1, z}};
        };
        for (int i = 0; i < 4; i++) {
            addEdge(out, corners[i], corners[(i + 1) % 4], color, origin,
                    dir.getStepX(), dir.getStepY(), dir.getStepZ());
        }
    }

    /** 同一条边会被两个相邻面各报一次，用"小端点 + 轴"做键；同时记下两个面的法线判断折边。 */
    private static void addEdge(Map<Long, Acc> out, int[] a, int[] b, int color, BlockPos origin,
                                int nx, int ny, int nz) {
        int[] start = a;
        int[] end = b;
        if (start[0] > end[0]
                || (start[0] == end[0] && start[1] > end[1])
                || (start[0] == end[0] && start[1] == end[1] && start[2] > end[2])) {
            start = b;
            end = a;
        }
        int axis = start[0] != end[0] ? 0 : (start[1] != end[1] ? 1 : 2);
        long key = edgeKey(start, axis, origin);
        Acc acc = out.get(key);
        if (acc == null) {
            out.put(key, new Acc(
                    new Seg(start[0], start[1], start[2], end[0], end[1], end[2], color), nx, ny, nz));
        } else {
            acc.faces++;
            if (acc.nx != nx || acc.ny != ny || acc.nz != nz) {
                acc.crease = true;
            }
        }
    }

    private static long edgeKey(int[] corner, int axis, BlockPos origin) {
        long cx = corner[0] - origin.getX() + 2048L;
        long cy = corner[1] - origin.getY() + 2048L;
        long cz = corner[2] - origin.getZ() + 2048L;
        return (cx & 0xFFFL) | ((cy & 0xFFFL) << 12) | ((cz & 0xFFFL) << 24) | ((long) axis << 36);
    }

    private static void scanChunk(LevelChunk chunk, BlockPos origin, double maxDistanceSq,
                                  List<Entry> found, Map<ResourceLocation, Integer> matched) {
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            // 调色板里没有目标方块就整段跳过，这是让扫描便宜的关键
            if (!section.maybeHas(state -> TargetStore.lookup(state.getBlock()) != null)) {
                continue;
            }
            int baseY = minY + sectionIndex * 16;
            if (!sectionInRange(baseX, baseY, baseZ, origin, maxDistanceSq)) {
                continue;
            }

            PalettedContainer<BlockState> states = section.getStates();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = states.get(x, y, z);
                        TargetStore.Target target = TargetStore.lookup(state.getBlock());
                        if (target == null) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                        if (pos.distSqr(origin) > maxDistanceSq) {
                            continue;
                        }
                        found.add(new Entry(pos, target.id(), target.color()));
                        matched.merge(target.id(), 1, Integer::sum);
                    }
                }
            }
        }
    }

    /** 16³ 的整段方块是否和扫描球有交集，没有就整段跳过（竖向尤其省）。 */
    private static boolean sectionInRange(int baseX, int baseY, int baseZ, BlockPos origin, double maxDistanceSq) {
        double dx = axisGap(baseX, baseX + 15, origin.getX());
        double dy = axisGap(baseY, baseY + 15, origin.getY());
        double dz = axisGap(baseZ, baseZ + 15, origin.getZ());
        return dx * dx + dy * dy + dz * dz <= maxDistanceSq;
    }

    private static double axisGap(int low, int high, int value) {
        if (value < low) {
            return low - value;
        }
        if (value > high) {
            return value - high;
        }
        return 0.0D;
    }

    private OreOverlay() {
    }
}
