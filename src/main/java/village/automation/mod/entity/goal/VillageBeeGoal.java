package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.entity.VillageBeeEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Drives the pollination cycle for a {@link VillageBeeEntity}.
 *
 * <h3>Phase loop</h3>
 * <pre>
 *   IDLE  ──── find crop ───▶  SEEK_CROP  ──── arrive ───▶  POLLINATE (spins)
 *    ▲                                                           │
 *    │                                              pollen overlay ON, fly home
 *    │                                                           │
 *  REST  ◀── arrive home ──  RETURN_HOME (particles) ◀──────────┘
 *  (invisible, invulnerable inside hive)
 * </pre>
 *
 * <ul>
 *   <li>Crop selection is <em>random</em> each run — no two bees always pick the same patch.</li>
 *   <li>While spinning over a crop the bee spins on its Y-axis.</li>
 *   <li>While returning with pollen: {@code hasNectar} is set true (vanilla pollen overlay)
 *       and {@link ParticleTypes#FALLING_NECTAR} particles fall from the bee.</li>
 *   <li>On arrival the bee goes invisible and invulnerable inside the hive block,
 *       then reappears above the entrance when REST ends.</li>
 * </ul>
 */
public class VillageBeeGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final int    SEARCH_RADIUS   = 48;
    private static final int    POLLINATE_TICKS = 60;   // 3 s spinning over crop
    private static final int    REST_TICKS      = 100;  // 5 s resting inside hive
    private static final int    NAV_TIMEOUT     = 400;  // 20 s max nav before retry
    private static final double FLY_SPEED       = 0.6;
    /** Arrival threshold for the crop (≈1.2 blocks — essentially on top). */
    private static final double CROP_NEAR_SQ    = 1.5;
    /** Arrival threshold for the home block. */
    private static final double HOME_NEAR_SQ    = 4.0;
    private static final int    IDLE_RATE       = 20;   // re-evaluate IDLE every 1 s
    /** Degrees of Y-rotation added each tick while pollinating. */
    private static final float  SPIN_SPEED      = 18.0f;  // full rotation every 20 ticks

    private enum Phase { IDLE, SEEK_CROP, POLLINATE, RETURN_HOME, REST }

    // ── State ─────────────────────────────────────────────────────────────────
    private final VillageBeeEntity bee;
    private Phase   phase           = Phase.IDLE;
    private int     phaseTick       = 0;
    private int     navTimeout      = 0;
    private int     idleTick        = 0;
    /**
     * {@code true} when the current RETURN_HOME trip should deposit pollen and
     * show the nectar overlay.  {@code false} when just returning after no crops.
     */
    private boolean depositOnReturn = false;

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
        BlockPos crop   = findRandomImmatureCrop(level, centre);

        if (crop != null) {
            targetCrop      = crop;
            depositOnReturn = false;
            flyTo(crop.getX() + 0.5, crop.getY() + 0.5, crop.getZ() + 0.5);
            navTimeout = NAV_TIMEOUT;
            phase = Phase.SEEK_CROP;
        } else {
            // No crops — fly home and rest inside the hive
            depositOnReturn = false;
            flyToHome();
            navTimeout = NAV_TIMEOUT;
            phase = Phase.RETURN_HOME;
        }
    }

    private void tickSeekCrop(ServerLevel level) {
        if (targetCrop == null) { resetToIdle(); return; }

        BlockState state = level.getBlockState(targetCrop);
        if (!(state.getBlock() instanceof CropBlock crop) || crop.isMaxAge(state)) {
            resetToIdle();
            return;
        }

        if (--navTimeout <= 0) { resetToIdle(); return; }

        if (bee.tickCount % 20 == 0) {
            flyTo(targetCrop.getX() + 0.5, targetCrop.getY() + 0.5, targetCrop.getZ() + 0.5);
        }

        double dist = bee.distanceToSqr(
                targetCrop.getX() + 0.5, targetCrop.getY() + 0.5, targetCrop.getZ() + 0.5);
        if (dist <= CROP_NEAR_SQ) {
            bee.getNavigation().stop();
            phase     = Phase.POLLINATE;
            phaseTick = POLLINATE_TICKS;
        }
    }

    /** Bee hovers over the crop, spinning, then carries pollen home. */
    private void tickPollinate(ServerLevel level) {
        // Spin on Y-axis while pollinating
        float rot = bee.getYRot() + SPIN_SPEED;
        bee.setYRot(rot);
        bee.setYHeadRot(rot);

        if (--phaseTick > 0) return;

        // Advance the crop one stage
        if (targetCrop != null) {
            BlockState state = level.getBlockState(targetCrop);
            if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                level.setBlock(targetCrop, crop.getStateForAge(
                        Math.min(crop.getAge(state) + 1, crop.getMaxAge())), 3);
            }
        }
        targetCrop = null;

        // Carry pollen back — show vanilla nectar overlay
        depositOnReturn = true;
        bee.setCarryingPollen(true);

        flyToHome();
        navTimeout = NAV_TIMEOUT;
        phase = Phase.RETURN_HOME;
    }

    /**
     * Bee flies home with pollen particles trailing behind.
     * On arrival it turns invisible and invulnerable inside the hive.
     */
    private void tickReturnHome(ServerLevel level) {
        if (--navTimeout <= 0) { resetToIdle(); return; }

        BlockPos home = bee.getHomePos();
        if (home == null) { resetToIdle(); return; }

        // Refresh navigation periodically
        if (bee.tickCount % 20 == 0) flyToHome();

        // Spawn falling-nectar particles while carrying pollen
        if (depositOnReturn && bee.tickCount % 4 == 0) {
            level.sendParticles(ParticleTypes.FALLING_NECTAR,
                    bee.getX(), bee.getY() + bee.getBbHeight() * 0.5, bee.getZ(),
                    2, 0.2, 0.1, 0.2, 0.0);
        }

        double dist = bee.distanceToSqr(
                home.getX() + 0.5, home.getY() + 1.0, home.getZ() + 0.5);
        if (dist <= HOME_NEAR_SQ) {
            bee.getNavigation().stop();

            // Enter the hive: invisible + invulnerable to prevent suffocation damage
            bee.setInvisible(true);
            bee.setInvulnerable(true);
            bee.teleportTo(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5);

            if (depositOnReturn) {
                bee.depositPollen(level);
                bee.setCarryingPollen(false);
                depositOnReturn = false;
            }

            phase     = Phase.REST;
            phaseTick = REST_TICKS;
        }
    }

    /**
     * Bee rests inside the hive (invisible, invulnerable).
     * On REST end it reappears above the hive entrance.
     */
    private void tickRest() {
        // Refresh flags each tick in case of external interference
        bee.setInvisible(true);
        bee.setInvulnerable(true);

        if (--phaseTick <= 0) {
            // Exit the hive
            bee.setInvisible(false);
            bee.setInvulnerable(false);
            BlockPos home = bee.getHomePos();
            if (home != null) {
                bee.teleportTo(home.getX() + 0.5, home.getY() + 1.5, home.getZ() + 0.5);
            }
            phase    = Phase.IDLE;
            idleTick = 0;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void resetToIdle() {
        bee.setInvisible(false);
        bee.setInvulnerable(false);
        bee.setCarryingPollen(false);
        targetCrop      = null;
        navTimeout      = 0;
        idleTick        = 0;
        depositOnReturn = false;
        phase           = Phase.IDLE;
        bee.getNavigation().stop();
    }

    /**
     * Returns a <em>random</em> immature crop within {@value #SEARCH_RADIUS} blocks
     * of {@code centre}, or {@code null} if none exist.
     * Picking randomly prevents all bees from always visiting the same nearest patch.
     */
    @Nullable
    private BlockPos findRandomImmatureCrop(ServerLevel level, BlockPos centre) {
        if (centre == null) return null;

        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos scan : BlockPos.betweenClosed(
                centre.offset(-SEARCH_RADIUS, -8, -SEARCH_RADIUS),
                centre.offset( SEARCH_RADIUS,  8,  SEARCH_RADIUS))) {
            BlockState state = level.getBlockState(scan);
            if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                candidates.add(scan.immutable());
            }
        }

        if (candidates.isEmpty()) return null;
        return candidates.get(level.random.nextInt(candidates.size()));
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
