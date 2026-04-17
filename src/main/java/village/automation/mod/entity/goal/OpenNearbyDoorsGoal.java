package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Goal that physically opens doors and fence gates when the mob walks up to
 * them, and closes them again once the mob has moved past.
 *
 * <p>Unlike {@link net.minecraft.world.entity.ai.goal.OpenDoorGoal} this goal
 * checks <em>physical proximity</em> rather than inspecting path-node types.
 * That makes it robust regardless of how the path navigator classifies
 * door/gate nodes — all that matters is that the mob is close to the block.
 *
 * <p>For the mob to actually <em>path through</em> a closed gate, the path
 * navigator must treat gate nodes as walkable
 * ({@code WalkNodeEvaluator.canPassDoors = true}).  That is handled separately
 * in {@code VillagerWorkerEntity.createNavigation()}.
 */
public class OpenNearbyDoorsGoal extends Goal {

    /** Squared distance within which a closed gate/door is opened (2 blocks). */
    private static final double OPEN_SQ   = 4.0;
    /**
     * Squared distance at which an opened gate/door may be closed again.
     * Larger than {@link #OPEN_SQ} so the mob fully passes through first.
     */
    private static final double CLOSE_SQ  = 16.0;
    /** Minimum ticks to wait after opening before considering closing (1 s). */
    private static final long   MIN_OPEN_TICKS = 20L;

    private final PathfinderMob mob;
    private final boolean closeDoor;

    /**
     * Positions we opened, mapped to the game-time tick of opening.
     * Only lower-half block positions are stored for doors.
     */
    private final Map<BlockPos, Long> opened = new LinkedHashMap<>();

    public OpenNearbyDoorsGoal(PathfinderMob mob, boolean closeDoor) {
        this.mob       = mob;
        this.closeDoor = closeDoor;
        setFlags(EnumSet.noneOf(Flag.class)); // no exclusive flags — runs alongside everything
    }

    @Override public boolean canUse()                  { return true; }
    @Override public boolean canContinueToUse()        { return true; }
    @Override public boolean isInterruptable()         { return false; }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (mob.level().isClientSide()) return;
        Level level = mob.level();

        // Only act while the mob is actively navigating somewhere
        if (!mob.getNavigation().isDone()) {
            BlockPos here = mob.blockPosition();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 0; dy <= 2; dy++) {
                        BlockPos check = here.offset(dx, dy, dz);
                        if (mob.distanceToSqr(
                                check.getX() + 0.5,
                                check.getY() + 0.5,
                                check.getZ() + 0.5) <= OPEN_SQ) {
                            tryOpen(level, check);
                        }
                    }
                }
            }
        }

        // Close any gates/doors the mob has moved away from
        if (closeDoor && !opened.isEmpty()) {
            long now = level.getGameTime();
            Iterator<Map.Entry<BlockPos, Long>> it = opened.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Long> entry = it.next();
                if (now - entry.getValue() < MIN_OPEN_TICKS) continue; // too soon
                double distSq = mob.distanceToSqr(
                        entry.getKey().getX() + 0.5,
                        entry.getKey().getY() + 0.5,
                        entry.getKey().getZ() + 0.5);
                if (distSq > CLOSE_SQ) {
                    tryClose(level, entry.getKey());
                    it.remove();
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void tryOpen(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockPos immutable = pos.immutable();

        if (state.getBlock() instanceof FenceGateBlock
                && !state.getValue(FenceGateBlock.OPEN)) {
            level.setBlock(immutable, state.setValue(FenceGateBlock.OPEN, true), 10);
            opened.putIfAbsent(immutable, level.getGameTime());

        } else if (state.getBlock() instanceof DoorBlock
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && !state.getValue(DoorBlock.OPEN)) {
            level.setBlock(immutable, state.setValue(DoorBlock.OPEN, true), 10);
            BlockPos upper = immutable.above();
            BlockState up  = level.getBlockState(upper);
            if (up.getBlock() instanceof DoorBlock) {
                level.setBlock(upper, up.setValue(DoorBlock.OPEN, true), 10);
            }
            opened.putIfAbsent(immutable, level.getGameTime());
        }
    }

    private void tryClose(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FenceGateBlock
                && state.getValue(FenceGateBlock.OPEN)) {
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, false), 10);

        } else if (state.getBlock() instanceof DoorBlock
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && state.getValue(DoorBlock.OPEN)) {
            level.setBlock(pos, state.setValue(DoorBlock.OPEN, false), 10);
            BlockPos upper = pos.above();
            BlockState up  = level.getBlockState(upper);
            if (up.getBlock() instanceof DoorBlock) {
                level.setBlock(upper, up.setValue(DoorBlock.OPEN, false), 10);
            }
        }
    }
}
