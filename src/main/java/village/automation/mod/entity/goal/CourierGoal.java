package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import village.automation.mod.ItemRequest;
import village.automation.mod.blockentity.CookingBlockEntity;
import village.automation.mod.blockentity.FarmBlockEntity;
import village.automation.mod.blockentity.LumbermillBlockEntity;
import village.automation.mod.blockentity.MineBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.CourierEntity;
import village.automation.mod.entity.SmithRecipe;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * Main AI for the courier. Runs a simple state machine:
 *
 * <pre>
 *  IDLE ──► FETCH  (navigate to a chest and extract a needed material)
 *       ──► DEPOSIT_TO_SMITH  (navigate to smith and hand off carried items)
 *       ──► PICKUP_FROM_SMITH (navigate to smith and collect the finished tool)
 *       ──► DELIVER_TO_WORKER (navigate to requesting worker and equip tool)
 * </pre>
 *
 * Timeouts are applied to each navigation leg; on expiry the courier resets to
 * IDLE so it never gets permanently stuck.
 */
public class CourierGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final double REACH_SQ       = 9.0;   // 3-block reach, squared
    private static final double WALK_SPEED     = 0.8;
    private static final int    NAV_TIMEOUT    = 400;   // 20 s before giving up

    // ── State ─────────────────────────────────────────────────────────────────
    private enum Phase { IDLE, FETCH, DEPOSIT_TO_SMITH, PICKUP_FROM_SMITH, DELIVER_TO_WORKER,
                         GATHER_FROM_BLOCK, DEPOSIT_GATHERED,
                         DELIVER_TO_COOKING, PICKUP_FROM_COOKING }

    private final CourierEntity courier;
    private Phase   phase           = Phase.IDLE;
    private int     navTimeout      = 0;
    private int     idleTick        = 0;

    /** The chest the courier is currently heading towards. */
    @Nullable private BlockPos targetChestPos = null;
    /** The ingredient the courier is trying to collect from {@link #targetChestPos}. */
    @Nullable private SmithRecipe.Ingredient targetIngredient = null;
    /** Amount of {@link #targetIngredient} still needed from that chest. */
    private int targetAmount = 0;
    /** UUID of the worker to deliver the finished tool to. */
    @Nullable private java.util.UUID deliveryWorkerUUID = null;

    /** True when the courier is fulfilling a request via direct chest→worker delivery (no smith). */
    private boolean directDelivery = false;
    @Nullable private java.util.UUID directDeliveryWorkerUUID = null;

    /** Workplace block the courier is currently gathering from (mine/farm). */
    @Nullable private BlockPos targetWorkplacePos = null;

    /** Cooking block the courier is currently delivering wheat to, or picking bread from. */
    @Nullable private BlockPos targetCookingPos = null;

    public CourierGoal(CourierEntity courier) {
        this.courier = courier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        return courier.getLinkedHeartPos() != null
                && courier.level() instanceof ServerLevel;
    }

    @Override public boolean canContinueToUse() { return canUse(); }

    @Override public void start() { phase = Phase.IDLE; navTimeout = 0; }

    @Override
    public void stop() {
        courier.getNavigation().stop();
        phase = Phase.IDLE;
    }

    @Override
    public void tick() {
        if (!(courier.level() instanceof ServerLevel level)) return;

        // Navigation timeout guard
        if (phase != Phase.IDLE && --navTimeout <= 0) {
            resetToIdle();
            return;
        }

        switch (phase) {
            case IDLE              -> tickIdle(level);
            case FETCH             -> tickFetch(level);
            case DEPOSIT_TO_SMITH  -> tickDeposit(level);
            case PICKUP_FROM_SMITH -> tickPickup(level);
            case DELIVER_TO_WORKER -> tickDeliver(level);
            case GATHER_FROM_BLOCK  -> tickGatherFromBlock(level);
            case DEPOSIT_GATHERED   -> tickDepositGathered(level);
            case DELIVER_TO_COOKING -> tickDeliverToCooking(level);
            case PICKUP_FROM_COOKING -> tickPickupFromCooking(level);
        }
    }

    // ── IDLE ──────────────────────────────────────────────────────────────────

    private void tickIdle(ServerLevel level) {
        // Rate-limit idle checks to every 20 ticks
        if (++idleTick < 20) return;
        idleTick = 0;

        // ── REQUESTS (highest priority) ──────────────────────────────────────
        // Direct delivery: requested item already in a chest → fetch and hand to worker
        if (tryStartDirectDelivery(level)) return;
        // Smith: pick up finished tool, or fetch missing ingredients
        if (tryStartSmithWork(level)) return;
        // Chef: deliver wheat when the chef has signalled it needs ingredients
        if (tryStartChefIngredients(level)) return;

        // ── IDLE TASKS (only when no requests need servicing) ────────────────
        if (courier.isCarryingAnything()) return;
        if (tryStartPickupFromCooking(level)) return;
        tryStartGathering(level);
    }

    /** Handles all smith-related courier work. Returns true if a task was started. */
    private boolean tryStartSmithWork(ServerLevel level) {
        VillagerWorkerEntity smith = getSmith(level);
        if (smith == null) return false;

        int state = smith.getSmithCraftingState();

        if (state == VillagerWorkerEntity.SMITH_READY) {
            startPickupFromSmith(smith);
            return true;
        }

        if (state == VillagerWorkerEntity.SMITH_AWAITING) {
            SmithRecipe recipe = smith.getSmithCurrentRecipe();
            if (recipe == null) return false;

            List<SmithRecipe.Ingredient> missing = recipe.missing(smith.getSmithInputContainer());
            missing = removeSatisfiedByCarried(missing, courier.getCarriedInventory());

            if (missing.isEmpty()) {
                // All ingredients accounted for — deposit what we're carrying if any
                if (courier.isCarryingAnything()) { startDepositToSmith(smith); return true; }
                return false; // smith is already satisfied, fall through to idle
            }

            SmithRecipe.Ingredient need = missing.get(0);
            BlockPos chestPos = findChestWithItem(level, need);
            if (chestPos != null) {
                targetChestPos   = chestPos;
                targetIngredient = need;
                targetAmount     = need.count;
                navigateTo(chestPos);
                navTimeout = NAV_TIMEOUT;
                phase      = Phase.FETCH;
                return true;
            }

            // Ingredient not findable — deposit partial items if any, then idle
            if (courier.isCarryingAnything()) { startDepositToSmith(smith); return true; }
        }

        return false;
    }

    // ── FETCH ─────────────────────────────────────────────────────────────────

    private void tickFetch(ServerLevel level) {
        if (targetChestPos == null || targetIngredient == null) { resetToIdle(); return; }

        if (courier.getNavigation().isDone() && distSqTo(targetChestPos) >= REACH_SQ) {
            navigateTo(targetChestPos);
        }

        if (distSqTo(targetChestPos) < REACH_SQ) {
            // At the chest — extract items
            courier.setUsingChest(true);
            BlockEntity be = level.getBlockEntity(targetChestPos);
            if (be instanceof Container chest) {
                // Extract the primary ingredient
                extractFromContainer(chest, targetIngredient, targetAmount,
                        courier.getCarriedInventory());

                // While here, also grab any other missing smith ingredients from this chest
                if (!directDelivery && targetCookingPos == null) {
                    VillagerWorkerEntity smithNow = getSmith(level);
                    if (smithNow != null && smithNow.getSmithCurrentRecipe() != null) {
                        List<SmithRecipe.Ingredient> alsoMissing =
                                smithNow.getSmithCurrentRecipe().missing(smithNow.getSmithInputContainer());
                        alsoMissing = removeSatisfiedByCarried(alsoMissing, courier.getCarriedInventory());
                        for (SmithRecipe.Ingredient ing : alsoMissing) {
                            // Only grab it if this chest actually has it
                            boolean chestHasIt = false;
                            for (int i = 0; i < chest.getContainerSize(); i++) {
                                if (ing.matches(chest.getItem(i))) { chestHasIt = true; break; }
                            }
                            if (chestHasIt) {
                                extractFromContainer(chest, ing, ing.count, courier.getCarriedInventory());
                            }
                        }
                    }
                }
            }
            targetChestPos   = null;
            targetIngredient = null;
            targetAmount     = 0;

            // Chef delivery path: deposit wheat into the cooking block
            if (targetCookingPos != null) {
                navigateTo(targetCookingPos);
                navTimeout = NAV_TIMEOUT;
                phase = Phase.DELIVER_TO_COOKING;
                return;
            }

            // Direct delivery path: go straight to the requesting worker
            if (directDelivery) {
                Entity target = directDeliveryWorkerUUID != null
                        ? level.getEntity(directDeliveryWorkerUUID) : null;
                if (target instanceof VillagerWorkerEntity worker) {
                    deliveryWorkerUUID = directDeliveryWorkerUUID;
                    navigateTo(worker.blockPosition());
                    navTimeout = NAV_TIMEOUT;
                    phase = Phase.DELIVER_TO_WORKER;
                } else {
                    resetToIdle();
                }
                return;
            }

            // Check if more materials needed; if so keep fetching, else deposit
            VillagerWorkerEntity smith = getSmith(level);
            if (smith == null) { resetToIdle(); return; }

            SmithRecipe recipe = smith.getSmithCurrentRecipe();
            if (recipe == null) { resetToIdle(); return; }

            List<SmithRecipe.Ingredient> stillMissing = recipe.missing(smith.getSmithInputContainer());
            stillMissing = removeSatisfiedByCarried(stillMissing, courier.getCarriedInventory());

            if (!stillMissing.isEmpty()) {
                // Still need items from another chest — re-evaluate
                phase    = Phase.IDLE;
                idleTick = 20;
            } else {
                startDepositToSmith(smith);
            }
        }
    }

    // ── DEPOSIT TO SMITH ──────────────────────────────────────────────────────

    private void tickDeposit(ServerLevel level) {
        VillagerWorkerEntity smith = getSmith(level);
        if (smith == null) { resetToIdle(); return; }

        // Re-navigate when path finishes so the courier tracks the smith even if it moved
        if (courier.getNavigation().isDone()) {
            courier.getNavigation().moveTo(smith, WALK_SPEED);
        }

        if (distSqTo(smith.blockPosition()) < REACH_SQ) {
            transferAll(courier.getCarriedInventory(), smith.getSmithInputContainer());
            courier.clearCarried();
            resetToIdle();
        }
    }

    // ── PICKUP FROM SMITH ─────────────────────────────────────────────────────

    private void tickPickup(ServerLevel level) {
        VillagerWorkerEntity smith = getSmith(level);
        if (smith == null || smith.getSmithCraftingState() != VillagerWorkerEntity.SMITH_READY) {
            resetToIdle(); return;
        }

        if (courier.getNavigation().isDone()) {
            courier.getNavigation().moveTo(smith, WALK_SPEED);
        }

        if (distSqTo(smith.blockPosition()) < REACH_SQ) {
            ItemStack output = smith.getSmithOutputContainer().getItem(0);
            if (!output.isEmpty()) {
                courier.getCarriedInventory().setItem(0, output.copy());
                smith.getSmithOutputContainer().setItem(0, ItemStack.EMPTY);

                if (smith.getSmithCurrentRequest() != null) {
                    deliveryWorkerUUID = smith.getSmithCurrentRequest().getWorkerUUID();
                    Entity target = level.getEntity(deliveryWorkerUUID);
                    if (target instanceof VillagerWorkerEntity worker) {
                        navigateTo(worker.blockPosition());
                        navTimeout = NAV_TIMEOUT;
                        phase      = Phase.DELIVER_TO_WORKER;
                        return;
                    }
                }
            }
            resetToIdle();
        }
    }

    // ── DELIVER TO WORKER ─────────────────────────────────────────────────────

    private void tickDeliver(ServerLevel level) {
        if (deliveryWorkerUUID == null) { resetToIdle(); return; }

        Entity target = level.getEntity(deliveryWorkerUUID);
        if (!(target instanceof VillagerWorkerEntity worker) || !worker.isAlive()) {
            resetToIdle(); return;
        }

        // Keep navigating towards the worker (it may have moved)
        if (distSqTo(worker.blockPosition()) > REACH_SQ) {
            if (courier.getNavigation().isDone()) {
                courier.getNavigation().moveTo(worker, WALK_SPEED);
            }
        } else {
            // Deliver: place the item in the worker's tool slot
            ItemStack toDeliver = courier.getCarriedInventory().getItem(0);
            if (!toDeliver.isEmpty()) {
                worker.getToolContainer().setItem(0, toDeliver.copy());
                courier.getCarriedInventory().setItem(0, ItemStack.EMPTY);

                // Resolve the request from the heart
                VillageHeartBlockEntity heart = getHeart(level);
                if (heart != null) heart.resolveRequest(deliveryWorkerUUID);
            }
            deliveryWorkerUUID = null;
            resetToIdle();
        }
    }

    // ── GATHER FROM BLOCK ─────────────────────────────────────────────────────

    private void tickGatherFromBlock(ServerLevel level) {
        if (targetWorkplacePos == null) { resetToIdle(); return; }

        if (distSqTo(targetWorkplacePos) < REACH_SQ) {
            BlockEntity be = level.getBlockEntity(targetWorkplacePos);
            if (be instanceof MineBlockEntity mine) {
                transferAllFromContainer(mine.getOutputContainer(), courier.getCarriedInventory());
            } else if (be instanceof FarmBlockEntity farm) {
                extractWheatFromContainer(farm.getOutputContainer(), courier.getCarriedInventory());
            } else if (be instanceof LumbermillBlockEntity mill) {
                transferAllFromContainer(mill.getOutputContainer(), courier.getCarriedInventory());
            }
            targetWorkplacePos = null;

            if (!courier.isCarryingAnything()) { resetToIdle(); return; }

            // Find a chest to deposit into
            BlockPos chestPos = findAnyChest(level);
            if (chestPos == null) { resetToIdle(); return; }

            targetChestPos = chestPos;
            navigateTo(chestPos);
            navTimeout = NAV_TIMEOUT;
            phase = Phase.DEPOSIT_GATHERED;
        }
    }

    // ── DEPOSIT GATHERED ──────────────────────────────────────────────────────

    private void tickDepositGathered(ServerLevel level) {
        if (targetChestPos == null) { resetToIdle(); return; }

        if (distSqTo(targetChestPos) < REACH_SQ) {
            courier.setUsingChest(true);
            BlockEntity be = level.getBlockEntity(targetChestPos);
            if (be instanceof Container chest) {
                depositAllIntoContainer(courier.getCarriedInventory(), chest);
            }
            courier.clearCarried();
            targetChestPos = null;
            resetToIdle();
        }
    }

    // ── DELIVER TO COOKING ────────────────────────────────────────────────────

    private void tickDeliverToCooking(ServerLevel level) {
        if (targetCookingPos == null) { resetToIdle(); return; }

        if (courier.getNavigation().isDone()) {
            navigateTo(targetCookingPos);
        }

        if (distSqTo(targetCookingPos) < REACH_SQ) {
            BlockEntity be = level.getBlockEntity(targetCookingPos);
            if (be instanceof CookingBlockEntity cooking) {
                transferAll(courier.getCarriedInventory(), cooking.getInputContainer());
            }
            courier.clearCarried();
            resetToIdle();
        }
    }

    // ── PICKUP FROM COOKING ───────────────────────────────────────────────────

    private void tickPickupFromCooking(ServerLevel level) {
        if (targetCookingPos == null) { resetToIdle(); return; }

        if (courier.getNavigation().isDone()) {
            navigateTo(targetCookingPos);
        }

        if (distSqTo(targetCookingPos) < REACH_SQ) {
            BlockEntity be = level.getBlockEntity(targetCookingPos);
            if (be instanceof CookingBlockEntity cooking) {
                transferAllFromContainer(cooking.getOutputContainer(), courier.getCarriedInventory());
            }
            targetCookingPos = null;

            if (!courier.isCarryingAnything()) { resetToIdle(); return; }

            BlockPos chestPos = findAnyChest(level);
            if (chestPos == null) { courier.clearCarried(); resetToIdle(); return; }

            targetChestPos = chestPos;
            navigateTo(chestPos);
            navTimeout = NAV_TIMEOUT;
            phase = Phase.DEPOSIT_GATHERED;
        }
    }

    // ── Chef idle checks ──────────────────────────────────────────────────────

    private boolean tryStartPickupFromCooking(ServerLevel level) {
        if (courier.isCarryingAnything()) return false;
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return false;

        for (BlockPos workPos : heart.getLinkedWorkplaces()) {
            BlockEntity be = level.getBlockEntity(workPos);
            if (!(be instanceof CookingBlockEntity cooking)) continue;
            if (!isContainerEmpty(cooking.getOutputContainer())) {
                targetCookingPos = workPos;
                navigateTo(workPos);
                navTimeout = NAV_TIMEOUT;
                phase = Phase.PICKUP_FROM_COOKING;
                return true;
            }
        }
        return false;
    }

    /** Chef has signalled it needs wheat — treat this as a request and fetch it. */
    private boolean tryStartChefIngredients(ServerLevel level) {
        if (courier.isCarryingAnything()) return false;
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return false;

        for (BlockPos workPos : heart.getLinkedWorkplaces()) {
            BlockEntity be = level.getBlockEntity(workPos);
            if (!(be instanceof CookingBlockEntity cooking)) continue;
            if (!cooking.isNeedsIngredients()) continue;

            SmithRecipe.Ingredient wheatIng = SmithRecipe.exact(Items.WHEAT, 1);
            BlockPos chestPos = findChestWithItem(level, wheatIng);
            if (chestPos == null) continue;

            targetCookingPos = workPos;
            targetChestPos   = chestPos;
            targetIngredient = wheatIng;
            targetAmount     = 64;
            navigateTo(chestPos);
            navTimeout = NAV_TIMEOUT;
            phase = Phase.FETCH;
            return true;
        }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void startDepositToSmith(VillagerWorkerEntity smith) {
        courier.getNavigation().moveTo(smith, WALK_SPEED);
        navTimeout = NAV_TIMEOUT;
        phase      = Phase.DEPOSIT_TO_SMITH;
    }

    private void startPickupFromSmith(VillagerWorkerEntity smith) {
        courier.getNavigation().moveTo(smith, WALK_SPEED);
        navTimeout = NAV_TIMEOUT;
        phase      = Phase.PICKUP_FROM_SMITH;
    }

    /**
     * Checks pending requests for any item already present in a registered chest.
     * If found, sets up a direct chest→worker delivery and starts FETCH.
     */
    private boolean tryStartDirectDelivery(ServerLevel level) {
        // Never start a direct delivery while already carrying items for the smith
        if (courier.isCarryingAnything()) return false;

        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return false;

        for (ItemRequest req : heart.getPendingRequests()) {
            ItemStack wanted = req.getRequestedItem();
            BlockPos chestPos = findChestWithExactItem(level, wanted);
            if (chestPos == null) continue;

            directDelivery = true;
            directDeliveryWorkerUUID = req.getWorkerUUID();
            targetChestPos   = chestPos;
            targetIngredient = SmithRecipe.exact(wanted.getItem(), Math.max(1, wanted.getCount()));
            targetAmount     = Math.max(1, wanted.getCount());
            navigateTo(chestPos);
            navTimeout = NAV_TIMEOUT;
            phase      = Phase.FETCH;
            return true;
        }
        return false;
    }

    /** Returns the first registered chest that contains at least one of {@code item}, or null. */
    @Nullable
    private BlockPos findChestWithExactItem(ServerLevel level, ItemStack item) {
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return null;
        for (BlockPos chestPos : heart.getRegisteredChests()) {
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof Container container)) continue;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack slot = container.getItem(i);
                if (!slot.isEmpty() && ItemStack.isSameItem(slot, item)) return chestPos;
            }
        }
        return null;
    }

    private void resetToIdle() {
        courier.getNavigation().stop();
        courier.setUsingChest(false);
        phase      = Phase.IDLE;
        navTimeout = 0;
        idleTick   = 0;
        directDelivery = false;
        directDeliveryWorkerUUID = null;
        targetWorkplacePos = null;
        targetCookingPos   = null;
    }

    private void navigateTo(BlockPos pos) {
        courier.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, WALK_SPEED);
    }

    private double distSqTo(BlockPos pos) {
        return courier.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    // ── Chest search ──────────────────────────────────────────────────────────

    /**
     * Returns the first registered chest that contains at least one item
     * matching {@code ingredient}, or {@code null} if none found.
     */
    @Nullable
    private BlockPos findChestWithItem(ServerLevel level, SmithRecipe.Ingredient ingredient) {
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return null;

        for (BlockPos chestPos : heart.getRegisteredChests()) {
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof Container container)) continue;
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (ingredient.matches(container.getItem(i))) return chestPos;
            }
        }
        return null;
    }

    // ── Item transfer utilities ───────────────────────────────────────────────

    /**
     * Extracts up to {@code needed} units of {@code ingredient} from
     * {@code source} into {@code dest}.
     */
    private static void extractFromContainer(Container source,
                                             SmithRecipe.Ingredient ingredient,
                                             int needed,
                                             SimpleContainer dest) {
        int remaining = needed;
        for (int i = 0; i < source.getContainerSize() && remaining > 0; i++) {
            ItemStack slot = source.getItem(i);
            if (slot.isEmpty() || !ingredient.matches(slot)) continue;
            int take = Math.min(slot.getCount(), remaining);
            depositIntoContainer(dest, slot.copyWithCount(take));
            slot.shrink(take);
            source.setItem(i, slot.isEmpty() ? ItemStack.EMPTY : slot);
            remaining -= take;
        }
    }

    /** Moves all carried items into the smith's input container. */
    private static void transferAll(SimpleContainer from, SimpleContainer to) {
        for (int i = 0; i < from.getContainerSize(); i++) {
            ItemStack stack = from.getItem(i);
            if (stack.isEmpty()) continue;
            depositIntoContainer(to, stack.copy());
            from.setItem(i, ItemStack.EMPTY);
        }
    }

    private static void depositIntoContainer(SimpleContainer container, ItemStack stack) {
        if (stack.isEmpty()) return;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, stack.copy());
                return;
            } else if (ItemStack.isSameItemSameComponents(slot, stack)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int move  = Math.min(space, stack.getCount());
                slot.grow(move);
                stack.shrink(move);
                if (stack.isEmpty()) return;
            }
        }
    }

    /**
     * Removes ingredients from {@code missing} whose total count is already
     * covered by the items in {@code carried}, returning a new reduced list.
     */
    private static List<SmithRecipe.Ingredient> removeSatisfiedByCarried(
            List<SmithRecipe.Ingredient> missing, SimpleContainer carried) {
        return missing.stream()
                .map(ing -> {
                    int have = SmithRecipe.countInContainer(carried, ing);
                    int need = ing.count - have;
                    return need > 0 ? ing.withCount(need) : null;
                })
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());
    }

    // ── Idle gathering ────────────────────────────────────────────────────────

    /** Finds a mine, farm, or lumbermill with output items and starts navigating to gather them. */
    private boolean tryStartGathering(ServerLevel level) {
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return false;

        for (BlockPos workPos : heart.getLinkedWorkplaces()) {
            BlockEntity be = level.getBlockEntity(workPos);
            if (be instanceof MineBlockEntity mine && !isContainerEmpty(mine.getOutputContainer())) {
                targetWorkplacePos = workPos;
                navigateTo(workPos);
                navTimeout = NAV_TIMEOUT;
                phase = Phase.GATHER_FROM_BLOCK;
                return true;
            } else if (be instanceof FarmBlockEntity farm && containerHasWheat(farm.getOutputContainer())) {
                targetWorkplacePos = workPos;
                navigateTo(workPos);
                navTimeout = NAV_TIMEOUT;
                phase = Phase.GATHER_FROM_BLOCK;
                return true;
            } else if (be instanceof LumbermillBlockEntity mill && !isContainerEmpty(mill.getOutputContainer())) {
                targetWorkplacePos = workPos;
                navigateTo(workPos);
                navTimeout = NAV_TIMEOUT;
                phase = Phase.GATHER_FROM_BLOCK;
                return true;
            }
        }
        return false;
    }

    @Nullable
    private BlockPos findAnyChest(ServerLevel level) {
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return null;
        for (BlockPos pos : heart.getRegisteredChests()) {
            if (level.getBlockEntity(pos) instanceof Container) return pos;
        }
        return null;
    }

    private static boolean isContainerEmpty(SimpleContainer inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private static boolean containerHasWheat(SimpleContainer inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(Items.WHEAT)) return true;
        }
        return false;
    }

    /** Moves all items from a SimpleContainer source into a SimpleContainer dest. */
    private static void transferAllFromContainer(SimpleContainer from, SimpleContainer to) {
        for (int i = 0; i < from.getContainerSize(); i++) {
            ItemStack stack = from.getItem(i);
            if (stack.isEmpty()) continue;
            depositIntoContainer(to, stack.copy());
            from.setItem(i, ItemStack.EMPTY);
        }
    }

    /** Moves only wheat from a SimpleContainer into the courier's carried inventory. */
    private static void extractWheatFromContainer(SimpleContainer from, SimpleContainer to) {
        for (int i = 0; i < from.getContainerSize(); i++) {
            ItemStack stack = from.getItem(i);
            if (stack.isEmpty() || !stack.is(Items.WHEAT)) continue;
            depositIntoContainer(to, stack.copy());
            from.setItem(i, ItemStack.EMPTY);
        }
    }

    /** Deposits all carried items into a generic Container (e.g. chest). */
    private static void depositAllIntoContainer(SimpleContainer from, Container to) {
        for (int i = 0; i < from.getContainerSize(); i++) {
            ItemStack stack = from.getItem(i);
            if (stack.isEmpty()) continue;
            // Try to merge with existing stacks first, then find empty slots
            for (int j = 0; j < to.getContainerSize() && !stack.isEmpty(); j++) {
                ItemStack slot = to.getItem(j);
                if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
                    int space = slot.getMaxStackSize() - slot.getCount();
                    int move  = Math.min(space, stack.getCount());
                    slot.grow(move);
                    to.setItem(j, slot);
                    stack.shrink(move);
                }
            }
            for (int j = 0; j < to.getContainerSize() && !stack.isEmpty(); j++) {
                if (to.getItem(j).isEmpty()) {
                    to.setItem(j, stack.copy());
                    stack.setCount(0);
                }
            }
            from.setItem(i, ItemStack.EMPTY);
        }
    }

    // ── Entity lookups ────────────────────────────────────────────────────────

    @Nullable
    private VillagerWorkerEntity getSmith(ServerLevel level) {
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return null;
        return heart.getBlacksmithWorker(level);
    }

    @Nullable
    private VillageHeartBlockEntity getHeart(ServerLevel level) {
        BlockPos pos = courier.getLinkedHeartPos();
        if (pos == null) return null;
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof VillageHeartBlockEntity h ? h : null;
    }
}
