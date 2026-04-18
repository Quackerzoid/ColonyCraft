package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.ItemRequest;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.blockentity.LumbermillBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * AI goal for Lumberjack workers.
 *
 * <p>Three-phase behaviour:
 * <ol>
 *   <li><b>APPROACH</b> — scans the village territory for the nearest tree
 *       (any {@link net.minecraft.tags.BlockTags#LOGS} block), walks to the
 *       bottom of its trunk, and waits for the approach timeout.
 *   <li><b>CHOP</b> — stops moving, looks at the base log, swings the axe
 *       every {@value #SWING_INTERVAL} ticks with block-crack particles and
 *       the log's hit sound.  Sets
 *       {@link VillagerWorkerEntity#isChoppingActive()} to {@code true} so
 *       {@link LumbermillBlockEntity#serverTick} counts down the 30 s timer.
 *       When the timer expires, floods-fills the entire tree (≤ 150 logs),
 *       breaks every log block, deposits loot into the worker's inventory,
 *       and plants a sapling at the base position.
 *   <li><b>DEPOSIT</b> — walks back to the lumbermill and dumps everything
 *       from the worker inventory into the lumbermill's 9-slot output.
 * </ol>
 *
 * <p>The goal requires an {@link AxeItem} in the worker's tool slot.  If none
 * is present an iron-axe request is submitted to the Village Heart (30 s
 * cooldown between requests).
 */
public class LumberjackWorkGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    /** Maximum log count collected per tree (flood-fill cap). */
    private static final int    MAX_LOGS         = 150;
    /** XZ radius around the heart to search for trees. */
    private static final int    SEARCH_RADIUS    = 24;
    /** Squared reach (blocks²) at which the lumberjack is "at" the tree base. */
    private static final double REACH_SQ         = 9.0;   // 3 blocks
    /** Squared reach (blocks²) to consider the lumberjack "at" the lumbermill. */
    private static final double MILL_REACH_SQ    = 6.25;  // 2.5 blocks
    /** Ticks before giving up on a navigation attempt. */
    private static final int    APPROACH_TIMEOUT = 200;
    /** Ticks between successive canUse scans after a failed approach. */
    private static final int    SCAN_INTERVAL    = 60;
    /** Ticks between arm-swing animations during chopping. */
    private static final int    SWING_INTERVAL   = 10;
    /** Ticks between particle bursts during chopping. */
    private static final int    PARTICLE_INTERVAL = 8;
    private static final double WALK_SPEED        = 0.6;

    // ── State ─────────────────────────────────────────────────────────────────
    private enum Phase { APPROACH, CHOP, DEPOSIT }

    private final VillagerWorkerEntity lumberjack;
    private Phase   phase            = Phase.APPROACH;
    private int     approachTimeout  = 0;
    private boolean approachFailed   = false;
    private int     scanCooldown     = 0;
    private int     swingCooldown    = 0;
    private int     particleCooldown = 0;
    private int     requestCooldown  = 0;

    @Nullable private BlockPos treeBasePos   = null;
    @Nullable private BlockPos lumbermillPos = null;

    public LumberjackWorkGoal(VillagerWorkerEntity lumberjack) {
        this.lumberjack = lumberjack;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── Goal lifecycle ────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        if (requestCooldown > 0) requestCooldown--;
        if (!lumberjack.level().isDay())        return false;
        if (lumberjack.isTooHungryToWork())     return false;
        if (scanCooldown > 0) { scanCooldown--; return false; }
        if (!(lumberjack.level() instanceof ServerLevel level)) return false;
        if (lumberjack.getJob() != JobType.LUMBERJACK)          return false;

        if (!hasAxeEquipped()) {
            if (requestCooldown <= 0) {
                submitToolRequest(level, new ItemStack(Items.IRON_AXE));
                requestCooldown = 600;
            }
            return false;
        }
        resolveToolRequest(level);

        lumbermillPos = lumberjack.getWorkplacePos();
        if (lumbermillPos == null) return false;

        treeBasePos = findNearbyTree(level);
        return treeBasePos != null;
    }

    @Override
    public void start() {
        phase           = Phase.APPROACH;
        approachTimeout = APPROACH_TIMEOUT;
        approachFailed  = false;
        swingCooldown   = SWING_INTERVAL;
        particleCooldown = PARTICLE_INTERVAL;
        navigateTo(treeBasePos);
    }

    @Override
    public boolean canContinueToUse() {
        if (approachFailed)                                      return false;
        if (!lumberjack.level().isDay())                         return false;
        if (lumberjack.isTooHungryToWork())                      return false;
        if (!(lumberjack.level() instanceof ServerLevel))        return false;
        if (lumberjack.getJob() != JobType.LUMBERJACK)           return false;
        if (!hasAxeEquipped())                                   return false;
        return lumberjack.getWorkplacePos() != null;
    }

    @Override
    public void tick() {
        if (!(lumberjack.level() instanceof ServerLevel level)) return;

        switch (phase) {
            case APPROACH -> tickApproach(level);
            case CHOP     -> tickChop(level);
            case DEPOSIT  -> tickDeposit(level);
            default       -> {}
        }
    }

    @Override
    public void stop() {
        lumberjack.setChoppingActive(false);
        lumberjack.getNavigation().stop();
        // Apply a short scan cooldown only when actually stopping (no trees found /
        // navigation failed).  When looping tree→deposit→tree the goal never stops.
        scanCooldown   = SCAN_INTERVAL;
        approachFailed = false;
        treeBasePos    = null;
        lumbermillPos  = null;
        phase          = Phase.APPROACH;
    }

    // ── Phase ticks ───────────────────────────────────────────────────────────

    private void tickApproach(ServerLevel level) {
        // No tree selected yet — find one (happens after a deposit loop-back, or on start)
        if (treeBasePos == null) {
            lumbermillPos = lumberjack.getWorkplacePos();
            treeBasePos = findNearbyTree(level);
            if (treeBasePos == null) {
                // No trees in range right now — stop the goal so the lumberjack can do
                // other things (eat, sleep) and canUse() will restart it when a tree appears
                approachFailed = true;
                return;
            }
            approachTimeout = APPROACH_TIMEOUT;
            swingCooldown   = SWING_INTERVAL;
            particleCooldown = PARTICLE_INTERVAL;
            navigateTo(treeBasePos);
            return;
        }

        double distSq = distSqTo(treeBasePos);
        if (distSq <= REACH_SQ) {
            lumberjack.getNavigation().stop();
            phase = Phase.CHOP;
        } else {
            if (--approachTimeout <= 0) {
                approachFailed = true;
            } else if (lumberjack.getNavigation().isDone()) {
                navigateTo(treeBasePos);
            }
        }
    }

    private void tickChop(ServerLevel level) {
        if (treeBasePos == null) { approachFailed = true; return; }

        // If the tree base was already broken (e.g. by the player), abort
        if (!level.getBlockState(treeBasePos).is(BlockTags.LOGS)) {
            lumberjack.setChoppingActive(false);
            approachFailed = true;
            return;
        }

        // If the lumberjack drifted too far, re-approach
        if (distSqTo(treeBasePos) > REACH_SQ * 4) {
            phase           = Phase.APPROACH;
            approachTimeout = APPROACH_TIMEOUT;
            lumberjack.setChoppingActive(false);
            navigateTo(treeBasePos);
            return;
        }

        lumberjack.setChoppingActive(true);
        lumberjack.getNavigation().stop();
        lumberjack.getLookControl().setLookAt(
                treeBasePos.getX() + 0.5,
                treeBasePos.getY() + 0.5,
                treeBasePos.getZ() + 0.5,
                30f, 30f);

        // Arm swing + log-hit sound
        if (--swingCooldown <= 0) {
            swingCooldown = SWING_INTERVAL;
            lumberjack.swing(InteractionHand.MAIN_HAND);

            BlockState baseState = level.getBlockState(treeBasePos);
            var sounds = baseState.getSoundType();
            level.playSound(null,
                    treeBasePos.getX() + 0.5, treeBasePos.getY() + 0.5, treeBasePos.getZ() + 0.5,
                    sounds.getHitSound(),
                    SoundSource.BLOCKS,
                    sounds.getVolume() * 0.3f,
                    sounds.getPitch() * 0.8f + level.getRandom().nextFloat() * 0.2f);
        }

        // Wood-crack particles on the log face
        if (--particleCooldown <= 0) {
            particleCooldown = PARTICLE_INTERVAL;
            spawnChopParticles(treeBasePos, level.getBlockState(treeBasePos), level);
        }

        // Check if the block entity has signalled that the 30 s timer has expired
        if (lumbermillPos != null) {
            var be = level.getBlockEntity(lumbermillPos);
            if (be instanceof LumbermillBlockEntity mill && mill.isChopCompleted()) {
                mill.clearChopCompleted();
                lumberjack.setChoppingActive(false);
                harvestTree(level);
                lumberjack.gainXp(3);
                phase = Phase.DEPOSIT;
                if (lumbermillPos != null) navigateToMill();
            }
        }
    }

    private void tickDeposit(ServerLevel level) {
        if (lumbermillPos == null) {
            // No mill — reset so canContinueToUse() re-validates the workplace
            approachFailed = true;
            return;
        }

        double distSq = distSqTo(lumbermillPos);
        if (distSq <= MILL_REACH_SQ) {
            var be = level.getBlockEntity(lumbermillPos);
            if (be instanceof LumbermillBlockEntity mill) {
                dumpInventoryToMill(mill);
            }
            // Done with this tree — immediately hunt for the next one without stopping
            treeBasePos = null;
            phase       = Phase.APPROACH;
        } else if (lumberjack.getNavigation().isDone()) {
            navigateToMill();
        }
    }

    // ── Tree harvest ──────────────────────────────────────────────────────────

    private void harvestTree(ServerLevel level) {
        if (treeBasePos == null) return;

        BlockState baseState = level.getBlockState(treeBasePos);
        if (!baseState.is(BlockTags.LOGS)) return;

        // Flood-fill to find all connected log blocks
        List<BlockPos> logPositions = floodFillTree(level, treeBasePos);
        int logCount = logPositions.size();

        // Identify wood type from base log
        Item logItem      = baseState.getBlock().asItem();
        Item saplingItem  = getSaplingForLog(baseState);
        boolean isOak     = baseState.is(BlockTags.OAK_LOGS);

        // Break every log block (top-down to look natural)
        for (int i = logPositions.size() - 1; i >= 0; i--) {
            level.removeBlock(logPositions.get(i), false);
        }

        // Plant a sapling at the base position (now air)
        if (level.getBlockState(treeBasePos).isAir()
                && saplingItem instanceof BlockItem saplingBlock) {
            BlockPos floor = treeBasePos.below();
            BlockState floorState = level.getBlockState(floor);
            if (floorState.isFaceSturdy(level, floor, Direction.UP)) {
                level.setBlock(treeBasePos,
                        saplingBlock.getBlock().defaultBlockState(), 3);
            }
        }

        // Deposit loot into worker inventory
        SimpleContainer inv = lumberjack.getWorkerInventory();
        depositToInventory(inv, new ItemStack(logItem, logCount));

        int sticks   = 3 + lumberjack.getRandom().nextInt(3);          // 3–5
        int saplings = 1 + lumberjack.getRandom().nextInt(4);          // 1–4
        depositToInventory(inv, new ItemStack(Items.STICK,       sticks));
        depositToInventory(inv, new ItemStack(saplingItem,       saplings));
        if (isOak && lumberjack.getRandom().nextInt(2) == 0) {
            depositToInventory(inv, new ItemStack(Items.APPLE, 1));    // 0–1
        }
    }

    /**
     * BFS flood-fill from {@code base} collecting all connected
     * {@link net.minecraft.tags.BlockTags#LOGS} blocks, capped at
     * {@value #MAX_LOGS}.
     */
    private List<BlockPos> floodFillTree(ServerLevel level, BlockPos base) {
        List<BlockPos> result  = new ArrayList<>();
        Set<BlockPos>  visited = new HashSet<>();
        Queue<BlockPos> queue  = new ArrayDeque<>();
        queue.add(base);
        visited.add(base);

        while (!queue.isEmpty() && result.size() < MAX_LOGS) {
            BlockPos current = queue.poll();
            if (!level.getBlockState(current).is(BlockTags.LOGS)) continue;
            result.add(current);
            for (Direction dir : Direction.values()) {
                BlockPos neighbour = current.relative(dir);
                if (!visited.contains(neighbour)) {
                    visited.add(neighbour);
                    queue.add(neighbour);
                }
            }
        }
        return result;
    }

    // ── Deposit to lumbermill ─────────────────────────────────────────────────

    private void dumpInventoryToMill(LumbermillBlockEntity mill) {
        SimpleContainer inv    = lumberjack.getWorkerInventory();
        SimpleContainer output = mill.getOutputContainer();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            depositToContainer(output, stack);
            inv.setItem(i, ItemStack.EMPTY);
        }
    }

    // ── Tree search ───────────────────────────────────────────────────────────

    /**
     * Scans the heart's territory for any tree, returning the bottom-most log
     * block (the "base") of a randomly chosen candidate.
     *
     * <p>A candidate log must have at least one other log directly above it
     * (to filter out lone decorative logs placed by the player).
     */
    @Nullable
    private BlockPos findNearbyTree(ServerLevel level) {
        if (lumbermillPos == null) return null;
        var wbe = level.getBlockEntity(lumbermillPos);
        if (!(wbe instanceof IWorkplaceBlockEntity workplace)) return null;
        BlockPos heartPos = workplace.getLinkedHeartPos();
        if (heartPos == null) return null;

        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -4; dy <= 20; dy++) {
                    BlockPos check = heartPos.offset(dx, dy, dz);
                    if (!level.getBlockState(check).is(BlockTags.LOGS)) continue;
                    // Must have another log above (confirm it's a tree trunk)
                    if (!level.getBlockState(check.above()).is(BlockTags.LOGS)) continue;
                    // Walk down to the base of this trunk
                    BlockPos base = findBase(level, check);
                    if (!candidates.contains(base)) {
                        candidates.add(base);
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(lumberjack.getRandom().nextInt(candidates.size()));
    }

    /** Returns the lowest log in the vertical column containing {@code start}. */
    private static BlockPos findBase(ServerLevel level, BlockPos start) {
        BlockPos current = start;
        while (level.getBlockState(current.below()).is(BlockTags.LOGS)) {
            current = current.below();
        }
        return current;
    }

    // ── Sapling mapping ───────────────────────────────────────────────────────

    private static Item getSaplingForLog(BlockState logState) {
        if (logState.is(BlockTags.OAK_LOGS))      return Items.OAK_SAPLING;
        if (logState.is(BlockTags.SPRUCE_LOGS))   return Items.SPRUCE_SAPLING;
        if (logState.is(BlockTags.BIRCH_LOGS))    return Items.BIRCH_SAPLING;
        if (logState.is(BlockTags.JUNGLE_LOGS))   return Items.JUNGLE_SAPLING;
        if (logState.is(BlockTags.ACACIA_LOGS))   return Items.ACACIA_SAPLING;
        if (logState.is(BlockTags.DARK_OAK_LOGS)) return Items.DARK_OAK_SAPLING;
        if (logState.is(BlockTags.MANGROVE_LOGS)) return Items.MANGROVE_PROPAGULE;
        if (logState.is(BlockTags.CHERRY_LOGS))   return Items.CHERRY_SAPLING;
        return Items.OAK_SAPLING;
    }

    // ── Tool request helpers ──────────────────────────────────────────────────

    private void submitToolRequest(ServerLevel level, ItemStack tool) {
        VillageHeartBlockEntity heart = findHeart(level);
        if (heart == null) return;
        heart.addRequest(new ItemRequest(
                lumberjack.getUUID(), lumberjack.getBaseName(), JobType.LUMBERJACK, tool));
    }

    private void resolveToolRequest(ServerLevel level) {
        VillageHeartBlockEntity heart = findHeart(level);
        if (heart != null) heart.resolveRequest(lumberjack.getUUID());
    }

    @Nullable
    private VillageHeartBlockEntity findHeart(ServerLevel level) {
        BlockPos workplace = lumberjack.getWorkplacePos();
        if (workplace == null) return null;
        if (!(level.getBlockEntity(workplace) instanceof IWorkplaceBlockEntity wbe)) return null;
        BlockPos heartPos = wbe.getLinkedHeartPos();
        if (heartPos == null) return null;
        return level.getBlockEntity(heartPos) instanceof VillageHeartBlockEntity h ? h : null;
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    private void navigateTo(BlockPos pos) {
        if (pos == null) return;
        lumberjack.getNavigation().moveTo(
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, WALK_SPEED);
    }

    private void navigateToMill() {
        if (lumbermillPos == null) return;
        lumberjack.getNavigation().moveTo(
                lumbermillPos.getX() + 0.5, lumbermillPos.getY(),
                lumbermillPos.getZ() + 0.5, WALK_SPEED);
    }

    private double distSqTo(BlockPos pos) {
        return lumberjack.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    // ── Particles ─────────────────────────────────────────────────────────────

    private static void spawnChopParticles(BlockPos pos, BlockState state, ServerLevel level) {
        level.sendParticles(
                new BlockParticleOption(ParticleTypes.BLOCK, state),
                pos.getX() + 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5,
                6, 0.3, 0.3, 0.3, 0.1);
    }

    // ── Tool check ────────────────────────────────────────────────────────────

    private boolean hasAxeEquipped() {
        return lumberjack.getToolContainer().getItem(0).getItem() instanceof AxeItem;
    }

    // ── Inventory helpers ─────────────────────────────────────────────────────

    /** Inserts {@code stack} into the first available slot in {@code container}. */
    private static void depositToInventory(SimpleContainer container, ItemStack stack) {
        if (stack.isEmpty()) return;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int move  = Math.min(space, stack.getCount());
                slot.grow(move);
                stack.shrink(move);
                container.setItem(i, slot);
                if (stack.isEmpty()) return;
            }
        }
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).isEmpty()) {
                container.setItem(i, stack.copy());
                return;
            }
        }
        // Container full — discard silently
    }

    /** Inserts {@code stack} into the first available slot in {@code container}. */
    private static void depositToContainer(SimpleContainer container, ItemStack stack) {
        if (stack.isEmpty()) return;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int move  = Math.min(space, stack.getCount());
                slot.grow(move);
                stack.shrink(move);
                container.setItem(i, slot);
                if (stack.isEmpty()) return;
            }
        }
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).isEmpty()) {
                container.setItem(i, stack.copy());
                return;
            }
        }
    }
}
