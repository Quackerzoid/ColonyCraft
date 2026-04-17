package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.phys.AABB;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.BeekeeperBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillageBeeEntity;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * AI goal for the beekeeper villager.
 *
 * <h3>Phase loop</h3>
 * <pre>
 *   APPROACH_BLOCK ──── arrive ────▶ WORK ──── bee found ──▶ APPROACH_BEE
 *         ▲                           │                            │
 *         │                           │ (knocked away)             ▼
 *         └───────────────────────────┘                       CLAIM_BEE
 *                                                                  │
 *                                                     ─────────────┘
 * </pre>
 *
 * <p>While in {@code WORK} the keeper stands at the block and calls
 * {@link BeekeeperBlockEntity#markWorkerPresent()} every tick, which allows the
 * smoking cycle to advance.  Every {@value #WORK_RECHECK} ticks it also scans
 * for claimable wild bees nearby.
 */
public class BeekeeperWorkGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final double WALK_SPEED     = 0.6;
    private static final double CLAIM_REACH_SQ = 9.0;   // 3-block reach, squared
    /** Keeper is "at" the block when within this many blocks (squared). */
    private static final double BLOCK_REACH_SQ = 16.0;  // 4 blocks
    private static final int    NAV_RETRY      = 60;    // re-issue moveTo every 3 s while approaching
    private static final int    WORK_RECHECK   = 40;    // look for bees every 2 s while working
    private static final int    SEARCH_RADIUS  = 48;

    private enum Phase { APPROACH_BLOCK, WORK, APPROACH_BEE, CLAIM_BEE }

    private final VillagerWorkerEntity keeper;
    private Phase  phase    = Phase.APPROACH_BLOCK;
    private int    navRetry = 0;
    private int    workTick = 0;

    @Nullable private Bee      targetBee = null;
    @Nullable private BlockPos blockPos  = null;

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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void start() {
        phase    = Phase.APPROACH_BLOCK;
        targetBee = null;
        navRetry = 0;
        workTick = 0;
        navigateToBlock();
    }

    @Override
    public void stop() {
        phase    = Phase.APPROACH_BLOCK;
        targetBee = null;
        navRetry = 0;
        workTick = 0;
        keeper.getNavigation().stop();
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (keeper.level().isClientSide()) return;

        BeekeeperBlockEntity be = findBlockEntity();
        if (be == null) return;
        blockPos = be.getBlockPos();

        switch (phase) {
            case APPROACH_BLOCK -> tickApproachBlock();
            case WORK           -> tickWork(be);
            case APPROACH_BEE   -> tickApproachBee(be);
            case CLAIM_BEE      -> tickClaimBee(be);
        }
    }

    // ── Phase handlers ────────────────────────────────────────────────────────

    private void tickApproachBlock() {
        if (blockPos == null) return;

        // Re-issue moveTo regularly so the pathfinder doesn't give up silently
        if (++navRetry >= NAV_RETRY) {
            navRetry = 0;
            navigateToBlock();
        }

        if (isNearBlock()) {
            keeper.getNavigation().stop();
            workTick = 0;
            phase = Phase.WORK;
        }
    }

    private void tickWork(BeekeeperBlockEntity be) {
        // Mark presence every tick — this is what gates tickSmoking in the block entity
        be.markWorkerPresent();

        // Look down at the beehive while working
        if (blockPos != null) {
            keeper.getLookControl().setLookAt(
                    blockPos.getX() + 0.5,
                    blockPos.getY() + 0.5,
                    blockPos.getZ() + 0.5);
        }

        // If knocked away, walk back
        if (!isNearBlock()) {
            navigateToBlock();
            phase = Phase.APPROACH_BLOCK;
            return;
        }

        // Every WORK_RECHECK ticks, scan for a wild bee to claim
        if (++workTick >= WORK_RECHECK) {
            workTick = 0;
            if (be.canClaimMoreBees()) {
                Bee bee = findNearbyUnclaimedBee(be);
                if (bee != null) {
                    targetBee = bee;
                    navigateTo(bee.getX(), bee.getY(), bee.getZ());
                    phase = Phase.APPROACH_BEE;
                }
            }
        }
    }

    private void tickApproachBee(BeekeeperBlockEntity be) {
        if (targetBee == null || !targetBee.isAlive() || be.hasClaimed(targetBee.getUUID())) {
            returnToBlock();
            return;
        }
        // Refresh nav toward moving bee every second
        if (keeper.tickCount % 20 == 0) {
            navigateTo(targetBee.getX(), targetBee.getY(), targetBee.getZ());
        }
        if (keeper.distanceToSqr(targetBee) <= CLAIM_REACH_SQ) {
            phase = Phase.CLAIM_BEE;
        }
    }

    private void tickClaimBee(BeekeeperBlockEntity be) {
        if (targetBee == null || !targetBee.isAlive()) {
            returnToBlock();
            return;
        }
        if (!(keeper.level() instanceof ServerLevel sl)) {
            returnToBlock();
            return;
        }

        // Convert the wild bee into a domesticated VillageBeeEntity
        VillageBeeEntity villageBee = new VillageBeeEntity(VillageMod.VILLAGE_BEE.get(), sl);
        villageBee.copyPosition(targetBee);
        villageBee.setYHeadRot(targetBee.getYHeadRot());
        if (blockPos != null) villageBee.setHomePos(blockPos);
        BlockPos heartPos = be.getLinkedHeartPos();
        if (heartPos != null) villageBee.setHeartPos(heartPos);

        targetBee.discard();
        sl.addFreshEntity(villageBee);
        be.claimBee(villageBee.getUUID());

        targetBee = null;
        returnToBlock();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isNearBlock() {
        if (blockPos == null) return false;
        return keeper.distanceToSqr(
                blockPos.getX() + 0.5,
                blockPos.getY() + 0.5,
                blockPos.getZ() + 0.5) <= BLOCK_REACH_SQ;
    }

    private void returnToBlock() {
        targetBee = null;
        navRetry  = 0;
        navigateToBlock();
        phase = Phase.APPROACH_BLOCK;
    }

    @Nullable
    private Bee findNearbyUnclaimedBee(BeekeeperBlockEntity be) {
        BlockPos centre = be.getLinkedHeartPos() != null ? be.getLinkedHeartPos() : blockPos;
        if (centre == null) return null;

        AABB searchBox = new AABB(centre).inflate(SEARCH_RADIUS, 16, SEARCH_RADIUS);
        List<Bee> bees = keeper.level().getEntitiesOfClass(Bee.class, searchBox,
                b -> b.isAlive()
                     && !(b instanceof VillageBeeEntity)
                     && !be.hasClaimed(b.getUUID()));

        if (bees.isEmpty()) return null;
        bees.sort(Comparator.comparingDouble(b -> b.distanceToSqr(keeper)));
        return bees.get(0);
    }

    @Nullable
    private BeekeeperBlockEntity findBlockEntity() {
        BlockPos wp = keeper.getWorkplacePos();
        if (wp == null) return null;
        var be = keeper.level().getBlockEntity(wp);
        return be instanceof BeekeeperBlockEntity bk ? bk : null;
    }

    private void navigateTo(double x, double y, double z) {
        keeper.getNavigation().moveTo(x, y, z, WALK_SPEED);
    }

    /**
     * Navigate to the air block directly above the beehive so the pathfinder
     * always gets a reachable, non-solid target.
     */
    private void navigateToBlock() {
        if (blockPos != null) {
            navigateTo(blockPos.getX() + 0.5,
                       blockPos.getY() + 1.0,   // above the solid block — always reachable
                       blockPos.getZ() + 0.5);
        }
    }
}
