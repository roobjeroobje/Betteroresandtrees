package nl.orevein;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Ore Vein Miner + Tree Feller (Fabric, Minecraft 26.3).
 * - Altijd actief, geen shift nodig. Pickaxe voor ores, bijl voor bomen.
 * - 1 durability per ore / per stam.
 * - Blokken verdwijnen geleidelijk (animatie) en met weinig geluid.
 * - Boom: stammen eerst, vanaf de stam die je slaat; bladeren pas daarna.
 * - Ores: iron ore + deepslate iron ore tellen als dezelfde vein (zelfde soort).
 * - Natuurlijke bomen vallen helemaal om; huizen blijven staan.
 */
public class OreVein implements ModInitializer {

    // ---- Limieten ----
    private static final int MAX_ORE_BLOCKS = 128;
    private static final int MAX_LOG_BLOCKS = 512;
    private static final int MAX_LEAF_BLOCKS = 1500;

    // ---- Animatie: hoeveel er per tick (1 tick = 1/20 sec) verdwijnt ----
    private static final double ORE_PER_TICK = 2.0;        // ores per tick
    private static final double TREE_BUDGET_PER_TICK = 1.5; // boom: stam kost 1.0, blad kost 0.25
    private static final double LOG_COST = 1.0;
    private static final double LEAF_COST = 0.25;

    // ---- Geluid: alleen elke N-de blok maakt geluid + deeltjes (hoger = stiller) ----
    private static final int ORE_SOUND_EVERY = 3;
    private static final int LOG_SOUND_EVERY = 4;
    private static final int LEAF_SOUND_EVERY = 12;

    // ---- Boom-herkenning ----
    private static final int LEAF_RADIUS = 2;       // stam telt als boom als er natuurlijk blad binnen 2 blokken zit
    private static final int MIN_NATURAL_LEAVES = 2;

    private static final List<Task> TASKS = new ArrayList<>();

    private record Entry(BlockPos pos, boolean block) {}

    private static class Task {
        final ServerLevel level;
        final UUID playerId;
        final BlockPos origin;
        final Predicate<BlockState> matcher;
        final boolean tree;
        final ArrayDeque<Entry> queue;
        final Set<BlockPos> treeLogs;
        double budget = 0;
        int blockCounter = 0;
        int leafCounter = 0;

        Task(ServerLevel level, UUID playerId, BlockPos origin, Predicate<BlockState> matcher, boolean tree,
             ArrayDeque<Entry> queue, Set<BlockPos> treeLogs) {
            this.level = level;
            this.playerId = playerId;
            this.origin = origin;
            this.matcher = matcher;
            this.tree = tree;
            this.queue = queue;
            this.treeLogs = treeLogs;
        }
    }

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> TASKS.clear());

        PlayerBlockBreakEvents.AFTER.register((level, player, origin, state, be) -> {
            if (!(level instanceof ServerLevel world)) return;
            if (!(player instanceof ServerPlayer sp)) return;
            if (sp.isCreative()) return;

            ItemStack tool = sp.getMainHandItem();

            if (state.is(ConventionalBlockTags.ORES)) {
                if (!tool.is(ItemTags.PICKAXES)) return;
                if (!sp.hasCorrectToolForDrops(state)) return;

                Predicate<BlockState> oreMatch = oreMatcher(state.getBlock());
                List<BlockPos> vein = collect(world, origin, oreMatch, MAX_ORE_BLOCKS);
                if (vein.isEmpty()) return;

                ArrayDeque<Entry> queue = new ArrayDeque<>();
                for (BlockPos p : vein) queue.add(new Entry(p, true));
                TASKS.add(new Task(world, sp.getUUID(), origin, oreMatch, false, queue, Set.of()));

            } else if (state.is(BlockTags.LOGS)) {
                if (!tool.is(ItemTags.AXES)) return;
                if (isPlayerMade(state)) return;

                Task task = planTree(world, sp, origin, state.getBlock());
                if (task != null) TASKS.add(task);
            }
        });
    }

    // =====================================================================
    //  Animatie: elke tick een paar blokken laten verdwijnen
    // =====================================================================

    private static void tick() {
        if (TASKS.isEmpty()) return;
        Iterator<Task> it = TASKS.iterator();
        while (it.hasNext()) {
            if (!step(it.next())) it.remove();
        }
    }

    /** @return false als de taak klaar of afgebroken is */
    private static boolean step(Task t) {
        ServerPlayer player = t.level.getServer().getPlayerList().getPlayer(t.playerId);
        if (player == null || player.isRemoved() || !player.isAlive() || player.level() != t.level) return false;

        ItemStack tool = player.getMainHandItem();
        if (!tool.is(t.tree ? ItemTags.AXES : ItemTags.PICKAXES)) return false;

        t.budget += t.tree ? TREE_BUDGET_PER_TICK : ORE_PER_TICK;

        while (!t.queue.isEmpty()) {
            Entry e = t.queue.peekFirst();
            double cost = e.block() ? LOG_COST : LEAF_COST;
            if (t.budget < cost) break;
            t.budget -= cost;
            t.queue.pollFirst();
            if (!process(t, player, e)) return false;
        }
        return !t.queue.isEmpty();
    }

    /** @return false als het gereedschap bijna kapot is (dan stopt de hele taak) */
    private static boolean process(Task t, ServerPlayer player, Entry e) {
        ServerLevel world = t.level;
        BlockPos pos = e.pos();
        BlockState s = world.getBlockState(pos);
        ItemStack tool = player.getMainHandItem();

        if (e.block()) {
            if (!t.matcher.test(s)) return true; // blok is al weg
            if (tool.isEmpty() || (tool.isDamageableItem() && tool.getDamageValue() >= tool.getMaxDamage() - 1)) {
                return false; // gereedschap blijft heel
            }

            BlockEntity be = world.getBlockEntity(pos);
            for (ItemStack drop : Block.getDrops(s, world, pos, be, player, tool)) {
                Block.popResource(world, t.origin, drop); // alle drops bij het eerste blok
            }
            s.spawnAfterBreak(world, pos, tool, true);

            int every = t.tree ? LOG_SOUND_EVERY : ORE_SOUND_EVERY;
            remove(world, player, pos, t.blockCounter++ % every == 0);
            tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND); // 1 durability per blok
            return true;
        }

        // blad
        if (!isNaturalLeaf(s)) return true;
        if (hasOtherLogNearby(world, pos, t.treeLogs)) return true;

        BlockEntity be = world.getBlockEntity(pos);
        for (ItemStack drop : Block.getDrops(s, world, pos, be, player, tool)) {
            Block.popResource(world, pos, drop);
        }
        remove(world, player, pos, t.leafCounter++ % LEAF_SOUND_EVERY == 0);
        return true;
    }

    /** Met effect = normaal breken (geluid + deeltjes). Zonder = stil laten verdwijnen. */
    private static void remove(ServerLevel world, ServerPlayer player, BlockPos pos, boolean withEffects) {
        if (withEffects) world.destroyBlock(pos, false, player);
        else world.removeBlock(pos, false);
    }

    // =====================================================================
    //  Boom plannen
    // =====================================================================

    /**
     * Een stam hoort bij de boom als er natuurlijk blad vlak bij zit. Daaronder tellen
     * de stammen mee die er recht onder staan (de rest van de stam tot de grond).
     * Stammen van een huis (met planken, deuren, glas, enz. ertegenaan) tellen nooit mee.
     * Een crafting table, kist, fakkel of bord tegen de boom maakt niets uit.
     */
    private static Task planTree(ServerLevel world, ServerPlayer sp, BlockPos origin, Block logBlock) {
        Predicate<BlockState> logMatch = st -> st.getBlock() == logBlock;
        List<BlockPos> found = collect(world, origin, logMatch, MAX_LOG_BLOCKS);
        Set<BlockPos> cluster = new HashSet<>(found);
        cluster.add(origin);

        // Stammen die een bouwwerk aanraken
        Set<BlockPos> building = new HashSet<>();
        for (BlockPos p : cluster) {
            if (touchesStructure(world, p)) building.add(p);
        }
        if (building.contains(origin)) return null;

        // Stammen met natuurlijk blad vlakbij
        Set<BlockPos> near = new HashSet<>();
        for (BlockPos p : cluster) {
            if (building.contains(p)) continue;
            if (countNaturalLeaves(world, p, LEAF_RADIUS) >= MIN_NATURAL_LEAVES) near.add(p);
        }

        // + de stam eronder (recht naar beneden)
        Set<BlockPos> tree = new HashSet<>(near);
        for (BlockPos p : near) {
            BlockPos q = p.below();
            while (cluster.contains(q) && !building.contains(q) && tree.add(q)) {
                q = q.below();
            }
        }
        if (!tree.contains(origin)) return null; // geen natuurlijke boom

        // Alleen het deel dat aan de geslagen stam vastzit
        Set<BlockPos> comp = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        comp.add(origin);
        queue.add(origin);
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos n = cur.offset(dx, dy, dz);
                if (tree.contains(n) && comp.add(n)) queue.add(n);
            }
        }
        comp.remove(origin); // dit blok is al gebroken
        if (comp.isEmpty() && countNaturalLeaves(world, origin, LEAF_RADIUS) == 0) return null;

        // Bladeren die aan deze boom hangen
        Set<BlockPos> starts = new HashSet<>(comp);
        starts.add(origin);
        Set<BlockPos> visited = new HashSet<>(starts);
        Queue<BlockPos> lq = new ArrayDeque<>(starts);
        List<BlockPos> leaves = new ArrayList<>();
        while (!lq.isEmpty() && leaves.size() < MAX_LEAF_BLOCKS) {
            BlockPos cur = lq.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = cur.relative(d);
                if (!visited.add(n)) continue;
                if (isNaturalLeaf(world.getBlockState(n))) {
                    leaves.add(n);
                    lq.add(n);
                }
            }
        }

        // Eerst ALLE stammen, beginnend bij de stam die je slaat en dan naar buiten toe.
        // Pas daarna verdwijnen de bladeren (ook van dichtbij naar ver weg).
        List<BlockPos> logList = new ArrayList<>(comp);
        logList.sort(Comparator.comparingInt(p -> distSq(origin, p)));
        leaves.sort(Comparator.comparingInt(p -> distSq(origin, p)));

        List<Entry> entries = new ArrayList<>();
        for (BlockPos p : logList) entries.add(new Entry(p, true));
        for (BlockPos p : leaves) entries.add(new Entry(p, false));

        ArrayDeque<Entry> deque = new ArrayDeque<>(entries);
        Set<BlockPos> felled = new HashSet<>(comp);
        felled.add(origin);
        return new Task(world, sp.getUUID(), origin, logMatch, true, deque, felled);
    }

    // =====================================================================
    //  Hulpfuncties
    // =====================================================================

    /** Alle aan elkaar vastzittende blokken die aan de voorwaarde voldoen (incl. diagonaal). */
    private static List<BlockPos> collect(ServerLevel world, BlockPos origin, Predicate<BlockState> matcher, int max) {
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        visited.add(origin);
        queue.add(origin);

        while (!queue.isEmpty() && found.size() < max) {
            BlockPos cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos next = cur.offset(dx, dy, dz);
                if (!visited.add(next)) continue;
                if (!matcher.test(world.getBlockState(next))) continue;
                if (found.size() >= max) break;
                found.add(next);
                queue.add(next);
            }
        }
        return found;
    }

    private static int distSq(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Zelfde ore, ook als er 'deepslate_' voor staat (iron_ore + deepslate_iron_ore = 1 vein). */
    private static String oreKey(Block b) {
        return BuiltInRegistries.BLOCK.getKey(b).toString().replace(":deepslate_", ":");
    }

    private static Predicate<BlockState> oreMatcher(Block origin) {
        String key = oreKey(origin);
        return st -> st.is(ConventionalBlockTags.ORES) && oreKey(st.getBlock()).equals(key);
    }

    private static boolean isNaturalLeaf(BlockState s) {
        return s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT);
    }

    /** Gestript of 'wood'/'hyphae'-blok = door een speler bewerkt. */
    private static boolean isPlayerMade(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.startsWith("stripped_") || path.endsWith("_wood") || path.endsWith("_hyphae");
    }

    /** Bouwmateriaal dat je in een natuurlijke boom niet verwacht (crafting table, kist, fakkel horen hier NIET bij). */
    private static boolean isStructural(BlockState s) {
        if (s.isAir()) return false;
        if (s.getBlock() instanceof LeavesBlock && s.getValue(LeavesBlock.PERSISTENT)) return true;
        return s.is(BlockTags.PLANKS) || s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS)
                || s.is(BlockTags.FENCES) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WALLS)
                || s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.WOOL)
                || s.is(Blocks.GLASS) || s.is(Blocks.GLASS_PANE) || isPlayerMade(s);
    }

    private static boolean touchesStructure(ServerLevel world, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (isStructural(world.getBlockState(p.relative(d)))) return true;
        }
        return false;
    }

    private static int countNaturalLeaves(ServerLevel world, BlockPos p, int r) {
        int n = 0;
        for (int dx = -r; dx <= r; dx++)
        for (int dy = -r; dy <= r; dy++)
        for (int dz = -r; dz <= r; dz++) {
            if (isNaturalLeaf(world.getBlockState(p.offset(dx, dy, dz)))) {
                if (++n >= MIN_NATURAL_LEAVES) return n;
            }
        }
        return n;
    }

    /** Blad blijft staan als er een stam van een ANDERE boom vlakbij staat. */
    private static boolean hasOtherLogNearby(ServerLevel world, BlockPos leaf, Set<BlockPos> felled) {
        for (int dx = -3; dx <= 3; dx++)
        for (int dy = -3; dy <= 3; dy++)
        for (int dz = -3; dz <= 3; dz++) {
            BlockPos p = leaf.offset(dx, dy, dz);
            if (felled.contains(p)) continue;
            if (world.getBlockState(p).is(BlockTags.LOGS)) return true;
        }
        return false;
    }
}
