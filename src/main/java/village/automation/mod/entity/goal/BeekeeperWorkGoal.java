package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.phys.AABB;
import village.automation.mod.blockentity.BeekeeperBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * AI goal for the beekeeper villager.
 *
 * <p>Phases:
 * <ol>
 *   <li>{@code IDLE} — decide what to do next.</li>
 *   <li>{@code APPROACH_BEE} — navigate toward an unclaimed bee.</li>
 *   <li>{@code CLAIM_BEE} — close enough; add bee to the block entity's claimed set.</li>
 *   <li>{@code STAND} — all bee slots full; stand near the beekeeper block and supervise.</li>
 * </ol>
 *
 * <p>The goal runs only when the worker's job is {@link JobType#BEEKEEPER} and the worker
 * has enough food to work.
 */
public class BeekeeperWorkGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final double WALK_SPEED      = 0.6;
    private static final double CLAIM_REACH_SQ  = 9.0;   // 3 block reach, squared
    private static final double BLOCK_REACH_SQ  = 16.0;  // 4 block reach
    private static final int    NAV_TIMEOUT     = 400;   // 20 s
    private static final int    IDLE_RECHECK    = 40;    // recheck every 2 s
    private static final int    SEARCH_RADIUS   = 48;    // blocks around heart

    // ── State ─────────────────────────────────────────────────────────────────
    private enum Phase { IDLE, APPROACH_BEE, CLAIM_BEE, STAND }

    private final VillagerWorkerEntity keeper;
    private Phase  phase      = Phase.IDLE;
    private int    navTimeout = 0;
    private int    idleTick   = 0;

    @Nullable private Bee    targetBee       = null;
    @Nullable private BlockPos blockPos      = null;

    public BeekeeperWorkGoal(VillagerWorkerEntity keeper) {
        this.keeper = keeper;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── canUse / canContinueToUse ─────────────────────────────────────────────

    @Override
    public boolean canUse() {
        return keeper.getJob() == JobType.BEEKEEPER
                && !keeper.isTooHungryToWork()
                && !keeper.level().isClientSide()
                && findBlockEntity() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return keeper.getJob() == JobType.BEEKEEPER
                && !keeper.isTooHungryToWork()
                && !keeper.level().isClientSide();
    }

    @Override
    public void stop() {
        phase      = Phase.IDLE;
        targetBee  = null;
        navTimeout = 0;
        keeper.getNavigation().stop();
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (keeper.level().isClientSide()) return;

        BeekeeperBlockEntity be = findBlockEntity();
        if (be == null) { phase = Phase.IDLE; return; }
        blockPos = be.getBlockPos();

        switch (phase) {
            case IDLE       -> tickIdle(be);
            case APPROACH_BEE -> tickApproachBee(be);
            case CLAIM_BEE  -> tickClaimBee(be);
            case STAND      -> tickStand(be);
        }
    }

    // ── Phase handlers ────────────────────────────────────────────────────────

    private void tickIdle(BeekeeperBlockEntity be) {
        if (++idleTick < IDLE_RECHECK) return;
        idleTick = 0;

        // Prune dead bees from claimed set
        if (keeper.level() instanceof ServerLevel sl) {
            for (java.util.UUID uuid : new java.util.HashSet<>(be.getClaimedBees())) {
                var entity = sl.getEntity(uuid);
                if (entity == null || !entity.isAlive()) {
                    be.unclaimBee(uuid);
                }
            }
        }

        if (be.canClaimMoreBees()) {
            Bee bee = findNearbyUnclaimedBee(be);
            if (bee != null) {
                targetBee = bee;
                navigateTo(bee.getX(), bee.getY(), bee.getZ());
                phase = Phase.APPROACH_BEE;
                navTimeout = NAV_TIMEOUT;
                return;
            }
        }

        // No bees to claim: stand near the block
        phase = Phase.STAND;
    }

    private void tickApproachBee(BeekeeperBlockEntity be) {
        if (targetBee == null || !targetBee.isAlive() || be.hasClaimed(targetBee.getUUID())) {
            phase = Phase.IDLE;
            return;
        }
        // Update navigation toward moving bee every 20 ticks
        if (keeper.tickCount % 20 == 0) {
            navigateTo(targetBee.getX(), targetBee.getY(), targetBee.getZ());
        }
        if (--navTimeout <= 0) { phase = Phase.IDLE; return; }

        double distSq = keeper.distanceToSqr(targetBee);
        if (distSq <= CLAIM_REACH_SQ) {
            phase = Phase.CLAIM_BEE;
        }
    }

    private void tickClaimBee(BeekeeperBlockEntity be) {
        if (targetBee == null || !targetBee.isAlive()) {
            phase = Phase.IDLE;
            return;
        }
        be.claimBee(targetBee.getUUID());
        // Prevent the bee from wandering too far from the village heart
        BlockPos heartPos = be.getLinkedHeartPos() != null ? be.getLinkedHeartPos() : blockPos;
        targetBee.restrictTo(heartPos, SEARCH_RADIUS);

        targetBee  = null;
        phase = Phase.IDLE;
    }

    private void tickStand(BeekeeperBlockEntity be) {
        if (be.canClaimMoreBees()) {
            // A slot opened up; go back to idle to look for more bees
            phase = Phase.IDLE;
            return;
        }
        // Walk to the block and stand nearby
        if (blockPos != null) {
            double distSq = keeper.distanceToSqr(
                    blockPos.getX() + 0.5, blockPos.getY(), blockPos.getZ() + 0.5);
            if (distSq > BLOCK_REACH_SQ) {
                navigateTo(blockPos.getX() + 0.5, blockPos.getY(), blockPos.getZ() + 0.5);
            } else {
                keeper.getNavigation().stop();
            }
        }
        // Stand quietly — no status method on VillagerWorkerEntity
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Finds the nearest unclaimed Bee within the search radius of the heart. */
    @Nullable
    private Bee findNearbyUnclaimedBee(BeekeeperBlockEntity be) {
        BlockPos centre = be.getLinkedHeartPos() != null ? be.getLinkedHeartPos() : blockPos;
        if (centre == null) return null;

        AABB searchBox = new AABB(centre).inflate(SEARCH_RADIUS, 16, SEARCH_RADIUS);
        List<Bee> bees = keeper.level().getEntitiesOfClass(Bee.class, searchBox,
                b -> b.isAlive() && !be.hasClaimed(b.getUUID()));

        if (bees.isEmpty()) return null;
        bees.sort(Comparator.comparingDouble(b -> b.distanceToSqr(keeper)));
        return bees.get(0);
    }

    /** Resolves the block entity from the keeper's workplacePos. */
    @Nullable
    private BeekeeperBlockEntity findBlockEntity() {
        BlockPos wp = keeper.getWorkplacePos();
        if (wp == null) return null;
        var be = keeper.level().getBlockEntity(wp);
        return be instanceof BeekeeperBlockEntity bk ? bk : null;
    }

    private void navigateTo(double x, double y, double z) {
        keeper.getNavigation().moveTo(x, y, z, WALK_SPEED);
        navTimeout = NAV_TIMEOUT;
    }
}
