package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.entity.VillageBeeEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Drives the pollination cycle for a {@link VillageBeeEntity}.
 *
 * <h3>Phase loop</h3>
 * <pre>
 *   IDLE  ──── find crop ───▶  SEEK_CROP  ──── arrive ───▶  POLLINATE
 *    ▲                                                           │
 *    │                                                     advance crop
 *    │                                                           │
 *  REST  ◀── deposit pollen ──  RETURN_HOME  ◀── nav home ──────┘
 * </pre>
 *
 * <ul>
 *   <li>No crops found → bee flies to the home block and rests.</li>
 *   <li>Navigation timeouts reset to IDLE so the bee never gets stuck.</li>
 * </ul>
 */
public class VillageBeeGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final int    SEARCH_RADIUS   = 48;   // blocks around heart/home
    private static final int    POLLINATE_TICKS = 60;   // 3 s hovering near crop
    private static final int    REST_TICKS      = 100;  // 5 s resting at home block
    private static final int    NAV_TIMEOUT     = 400;  // 20 s max nav before retry
    private static final double FLY_SPEED       = 0.6;
    private static final double NEAR_DIST_SQ    = 9.0;  // 3-block radius, squared
    private static final int    IDLE_RATE       = 20;   // re-evaluate IDLE every 1 s

    private enum Phase { IDLE, SEEK_CROP, POLLINATE, RETURN_HOME, REST }

    // ── State ─────────────────────────────────────────────────────────────────
    private final VillageBeeEntity bee;
    private Phase  phase     = Phase.IDLE;
    private int    phaseTick = 0;    // countdown used in POLLINATE and REST
    private int    navTimeout = 0;
    private int    idleTick   = 0;

    @Nullable private BlockPos targetCrop = null;

    public VillageBeeGoal(VillageBeeEntity bee) {
        this.bee = bee;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── canUse / canContinueToUse ─────────────────────────────────────────────

    @Override
    public boolean canUse() {
        return !bee.level().isClientSide() && bee.getHomePos() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !bee.level().isClientSide() && bee.isAlive() && bee.getHomePos() != null;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (!(bee.level() instanceof ServerLevel level)) return;
        if (bee.getHomePos() == null) return;

        switch (phase) {
            case IDLE        -> tickIdle(level);
            case SEEK_CROP   -> tickSeekCrop(level);
            case POLLINATE   -> tickPollinate(level);
            case RETURN_HOME -> tickReturnHome(level);
            case REST        -> tickRest();
        }
    }

    // ── Phase handlers ────────────────────────────────────────────────────────

    private void tickIdle(ServerLevel level) {
        if (++idleTick < IDLE_RATE) return;
        idleTick = 0;

        BlockPos centre = bee.getHeartPos() != null ? bee.getHeartPos() : bee.getHomePos();

        BlockPos crop = findNearestImmatureCrop(level, centre);
        if (crop != null) {
            targetCrop = crop;
            // Aim slightly above the crop so the bee hovers at crop-top height
            flyTo(crop.getX() + 0.5, crop.getY() + 1.5, crop.getZ() + 0.5);
            navTimeout = NAV_TIMEOUT;
            phase = Phase.SEEK_CROP;
        } else {
            // No crops to pollinate — rest near home
            flyToHome();
            navTimeout = NAV_TIMEOUT;
            phase = Phase.REST;
            phaseTick = REST_TICKS;
        }
    }

    private void tickSeekCrop(ServerLevel level) {
        // Abort if target crop was harvested/grown to max age
        if (targetCrop == null) { resetToIdle(); return; }

        BlockState state = level.getBlockState(targetCrop);
        if (!(state.getBlock() instanceof CropBlock crop) || crop.isMaxAge(state)) {
            resetToIdle();
            return;
        }

        if (--navTimeout <= 0) { resetToIdle(); return; }

        // Refresh navigation toward the crop periodically
        if (bee.tickCount % 20 == 0) {
            flyTo(targetCrop.getX() + 0.5, targetCrop.getY() + 1.5, targetCrop.getZ() + 0.5);
        }

        double dist = bee.distanceToSqr(
                targetCrop.getX() + 0.5, targetCrop.getY() + 1.5, targetCrop.getZ() + 0.5);
        if (dist <= NEAR_DIST_SQ) {
            bee.getNavigation().stop();
            phase = Phase.POLLINATE;
            phaseTick = POLLINATE_TICKS;
        }
    }

    private void tickPollinate(ServerLevel level) {
        if (--phaseTick > 0) return;

        // Advance the crop one stage
        if (targetCrop != null) {
            BlockState state = level.getBlockState(targetCrop);
            if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                BlockState advanced = crop.getStateForAge(
                        Math.min(crop.getAge(state) + 1, crop.getMaxAge()));
                level.setBlock(targetCrop, advanced, 3);
            }
        }
        targetCrop = null;

        flyToHome();
        navTimeout = NAV_TIMEOUT;
        phase = Phase.RETURN_HOME;
    }

    private void tickReturnHome(ServerLevel level) {
        if (--navTimeout <= 0) { resetToIdle(); return; }

        BlockPos home = bee.getHomePos();
        if (home == null) { resetToIdle(); return; }

        // Refresh nav toward home periodically
        if (bee.tickCount % 20 == 0) {
            flyToHome();
        }

        double dist = bee.distanceToSqr(
                home.getX() + 0.5, home.getY() + 1.0, home.getZ() + 0.5);
        if (dist <= NEAR_DIST_SQ) {
            bee.getNavigation().stop();
            bee.depositPollen(level);
            phase = Phase.REST;
            phaseTick = REST_TICKS;
        }
    }

    private void tickRest() {
        // Drift back toward home if the bee wanders
        if (bee.tickCount % 40 == 0) {
            BlockPos home = bee.getHomePos();
            if (home != null) {
                double dist = bee.distanceToSqr(
                        home.getX() + 0.5, home.getY() + 1.0, home.getZ() + 0.5);
                if (dist > NEAR_DIST_SQ * 4) {
                    flyToHome();
                }
            }
        }
        if (--phaseTick <= 0) {
            phase = Phase.IDLE;
            idleTick = 0;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void resetToIdle() {
        targetCrop = null;
        navTimeout = 0;
        idleTick   = 0;
        phase      = Phase.IDLE;
        bee.getNavigation().stop();
    }

    /**
     * Scans for the nearest immature crop within {@value #SEARCH_RADIUS} blocks
     * of {@code centre}.  Returns {@code null} if none found.
     */
    @Nullable
    private BlockPos findNearestImmatureCrop(ServerLevel level, BlockPos centre) {
        if (centre == null) return null;
        BlockPos best     = null;
        double   bestDist = Double.MAX_VALUE;

        for (BlockPos scan : BlockPos.betweenClosed(
                centre.offset(-SEARCH_RADIUS, -8, -SEARCH_RADIUS),
                centre.offset( SEARCH_RADIUS,  8,  SEARCH_RADIUS))) {
            BlockState state = level.getBlockState(scan);
            if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                double d = scan.distSqr(centre);
                if (d < bestDist) {
                    bestDist = d;
                    best     = scan.immutable();
                }
            }
        }
        return best;
    }

    private void flyTo(double x, double y, double z) {
        bee.getNavigation().moveTo(x, y, z, FLY_SPEED);
    }

    private void flyToHome() {
        BlockPos home = bee.getHomePos();
        if (home != null) {
            flyTo(home.getX() + 0.5, home.getY() + 1.0, home.getZ() + 0.5);
        }
    }
}
