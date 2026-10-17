package nl.orevein;

import net.fabricmc.api.ModInitializer;
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
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Ore Vein Miner + Tree Feller (Minecraft 26.x, Mojang-namen).
 * Altijd actief, geen shift nodig. Pickaxe nodig voor ores, bijl voor bomen.
 * 1 durability per ore / per stam. Alleen natuurlijke bomen.
 */
public class OreVein implements ModInitializer {

    private static final int MAX_ORE_BLOCKS = 128;
    private static final int MAX_LOG_BLOCKS = 256;
    private static final int MAX_LEAF_BLOCKS = 700;
    private static final int MIN_NATURAL_LEAVES = 2;

    @Override
    public void onInitialize() {
        PlayerBlockBreakEvents.AFTER.register((level, player, origin, state, be) -> {
            if (!(level instanceof ServerLevel world)) return;
            if (!(player instanceof ServerPlayer sp)) return;
            if (sp.isCreative()) return;

            ItemStack tool = sp.getMainHandItem();

            if (state.is(ConventionalBlockTags.ORES)) {
                if (!tool.is(ItemTags.PICKAXES)) return;
                if (!sp.hasCorrectToolForDrops(state)) return;
                List<BlockPos> vein = collect(world, origin, state.getBlock(), MAX_ORE_BLOCKS);
                breakAll(world, sp, origin, vein, tool);

            } else if (state.is(BlockTags.LOGS)) {
                if (!tool.is(ItemTags.AXES)) return;
                if (isPlayerMade(state)) return;

                List<BlockPos> logs = collect(world, origin, state.getBlock(), MAX_LOG_BLOCKS);
                if (!isNaturalTree(world, origin, logs)) return;

                breakAll(world, sp, origin, logs, tool);
                removeLeaves(world, sp, origin, logs);
            }
        });
    }

    private List<BlockPos> collect(ServerLevel world, BlockPos origin, Block target, int max) {
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
                if (world.getBlockState(next).getBlock() != target) continue;
                if (found.size() >= max) break;
                found.add(next);
                queue.add(next);
            }
        }
        return found;
    }

    private boolean isPlayerMade(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.startsWith("stripped_") || path.endsWith("_wood") || path.endsWith("_hyphae");
    }

    private boolean isManMade(ServerLevel world, BlockPos pos, BlockState s) {
        if (s.isAir()) return false;
        if (s.getBlock() instanceof LeavesBlock && s.getValue(LeavesBlock.PERSISTENT)) return true;

        if (s.is(BlockTags.PLANKS) || s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS)
                || s.is(BlockTags.FENCES) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WALLS)
                || s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.ALL_SIGNS)
                || s.is(BlockTags.BEDS) || s.is(BlockTags.WOOL) || s.is(BlockTags.BANNERS)
                || s.is(BlockTags.BUTTONS) || s.is(BlockTags.PRESSURE_PLATES) || s.is(BlockTags.CANDLES)) {
            return true;
        }
        if (s.is(Blocks.GLASS) || s.is(Blocks.GLASS_PANE) || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH)
                || s.is(Blocks.LANTERN) || s.is(Blocks.LADDER) || s.is(Blocks.CRAFTING_TABLE)
                || s.is(Blocks.BOOKSHELF) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.STONE_BRICKS)) {
            return true;
        }
        if (!s.is(Blocks.BEE_NEST) && world.getBlockEntity(pos) != null) return true;
        return isPlayerMade(s);
    }

    private boolean isNaturalTree(ServerLevel world, BlockPos origin, List<BlockPos> logs) {
        List<BlockPos> all = new ArrayList<>(logs);
        all.add(origin);

        boolean onGround = false;
        for (BlockPos log : all) {
            BlockState below = world.getBlockState(log.below());
            if (below.is(BlockTags.DIRT) || below.is(Blocks.MUDDY_MANGROVE_ROOTS) || below.is(Blocks.MANGROVE_ROOTS)) {
                onGround = true;
                break;
            }
        }
        if (!onGround) return false;

        Set<BlockPos> checked = new HashSet<>();
        Set<BlockPos> logSet = new HashSet<>(all);
        int natural = 0;

        for (BlockPos log : all) {
            for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = log.offset(dx, dy, dz);
                if (!checked.add(p) || logSet.contains(p)) continue;
                BlockState s = world.getBlockState(p);
                if (isManMade(world, p, s)) return false;
                if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT)) natural++;
            }
        }
        return natural >= MIN_NATURAL_LEAVES;
    }

    private void breakAll(ServerLevel world, ServerPlayer player, BlockPos origin,
                          List<BlockPos> positions, ItemStack tool) {
        for (BlockPos pos : positions) {
            if (tool.isEmpty() || (tool.isDamageableItem() && tool.getDamageValue() >= tool.getMaxDamage() - 1)) return;

            BlockState s = world.getBlockState(pos);
            BlockEntity be = world.getBlockEntity(pos);

            for (ItemStack drop : Block.getDrops(s, world, pos, be, player, tool)) {
                Block.popResource(world, origin, drop);
            }
            s.spawnAfterBreak(world, pos, tool, true);

            world.destroyBlock(pos, false, player);
            tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }
    }

    private void removeLeaves(ServerLevel world, ServerPlayer player, BlockPos origin, List<BlockPos> logs) {
        List<BlockPos> starts = new ArrayList<>(logs);
        starts.add(origin);

        Set<BlockPos> visited = new HashSet<>(starts);
        Queue<BlockPos> queue = new ArrayDeque<>(starts);
        List<BlockPos> leaves = new ArrayList<>();

        while (!queue.isEmpty() && leaves.size() < MAX_LEAF_BLOCKS) {
            BlockPos cur = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos next = cur.relative(d);
                if (!visited.add(next)) continue;
                BlockState s = world.getBlockState(next);
                if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT)) {
                    leaves.add(next);
                    queue.add(next);
                }
            }
        }

        for (BlockPos leaf : leaves) {
            if (hasOtherLogNearby(world, leaf)) continue;
            world.destroyBlock(leaf, true, player);
        }
    }

    private boolean hasOtherLogNearby(ServerLevel world, BlockPos leaf) {
        for (int dx = -3; dx <= 3; dx++)
        for (int dy = -3; dy <= 3; dy++)
        for (int dz = -3; dz <= 3; dz++) {
            if (world.getBlockState(leaf.offset(dx, dy, dz)).is(BlockTags.LOGS)) return true;
        }
        return false;
    }
}
