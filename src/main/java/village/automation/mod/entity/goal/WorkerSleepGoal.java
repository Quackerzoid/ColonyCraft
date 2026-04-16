package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Stops all workers at nightfall, walks them to the nearest available bed
 * within {@value #SLEEP_SEARCH_RADIUS} blocks, and has them lie down until dawn.
 *
 * <p>If no bed is found the worker simply stands still for the night —
 * work and wander goals are still blocked via the shared MOVE + LOOK flags.
 *
 * <p>This goal must be registered at a <em>higher priority</em> (lower number)
 * than any work goal so it preempts all activity at night.
 */
public class WorkerSleepGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    /** XZ + Y search radius when looking for a bed. */
    private static final int    SLEEP_SEARCH_RADIUS = 24;
    /** Squared distance at which the worker is considered to have "arrived" at the bed. */
    private static final double ARRIVE_DIST_SQ      = 4.0;  // 2-block radius
    private static final double WALK_SPEED          = 0.6;

    // ── State ─────────────────────────────────────────────────────────────────
    private final VillagerWorkerEntity worker;

    /** Foot position of the target bed, or {@code null} when no bed was found. */
    @Nullable
    private BlockPos bedPos   = null;
    /** True once the worker has committed to lying down (navigation finished). */
    private boolean  sleeping = false;

    public WorkerSleepGoal(VillagerWorkerEntity worker) {
        this.worker = worker;
        // Own both movement and look so work / wander goals cannot run at night.
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Activates at nightfall. Always returns {@code true} at night (even with no
     * bed) so that all MOVE + LOOK goals are blocked for the duration of the night.
     */
    @Override
    public boolean canUse() {
        if (worker.level().isDay()) return false;
        findBed();   // populates bedPos; may leave it null if no bed is nearby
        return true;
    }

    /** Keeps the goal alive until dawn regardless of bed availability. */
    @Override
    public boolean canContinueToUse() {
        return !worker.level().isDay();
    }

    @Override
    public void start() {
        sleeping = false;
        if (bedPos != null) {
            // Navigate to the standing position above the bed foot
            worker.getNavigation().moveTo(
                    bedPos.getX() + 0.5,
                    bedPos.getY() + 1.0,
                    bedPos.getZ() + 0.5,
                    WALK_SPEED);
        } else {
            // No bed in range — stop in place for the night
            worker.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        if (sleeping) return;

        if (bedPos == null) {
            // No bed — commit to resting in place
            worker.getNavigation().stop();
            sleeping = true;
            return;
        }

        // Check if the worker has arrived near the bed (or the pathfinder gave up)
        double distSq = worker.distanceToSqr(
                bedPos.getX() + 0.5,
                bedPos.getY() + 1.0,
                bedPos.getZ() + 0.5);

        if (distSq <= ARRIVE_DIST_SQ || worker.getNavigation().isDone()) {
            worker.getNavigation().stop();
            // Snap X/Z to the bed centre but keep the entity's natural walking Y.
            // Forcing Y to the bed surface causes the model to sink because
            // Pose.SLEEPING rotates the model 90° around Z, so the body's depth
            // (not its height) becomes the vertical extent — half of which would
            // end up below whatever Y origin we set.
            worker.setPos(
                    bedPos.getX() + 0.5,
                    worker.getY(),
                    bedPos.getZ() + 0.5);
            // Register the sleeping position so getBedOrientation() returns the
            // bed's FACING direction.  The renderer calls getBedOrientation() and
            // passes it through its own sleepDirectionToRotation() lookup, which
            // is the correct mapping for Pose.SLEEPING — NOT Direction.toYRot().
            worker.setSleepingPos(bedPos);
            worker.setPose(Pose.SLEEPING);
            sleeping = true;
        }
    }

    /**
     * Wakes the worker up at dawn and resets all state so the goal can be
     * re-entered on the next night.
     */
    @Override
    public void stop() {
        if (worker.getPose() == Pose.SLEEPING) {
            worker.setPose(Pose.STANDING);
        }
        worker.clearSleepingPos();
        sleeping = false;
        bedPos   = null;
    }

    // ── Bed search ────────────────────────────────────────────────────────────

    /**
     * Finds the nearest bed-foot block within {@value #SLEEP_SEARCH_RADIUS}
     * blocks (XZ) and ±3 blocks (Y) of the worker and stores it in
     * {@link #bedPos}.  Sets {@code bedPos = null} when no bed is found.
     *
     * <p>Only the {@link BedPart#FOOT} half of each bed is targeted so that
     * the worker walks to the far end (feet end) and lies naturally.
     *
     * <p><b>Note:</b> {@link BlockPos#betweenClosed} reuses a mutable
     * {@link BlockPos} object on every iteration, so every stored position
     * must be {@linkplain BlockPos#immutable() immutable()}.
     */
    private void findBed() {
        Level    level  = worker.level();
        BlockPos origin = worker.blockPosition();
        int      r      = SLEEP_SEARCH_RADIUS;

        BlockPos nearest     = null;
        double   nearestDist = Double.MAX_VALUE;

        for (BlockPos p : BlockPos.betweenClosed(
                origin.offset(-r, -3, -r),
                origin.offset( r,  3,  r))) {

            BlockState state = level.getBlockState(p);
            if (!(state.getBlock() instanceof BedBlock)) continue;
            if (state.getValue(BedBlock.PART) != BedPart.FOOT) continue;

            double dist = p.distSqr(origin);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = p.immutable();
            }
        }

        bedPos = nearest;
    }
}
