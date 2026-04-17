package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.phys.AABB;
import village.automation.mod.blockentity.ButcherBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * AI goal for the Butcher worker.
 *
 * <h3>Phase loop</h3>
 * <pre>
 *   APPROACH_ANIMAL ── arrive within reach ──▶ LEASH_ANIMAL
 *         ▲                                         │
 *         │ (no target / lost)               leash + navigate home
 *         │                                         ▼
 *         └──────────────── reset ◀──── LEAD_HOME ──┤
 *                                                    │ keeper at block, animal near
 *                                                    ▼
 *                                              KILL_ANIMAL
 *                                                    │ (brief wait)
 *                                                    ▼
 *                                              COLLECT_DROPS
 * </pre>
 *
 * <p>The butcher activates only when:
 * <ul>
 *   <li>job == {@link JobType#BUTCHER} and the worker is not too hungry,</li>
 *   <li>a {@link SwordItem} is present in the tool container,</li>
 *   <li>a valid butcher block entity is linked as the workplace, and</li>
 *   <li>at least {@value #MIN_TO_TRIGGER} adults of the same species are
 *       grouped within a 16-block area.</li>
 * </ul>
 */
public class ButcherWorkGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final double WALK_SPEED        = 0.6;
    private static final double APPROACH_REACH_SQ = 9.0;   // 3-block reach
    private static final double BLOCK_REACH_SQ    = 9.0;   // 3 blocks from butcher block
    /** Animal must be within this distance (sq) of the block before the kill. */
    private static final double ANIMAL_NEAR_SQ    = 36.0;  // 6 blocks
    private static final int    NAV_RETRY         = 60;    // re-path every 3 s
    /** Ticks to wait after kill before sweeping ItemEntities. */
    private static final int    KILL_WAIT         = 5;
    /** Minimum adults of same species nearby to trigger butchering. */
    private static final int    MIN_TO_TRIGGER    = 3;
    /** Half-width of the heart-centred scan box. */
    private static final int    SEARCH_RADIUS     = 64;
    /** Radius used to count nearby same-species adults. */
    private static final int    GROUP_RADIUS      = 16;

    private enum Phase { APPROACH_ANIMAL, LEASH_ANIMAL, LEAD_HOME, KILL_ANIMAL, COLLECT_DROPS }

    // ── State ─────────────────────────────────────────────────────────────────
    private final VillagerWorkerEntity keeper;
    private Phase   phase        = Phase.APPROACH_ANIMAL;
    private int     navRetry     = 0;
    private int     killTimer    = 0;
    @Nullable private Animal   targetAnimal = null;
    @Nullable private BlockPos killPos      = null;

    // ── Construction ──────────────────────────────────────────────────────────

    public ButcherWorkGoal(VillagerWorkerEntity keeper) {
        this.keeper = keeper;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── Gate conditions ───────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        return keeper.getJob() == JobType.BUTCHER
                && !keeper.isTooHungryToWork()
                && !keeper.level().isClientSide()
                && hasSword()
                && findBlockEntity() != null
                && findValidTarget() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return keeper.getJob() == JobType.BUTCHER
                && !keeper.isTooHungryToWork()
                && !keeper.level().isClientSide()
                && hasSword();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void start() {
        phase        = Phase.APPROACH_ANIMAL;
        navRetry     = 0;
        killTimer    = 0;
        killPos      = null;
        targetAnimal = findValidTarget();
        if (targetAnimal != null) {
            navigateTo(targetAnimal.getX(), targetAnimal.getY(), targetAnimal.getZ());
        }
        equipSword();
    }

    @Override
    public void stop() {
        phase     = Phase.APPROACH_ANIMAL;
        navRetry  = 0;
        killTimer = 0;
        killPos   = null;
        if (targetAnimal != null) {
            if (targetAnimal.isLeashed()) {
                targetAnimal.dropLeash(true, true);
            }
            targetAnimal = null;
        }
        keeper.getNavigation().stop();
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (!(keeper.level() instanceof ServerLevel level)) return;
        ButcherBlockEntity be = findBlockEntity();
        if (be == null) { stop(); return; }

        switch (phase) {
            case APPROACH_ANIMAL -> tickApproachAnimal();
            case LEASH_ANIMAL    -> tickLeashAnimal(be);
            case LEAD_HOME       -> tickLeadHome(be);
            case KILL_ANIMAL     -> tickKillAnimal(level);
            case COLLECT_DROPS   -> tickCollectDrops(level, be);
        }
    }

    // ── Phase handlers ────────────────────────────────────────────────────────

    private void tickApproachAnimal() {
        if (targetAnimal == null || !targetAnimal.isAlive()) {
            targetAnimal = findValidTarget();
            if (targetAnimal == null) return;
        }
        if (++navRetry >= NAV_RETRY) {
            navRetry = 0;
            navigateTo(targetAnimal.getX(), targetAnimal.getY(), targetAnimal.getZ());
        }
        keeper.getLookControl().setLookAt(targetAnimal, 30f, 30f);
        if (keeper.distanceToSqr(targetAnimal) <= APPROACH_REACH_SQ) {
            keeper.getNavigation().stop();
            phase    = Phase.LEASH_ANIMAL;
            navRetry = 0;
        }
    }

    private void tickLeashAnimal(ButcherBlockEntity be) {
        if (targetAnimal == null || !targetAnimal.isAlive()) {
            targetAnimal = null;
            phase = Phase.APPROACH_ANIMAL;
            return;
        }
        // Attach lead and walk home — the animal will follow
        targetAnimal.setLeashedTo(keeper, true);
        navigateToBlock(be);
        navRetry = 0;
        phase    = Phase.LEAD_HOME;
    }

    private void tickLeadHome(ButcherBlockEntity be) {
        BlockPos blockPos = be.getBlockPos();
        if (++navRetry >= NAV_RETRY) {
            navRetry = 0;
            navigateToBlock(be);
        }
        if (targetAnimal == null || !targetAnimal.isAlive()) {
            targetAnimal = null;
            phase = Phase.APPROACH_ANIMAL;
            return;
        }
        double keeperDist = keeper.distanceToSqr(
                blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
        if (keeperDist <= BLOCK_REACH_SQ) {
            double animalDist = targetAnimal.distanceToSqr(
                    blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
            if (animalDist <= ANIMAL_NEAR_SQ) {
                keeper.getNavigation().stop();
                phase     = Phase.KILL_ANIMAL;
                killTimer = KILL_WAIT;
            }
        }
    }

    private void tickKillAnimal(ServerLevel level) {
        if (targetAnimal == null || !targetAnimal.isAlive()) {
            targetAnimal = null;
            phase = Phase.APPROACH_ANIMAL;
            return;
        }
        keeper.getLookControl().setLookAt(targetAnimal, 30f, 30f);
        if (--killTimer > 0) return;

        // Release lead (keep the lead in inventory — pass dropItem=false)
        targetAnimal.dropLeash(true, false);
        killPos = targetAnimal.blockPosition();

        // Mob-attack damage triggers normal loot tables
        targetAnimal.hurt(level.damageSources().mobAttack(keeper), Float.MAX_VALUE);
        targetAnimal = null;

        killTimer = KILL_WAIT;   // brief pause before sweeping drops
        phase     = Phase.COLLECT_DROPS;
    }

    private void tickCollectDrops(ServerLevel level, ButcherBlockEntity be) {
        if (--killTimer > 0) return;
        if (killPos == null) { phase = Phase.APPROACH_ANIMAL; return; }

        AABB sweepBox = new AABB(killPos).inflate(4.0, 2.0, 4.0);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, sweepBox,
                e -> e.isAlive() && !e.getItem().isEmpty());

        for (ItemEntity ie : drops) {
            ItemStack stack = ie.getItem().copy();
            if (depositIntoOutput(stack, be)) {
                ie.discard();
            }
        }

        killPos  = null;
        phase    = Phase.APPROACH_ANIMAL;
        navRetry = 0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean hasSword() {
        ItemStack tool = keeper.getToolContainer().getItem(0);
        return tool.getItem() instanceof SwordItem;
    }

    private void equipSword() {
        ItemStack tool = keeper.getToolContainer().getItem(0);
        if (tool.getItem() instanceof SwordItem) {
            keeper.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
        }
    }

    /**
     * Scans within the heart/workplace area for an adult animal whose species
     * has at least {@value #MIN_TO_TRIGGER} adults within {@value #GROUP_RADIUS} blocks.
     * Returns the nearest qualifying animal.
     */
    @Nullable
    private Animal findValidTarget() {
        if (keeper.level().isClientSide()) return null;
        BlockPos centre = getHeartOrWorkplace();
        if (centre == null) return null;

        AABB searchBox = new AABB(centre).inflate(SEARCH_RADIUS, 16, SEARCH_RADIUS);
        List<Animal> candidates = keeper.level().getEntitiesOfClass(Animal.class, searchBox,
                a -> a.isAlive() && !a.isBaby() && !(a instanceof Bee));
        if (candidates.isEmpty()) return null;

        // Closest-first so the butcher takes a short path
        candidates.sort(Comparator.comparingDouble(a -> a.distanceToSqr(keeper)));
        for (Animal candidate : candidates) {
            long nearbyCount = keeper.level().getEntitiesOfClass(
                    candidate.getClass(),
                    new AABB(candidate.blockPosition()).inflate(GROUP_RADIUS, 8, GROUP_RADIUS),
                    a -> a.isAlive() && !a.isBaby() && !(a instanceof Bee)).size();
            if (nearbyCount >= MIN_TO_TRIGGER) return candidate;
        }
        return null;
    }

    /**
     * Attempts to deposit {@code stack} into the butcher block output container.
     * Returns {@code true} if the entire stack was placed.
     */
    private static boolean depositIntoOutput(ItemStack stack, ButcherBlockEntity be) {
        var output = be.getOutputContainer();
        // Merge with existing stacks
        for (int i = 0; i < output.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = output.getItem(i);
            if (slot.is(stack.getItem()) && slot.getCount() < slot.getMaxStackSize()) {
                int move = Math.min(slot.getMaxStackSize() - slot.getCount(), stack.getCount());
                slot.grow(move);
                stack.shrink(move);
                output.setItem(i, slot);
            }
        }
        // Fill empty slots
        for (int i = 0; i < output.getContainerSize() && !stack.isEmpty(); i++) {
            if (output.getItem(i).isEmpty()) {
                output.setItem(i, stack.copy());
                stack = ItemStack.EMPTY;
            }
        }
        return stack.isEmpty();
    }

    @Nullable
    private ButcherBlockEntity findBlockEntity() {
        BlockPos wp = keeper.getWorkplacePos();
        if (wp == null) return null;
        var be = keeper.level().getBlockEntity(wp);
        return be instanceof ButcherBlockEntity b ? b : null;
    }

    @Nullable
    private BlockPos getHeartOrWorkplace() {
        if (!(keeper.level() instanceof ServerLevel sl)) return null;
        var heart = VillageHeartBlockEntity.findClaimingHeart(sl, keeper.blockPosition(), null);
        return heart.orElseGet(keeper::getWorkplacePos);
    }

    private void navigateTo(double x, double y, double z) {
        keeper.getNavigation().moveTo(x, y, z, WALK_SPEED);
    }

    private void navigateToBlock(ButcherBlockEntity be) {
        BlockPos pos = be.getBlockPos();
        navigateTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }
}
