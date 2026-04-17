package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import village.automation.mod.ItemRequest;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import village.automation.mod.blockentity.CookingBlockEntity;
import village.automation.mod.blockentity.FarmBlockEntity;
import village.automation.mod.blockentity.AnimalPenBlockEntity;
import village.automation.mod.blockentity.FishingBlockEntity;
import village.automation.mod.entity.AnimalType;
import village.automation.mod.blockentity.LumbermillBlockEntity;
import village.automation.mod.blockentity.MineBlockEntity;
import village.automation.mod.blockentity.SmelterBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.CourierDispatcher;
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
 *  IDLE ──► FETCH            (navigate to a chest and extract a needed material)
 *       ──► DEPOSIT_TO_SMITH  (navigate to smith and hand off carried items)
 *       ──► PICKUP_FROM_SMITH (navigate to smith and collect the finished tool)
 *       ──► DELIVER_TO_WORKER (navigate to requesting worker and equip tool)
 *       ──► GATHER_FROM_BLOCK (navigate to mine/farm and collect output)
 *       ──► DEPOSIT_GATHERED  (navigate to a chest and deposit collected items)
 *       ──► DELIVER_TO_COOKING (navigate to cooking block and deposit wheat)
 *       ──► PICKUP_FROM_COOKING (navigate to cooking block and collect bread)
 * </pre>
 *
 * Timeouts guard every navigation leg; on expiry the courier resets to IDLE.
 *
 * <h3>Multi-courier coordination</h3>
 * Before committing to any task the courier checks the village heart's
 * {@link CourierDispatcher}.  The dispatcher holds exclusive per-resource locks:
 * <ul>
 *   <li>Smith lock — at most one courier handles the smith at a time.</li>
 *   <li>Chest lock — at most one courier fetches from a given chest at a time.</li>
 *   <li>Workplace lock — at most one courier gathers from a given mine/farm.</li>
 *   <li>Cooking lock — at most one courier handles a given cooking block.</li>
 *   <li>Request lock — at most one courier services a given worker's item request.</li>
 * </ul>
 * Locks are released explicitly on task completion and automatically expire after
 * 600 ticks (30 s) to recover from stuck or dead couriers.
 */
public class CourierGoal extends Goal {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final double REACH_SQ    = 9.0;   // 3-block reach, squared
    private static final double WALK_SPEED  = 0.8;
    private static final int    NAV_TIMEOUT = 400;   // 20 s before giving up

    // ── State ─────────────────────────────────────────────────────────────────
    private enum Phase {
        IDLE, FETCH, DEPOSIT_TO_SMITH, PICKUP_FROM_SMITH, DELIVER_TO_WORKER,
        GATHER_FROM_BLOCK, DEPOSIT_GATHERED,
        DELIVER_TO_COOKING, PICKUP_FROM_COOKING,
        DELIVER_TO_SMELTER, PICKUP_FROM_SMELTER,
        DELIVER_TO_ANIMAL_PEN,
        DELIVER_TO_GOLEM,
        /** Leisurely stroll within the village bounds when truly idle. */
        WANDER,
        /** Carrying a poppy to a Soul Iron Golem to grant it Strength II. */
        DELIVER_POPPY
    }

    private final CourierEntity courier;
    private Phase phase      = Phase.IDLE;
    private int   navTimeout = 0;
    private int   idleTick   = 0;

    /** The chest the courier is currently heading towards. */
    @Nullable private BlockPos               targetChestPos           = null;
    /** The ingredient the courier is trying to collect from {@link #targetChestPos}. */
    @Nullable private SmithRecipe.Ingredient targetIngredient         = null;
    /** Amount of {@link #targetIngredient} still needed from that chest. */
    private           int                    targetAmount             = 0;
    /** UUID of the worker to deliver the finished tool to. */
    @Nullable private java.util.UUID         deliveryWorkerUUID       = null;

    /** True when fulfilling a direct chest→worker delivery (no smith involved). */
    private           boolean                directDelivery           = false;
    @Nullable private java.util.UUID         directDeliveryWorkerUUID = null;

    /** Workplace block the courier is currently gathering from (mine/farm). */
    @Nullable private BlockPos targetWorkplacePos = null;
    /** Cooking block the courier is currently interacting with. */
    @Nullable private BlockPos targetCookingPos   = null;
    /** Smelter block the courier is currently delivering to or collecting from. */
    @Nullable private BlockPos targetSmelterPos   = null;
    /** True when the smelter delivery is fuel; false when it is ore. */
    private           boolean  smelterDeliverFuel = false;
    /** Animal pen the courier is delivering breeding food to. */
    @Nullable private BlockPos targetAnimalPenPos = null;
    /** UUID of a SoulIronGolemEntity the courier is delivering iron or a poppy to. */
    @Nullable private java.util.UUID targetGolemUUID = null;

    /** Total ticks spent in IDLE phase since last task — drives the wander trigger. */
    private int totalIdleTicks = 0;
    /** Rate-limit counter within the WANDER phase (mirrors {@code idleTick}). */
    private int wanderCheckTick = 0;
    /** Destination for the current wander leg. */
    @Nullable private BlockPos wanderTarget = null;

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

    @Override
    public void start() {
        phase = Phase.IDLE;
        navTimeout = 0;
        courier.setCurrentTask("Idle");
    }

    @Override
    public void stop() {
        releaseDispatcherLocks();
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
            case IDLE                -> tickIdle(level);
            case FETCH               -> tickFetch(level);
            case DEPOSIT_TO_SMITH    -> tickDeposit(level);
            case PICKUP_FROM_SMITH   -> tickPickup(level);
            case DELIVER_TO_WORKER   -> tickDeliver(level);
            case GATHER_FROM_BLOCK   -> tickGatherFromBlock(level);
            case DEPOSIT_GATHERED    -> tickDepositGathered(level);
            case DELIVER_TO_COOKING  -> tickDeliverToCooking(level);
            case PICKUP_FROM_COOKING -> tickPickupFromCooking(level);
            case DELIVER_TO_SMELTER  -> tickDeliverToSmelter(level);
            case PICKUP_FROM_SMELTER -> tickPickupFromSmelter(level);
            case DELIVER_TO_ANIMAL_PEN -> tickDeliverToAnimalPen(level);
            case DELIVER_TO_GOLEM      -> tickDeliverToGolem(level);
            case WANDER                -> tickWander(level);
            case DELIVER_POPPY         -> tickDeliverPoppy(level);
        }
    }

    // ── IDLE ──────────────────────────────────────────────────────────────────

    private void tickIdle(ServerLevel level) {
        // Always count ticks so the wander timer runs even between checks.
        totalIdleTicks++;

        // Rate-limit idle checks to every 20 ticks
        if (++idleTick < 20) return;
        idleTick = 0;

        // ── REQUESTS (highest priority) ──────────────────────────────────────
        if (tryStartDirectDelivery(level)) return;
        if (tryStartSmithWork(level)) return;
        if (tryStartChefIngredients(level)) return;
        if (tryStartSmelterDelivery(level)) return;
        if (tryStartGolemRepair(level)) return;

        // ── IDLE TASKS (only when not already carrying something) ─────────────
        if (courier.isCarryingAnything()) {
            // Safety valve: if we're carrying items but have no active task (e.g. a
            // direct delivery failed because the target worker was unreachable or gone),
            // deposit the items into the nearest chest rather than being stuck forever.
            BlockPos chestPos = findAnyChest(level);
            if (chestPos != null) {
                targetChestPos = chestPos;
                courier.setCurrentTask("Depositing to chest");
                navigateTo(chestPos);
                navTimeout = NAV_TIMEOUT;
                phase = Phase.DEPOSIT_GATHERED;
            }
            return;
        }
        if (tryStartPickupFromCooking(level)) return;
        if (tryStartPickupFromSmelter(level)) return;
        if (tryStartAnimalPenFood(level)) return;
        if (tryStartGathering(level)) return;

        // Nothing to do — after 15 seconds of true idleness, take a stroll.
        if (totalIdleTicks >= 300) tryStartWander(level);
    }

    // ── Smith work ────────────────────────────────────────────────────────────

    /**
     * Handles all smith-related courier work.  Checks the dispatcher first so
     * only one courier services the smith at a time.
     *
     * @return {@code true} if a task was started
     */
    private boolean tryStartSmithWork(ServerLevel level) {
        CourierDispatcher dispatcher = getDispatcher(level);

        // Guard: another courier already owns the smith
        if (dispatcher != null && !dispatcher.isSmithFree(courier.getUUID())) return false;

        VillagerWorkerEntity smith = getSmith(level);
        if (smith == null) return false;

        int state = smith.getSmithCraftingState();

        // Smith has finished a tool — go pick it up
        if (state == VillagerWorkerEntity.SMITH_READY) {
            if (dispatcher != null) dispatcher.claimSmith(courier.getUUID());
            startPickupFromSmith(smith);
            return true;
        }

        // Smith is waiting for ingredients
        if (state == VillagerWorkerEntity.SMITH_AWAITING) {
            SmithRecipe recipe = smith.getSmithCurrentRecipe();
            if (recipe == null) return false;

            List<SmithRecipe.Ingredient> missing = recipe.missing(smith.getSmithInputContainer());
            missing = removeSatisfiedByCarried(missing, courier.getCarriedInventory());

            if (missing.isEmpty()) {
                // All ingredients accounted for — deposit what we have if any
                if (courier.isCarryingAnything()) {
                    if (dispatcher != null) dispatcher.claimSmith(courier.getUUID());
                    startDepositToSmith(smith);
                    return true;
                }
                return false; // smith is satisfied, fall through to idle tasks
            }

            // Find an unclaimed chest containing the first missing ingredient
            SmithRecipe.Ingredient need = missing.get(0);
            BlockPos chestPos = findChestWithItem(level, need, dispatcher);
            if (chestPos != null) {
                if (dispatcher != null) {
                    dispatcher.claimSmith(courier.getUUID());
                    dispatcher.claimChest(chestPos, courier.getUUID());
                }
                targetChestPos   = chestPos;
                targetIngredient = need;
                targetAmount     = need.count;
                courier.setCurrentTask("Fetching materials");
                navigateTo(chestPos);
                navTimeout = NAV_TIMEOUT;
                phase      = Phase.FETCH;
                return true;
            }

            // Ingredient not findable — deposit partial items if any, then wait
            if (courier.isCarryingAnything()) {
                if (dispatcher != null) dispatcher.claimSmith(courier.getUUID());
                startDepositToSmith(smith);
                return true;
            }
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
            // ── At the chest — extract items ──────────────────────────────────
            courier.setUsingChest(true);
            BlockEntity be = level.getBlockEntity(targetChestPos);
            if (be instanceof Container chest) {
                extractFromContainer(chest, targetIngredient, targetAmount,
                        courier.getCarriedInventory());

                // While here, opportunistically grab any other missing smith
                // ingredients that happen to be in this same chest.
                if (!directDelivery && targetCookingPos == null) {
                    VillagerWorkerEntity smithNow = getSmith(level);
                    if (smithNow != null && smithNow.getSmithCurrentRecipe() != null) {
                        List<SmithRecipe.Ingredient> alsoMissing =
                                smithNow.getSmithCurrentRecipe().missing(smithNow.getSmithInputContainer());
                        alsoMissing = removeSatisfiedByCarried(alsoMissing, courier.getCarriedInventory());
                        for (SmithRecipe.Ingredient ing : alsoMissing) {
                            boolean chestHasIt = false;
                            for (int i = 0; i < chest.getContainerSize(); i++) {
                                if (ing.matches(chest.getItem(i))) { chestHasIt = true; break; }
                            }
                            if (chestHasIt) {
                                extractFromContainer(chest, ing, ing.count,
                                        courier.getCarriedInventory());
                            }
                        }
                    }
                }
            }

            // Release the chest lock now — we are done at this chest and
            // another courier can use it immediately for a different task.
            CourierDispatcher dispatcher = getDispatcher(level);
            if (dispatcher != null && targetChestPos != null) {
                dispatcher.releaseChest(targetChestPos);
            }

            targetChestPos   = null;
            targetIngredient = null;
            targetAmount     = 0;

            // ── Decide next phase ─────────────────────────────────────────────

            // Chef delivery path: deposit wheat into the cooking block
            if (targetCookingPos != null) {
                courier.setCurrentTask("Delivering to chef");
                navigateTo(targetCookingPos);
                navTimeout = NAV_TIMEOUT;
                phase = Phase.DELIVER_TO_COOKING;
                return;
            }

            // Smelter delivery path: deposit ore or fuel into the smelter block
            if (targetSmelterPos != null) {
                courier.setCurrentTask("Delivering to smelter");
                navigateTo(targetSmelterPos);
                navTimeout = NAV_TIMEOUT;
                phase = Phase.DELIVER_TO_SMELTER;
                return;
            }

            // Animal pen food delivery path
            if (targetAnimalPenPos != null) {
                courier.setCurrentTask("Delivering to animal pen");
                navigateTo(targetAnimalPenPos);
                navTimeout = NAV_TIMEOUT;
                phase = Phase.DELIVER_TO_ANIMAL_PEN;
                return;
            }

            // Golem repair delivery path: carry iron to the damaged golem
            if (targetGolemUUID != null) {
                village.automation.mod.entity.SoulIronGolemEntity golem =
                        (village.automation.mod.entity.SoulIronGolemEntity)
                        level.getEntity(targetGolemUUID);
                if (golem != null && golem.isAlive() && golem.isRepairing()) {
                    courier.setCurrentTask("Delivering iron to golem");
                    navigateTo(golem.blockPosition());
                    navTimeout = NAV_TIMEOUT;
                    phase = Phase.DELIVER_TO_GOLEM;
                } else {
                    targetGolemUUID = null;
                    resetToIdle();
                }
                return;
            }

            // Direct delivery path: go straight to the requesting worker
            if (directDelivery) {
                Entity target = directDeliveryWorkerUUID != null
                        ? level.getEntity(directDeliveryWorkerUUID) : null;
                if (target instanceof VillagerWorkerEntity worker) {
                    deliveryWorkerUUID = directDeliveryWorkerUUID;
                    courier.setCurrentTask("Delivering to worker");
                    navigateTo(worker.blockPosition());
                    navTimeout = NAV_TIMEOUT;
                    phase = Phase.DELIVER_TO_WORKER;
                } else {
                    resetToIdle();
                }
                return;
            }

            // Smith path: check if more materials are still needed
            VillagerWorkerEntity smith = getSmith(level);
            if (smith == null) { resetToIdle(); return; }

            SmithRecipe recipe = smith.getSmithCurrentRecipe();
            if (recipe == null) { resetToIdle(); return; }

            List<SmithRecipe.Ingredient> stillMissing = recipe.missing(smith.getSmithInputContainer());
            stillMissing = removeSatisfiedByCarried(stillMissing, courier.getCarriedInventory());

            if (!stillMissing.isEmpty()) {
                // Still need items — re-evaluate from idle to find the next chest
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
                        courier.setCurrentTask("Delivering tool");
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

        if (distSqTo(worker.blockPosition()) > REACH_SQ) {
            if (courier.getNavigation().isDone()) {
                courier.getNavigation().moveTo(worker, WALK_SPEED);
            }
        } else {
            ItemStack toDeliver = courier.getCarriedInventory().getItem(0);
            if (!toDeliver.isEmpty()) {
                worker.getToolContainer().setItem(0, toDeliver.copy());
                courier.getCarriedInventory().setItem(0, ItemStack.EMPTY);

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
            } else if (be instanceof FishingBlockEntity fish) {
                transferAllFromContainer(fish.getOutputContainer(), courier.getCarriedInventory());
            } else if (be instanceof AnimalPenBlockEntity pen) {
                transferAllFromContainer(pen.getOutputContainer(), courier.getCarriedInventory());
            }

            // Release the workplace lock — the block's output is now in our inventory
            CourierDispatcher dispatcher = getDispatcher(level);
            if (dispatcher != null) dispatcher.releaseWorkplace(targetWorkplacePos);
            targetWorkplacePos = null;

            if (!courier.isCarryingAnything()) { resetToIdle(); return; }

            BlockPos chestPos = findAnyChest(level);
            if (chestPos == null) { resetToIdle(); return; }

            targetChestPos = chestPos;
            courier.setCurrentTask("Depositing to chest");
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
            courier.setCurrentTask("Depositing to chest");
            navigateTo(chestPos);
            navTimeout = NAV_TIMEOUT;
            phase = Phase.DEPOSIT_GATHERED;
        }
    }

    // ── Chef idle checks ──────────────────────────────────────────────────────

    /**
     * If any cooking block has finished output ready to collect, claims it and
     * starts a PICKUP_FROM_COOKING leg.  Skips cooking blocks already claimed
     * by another courier.
     */
    private boolean tryStartPickupFromCooking(ServerLevel level) {
        if (courier.isCarryingAnything()) return false;
        CourierDispatcher dispatcher = getDispatcher(level);
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return false;

        for (BlockPos workPos : heart.getLinkedWorkplaces()) {
            BlockEntity be = level.getBlockEntity(workPos);
            if (!(be instanceof CookingBlockEntity cooking)) continue;
            if (isContainerEmpty(cooking.getOutputContainer())) continue;
            if (dispatcher != null && !dispatcher.isCookingFree(workPos, courier.getUUID())) continue;

            if (dispatcher != null) dispatcher.claimCooking(workPos, courier.getUUID());
            targetCookingPos = workPos;
            courier.setCurrentTask("Collecting from chef");
            navigateTo(workPos);
            navTimeout = NAV_TIMEOUT;
            phase = Phase.PICKUP_FROM_COOKING;
            return true;
        }
        return false;
    }

    /**
     * If a cooking block has signalled it needs ingredients, fetches a cookable
     * item (wheat, raw cod, or raw salmon — in that priority order) from an
     * unclaimed chest and starts a FETCH→DELIVER_TO_COOKING chain.  Skips
     * cooking blocks and chests already claimed by another courier.
     */
    private boolean tryStartChefIngredients(ServerLevel level) {
        if (courier.isCarryingAnything()) return false;
        CourierDispatcher dispatcher = getDispatcher(level);
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return false;

        for (BlockPos workPos : heart.getLinkedWorkplaces()) {
            BlockEntity be = level.getBlockEntity(workPos);
            if (!(be instanceof CookingBlockEntity cooking)) continue;
            if (!cooking.isNeedsIngredients()) continue;
            if (dispatcher != null && !dispatcher.isCookingFree(workPos, courier.getUUID())) continue;

            // Try cookable ingredients in priority order: wheat → cod → salmon
            SmithRecipe.Ingredient ingredient = null;
            BlockPos chestPos = null;
            for (net.minecraft.world.item.Item candidate :
                    List.of(Items.WHEAT, Items.COD, Items.SALMON)) {
                SmithRecipe.Ingredient ing = SmithRecipe.exact(candidate, 1);
                BlockPos found = findChestWithItem(level, ing, dispatcher);
                if (found != null) {
                    ingredient = ing;
                    chestPos   = found;
                    break;
                }
            }
            if (chestPos == null) continue;

            if (dispatcher != null) {
                dispatcher.claimCooking(workPos, courier.getUUID());
                dispatcher.claimChest(chestPos, courier.getUUID());
            }
            targetCookingPos = workPos;
            targetChestPos   = chestPos;
            targetIngredient = ingredient;
            targetAmount     = 64;
            courier.setCurrentTask("Fetching for chef");
            navigateTo(chestPos);
            navTimeout = NAV_TIMEOUT;
            phase = Phase.FETCH;
            return true;
        }
        return false;
    }

    // ── Task starters ─────────────────────────────────────────────────────────

    private void startDepositToSmith(VillagerWorkerEntity smith) {
        courier.getNavigation().moveTo(smith, WALK_SPEED);
        courier.setCurrentTask("Delivering to smith");
        navTimeout = NAV_TIMEOUT;
        phase      = Phase.DEPOSIT_TO_SMITH;
    }

    private void startPickupFromSmith(VillagerWorkerEntity smith) {
        courier.getNavigation().moveTo(smith, WALK_SPEED);
        courier.setCurrentTask("Collecting from smith");
        navTimeout = NAV_TIMEOUT;
        phase      = Phase.PICKUP_FROM_SMITH;
    }

    /**
     * Scans pending item requests for one that is (a) not already claimed by
     * another courier and (b) whose item exists in an unclaimed chest.
     * Starts a FETCH leg with both the request and chest locked.
     */
    private boolean tryStartDirectDelivery(ServerLevel level) {
        if (courier.isCarryingAnything()) return false;
        CourierDispatcher dispatcher = getDispatcher(level);
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return false;

        for (ItemRequest req : heart.getPendingRequests()) {
            // Skip if another courier already handles this request
            if (dispatcher != null
                    && !dispatcher.isRequestFree(req.getWorkerUUID(), courier.getUUID())) continue;

            // Skip (and cancel) requests from workers that no longer exist in the world —
            // prevents the courier from fetching an item it can never deliver
            Entity reqEntity = level.getEntity(req.getWorkerUUID());
            if (!(reqEntity instanceof VillagerWorkerEntity) || !reqEntity.isAlive()) {
                heart.resolveRequest(req.getWorkerUUID());
                continue;
            }

            ItemStack wanted  = req.getRequestedItem();
            BlockPos chestPos = findChestWithExactItem(level, wanted, dispatcher);
            if (chestPos == null) continue;

            if (dispatcher != null) {
                dispatcher.claimRequest(req.getWorkerUUID(), courier.getUUID());
                dispatcher.claimChest(chestPos, courier.getUUID());
            }
            directDelivery           = true;
            directDeliveryWorkerUUID = req.getWorkerUUID();
            targetChestPos           = chestPos;
            targetIngredient         = SmithRecipe.exact(wanted.getItem(), Math.max(1, wanted.getCount()));
            targetAmount             = Math.max(1, wanted.getCount());
            courier.setCurrentTask("Fetching for worker");
            navigateTo(chestPos);
            navTimeout = NAV_TIMEOUT;
            phase      = Phase.FETCH;
            return true;
        }
        return false;
    }

    /** Finds an unclaimed mine, farm, or lumbermill with output ready and starts GATHER_FROM_BLOCK. */
    private boolean tryStartGathering(ServerLevel level) {
        CourierDispatcher dispatcher = getDispatcher(level);
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return false;

        for (BlockPos workPos : heart.getLinkedWorkplaces()) {
            // Skip workplaces another courier is already heading to
            if (dispatcher != null && !dispatcher.isWorkplaceFree(workPos, courier.getUUID())) continue;

            BlockEntity be = level.getBlockEntity(workPos);
            boolean hasMineOutput = be instanceof MineBlockEntity mine
                    && !isContainerEmpty(mine.getOutputContainer());
            boolean hasFarmWheat  = be instanceof FarmBlockEntity farm
                    && containerHasWheat(farm.getOutputContainer());
            boolean hasMillOutput = be instanceof LumbermillBlockEntity mill
                    && !isContainerEmpty(mill.getOutputContainer());
            boolean hasFishOutput = be instanceof FishingBlockEntity fish
                    && !isContainerEmpty(fish.getOutputContainer());
            boolean hasPenOutput  = be instanceof AnimalPenBlockEntity pen
                    && !isContainerEmpty(pen.getOutputContainer());

            if (hasMineOutput || hasFarmWheat || hasMillOutput || hasFishOutput || hasPenOutput) {
                if (dispatcher != null) dispatcher.claimWorkplace(workPos, courier.getUUID());
                targetWorkplacePos = workPos;
                courier.setCurrentTask("Gathering resources");
                navigateTo(workPos);
                navTimeout = NAV_TIMEOUT;
                phase = Phase.GATHER_FROM_BLOCK;
                return true;
            }
        }
        return false;
    }

    // ── DELIVER TO SMELTER ────────────────────────────────────────────────────

    private void tickDeliverToSmelter(ServerLevel level) {
        if (targetSmelterPos == null) { resetToIdle(); return; }

        if (courier.getNavigation().isDone()) {
            navigateTo(targetSmelterPos);
        }

        if (distSqTo(targetSmelterPos) < REACH_SQ) {
            BlockEntity be = level.getBlockEntity(targetSmelterPos);
            if (be instanceof SmelterBlockEntity smelterBE) {
                if (smelterDeliverFuel) {
                    transferAll(courier.getCarriedInventory(), smelterBE.getFuelContainer());
                    smelterBE.setNeedsFuel(false);
                } else {
                    transferAll(courier.getCarriedInventory(), smelterBE.getOreContainer());
                    smelterBE.setNeedsOre(false);
                }
            }
            courier.clearCarried();

            CourierDispatcher dispatcher = getDispatcher(level);
            if (dispatcher != null) dispatcher.releaseSmelter(targetSmelterPos);
            targetSmelterPos = null;
            resetToIdle();
        }
    }

    // ── PICKUP FROM SMELTER ───────────────────────────────────────────────────

    private void tickPickupFromSmelter(ServerLevel level) {
        if (targetSmelterPos == null) { resetToIdle(); return; }

        if (courier.getNavigation().isDone()) {
            navigateTo(targetSmelterPos);
        }

        if (distSqTo(targetSmelterPos) < REACH_SQ) {
            BlockEntity be = level.getBlockEntity(targetSmelterPos);
            if (be instanceof SmelterBlockEntity smelterBE) {
                transferAllFromContainer(smelterBE.getOutputContainer(), courier.getCarriedInventory());
                smelterBE.setOutputReady(false);
            }

            CourierDispatcher dispatcher = getDispatcher(level);
            if (dispatcher != null) dispatcher.releaseSmelter(targetSmelterPos);
            targetSmelterPos = null;

            if (!courier.isCarryingAnything()) { resetToIdle(); return; }

            BlockPos chestPos = findAnyChest(level);
            if (chestPos == null) { courier.clearCarried(); resetToIdle(); return; }

            targetChestPos = chestPos;
            courier.setCurrentTask("Depositing smelter output");
            navigateTo(chestPos);
            navTimeout = NAV_TIMEOUT;
            phase = Phase.DEPOSIT_GATHERED;
        }
    }

    // ── Smelter task starters ─────────────────────────────────────────────────

    /**
     * Scans linked workplaces for a {@link SmelterBlockEntity} that needs ore or
     * fuel, finds an unclaimed chest containing a matching item, and starts a
     * FETCH → DELIVER_TO_SMELTER chain.  Ore is prioritised over fuel.
     *
     * @return {@code true} if a task was started
     */
    private boolean tryStartSmelterDelivery(ServerLevel level) {
        if (courier.isCarryingAnything()) return false;
        CourierDispatcher dispatcher = getDispatcher(level);
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return false;

        for (BlockPos workPos : heart.getLinkedWorkplaces()) {
            BlockEntity be = level.getBlockEntity(workPos);
            if (!(be instanceof SmelterBlockEntity smelterBE)) continue;
            if (dispatcher != null && !dispatcher.isSmelterFree(workPos, courier.getUUID())) continue;

            // ── Ore delivery — up to 8 items per trip ────────────────────────
            if (smelterBE.isNeedsOre() && isContainerEmpty(smelterBE.getOreContainer())) {
                ItemStack foundOre = findAnyBlastableInChests(level, dispatcher);
                if (foundOre != null) {
                    BlockPos chestPos = findChestWithExactItem(level, foundOre, dispatcher);
                    if (chestPos != null) {
                        if (dispatcher != null) {
                            dispatcher.claimSmelter(workPos, courier.getUUID());
                            dispatcher.claimChest(chestPos, courier.getUUID());
                        }
                        targetSmelterPos   = workPos;
                        smelterDeliverFuel = false;
                        targetChestPos     = chestPos;
                        targetIngredient   = SmithRecipe.exact(foundOre.getItem(), 8);
                        targetAmount       = 8;
                        courier.setCurrentTask("Fetching ore for smelter");
                        navigateTo(chestPos);
                        navTimeout = NAV_TIMEOUT;
                        phase      = Phase.FETCH;
                        return true;
                    }
                }
            }

            // ── Fuel delivery ─────────────────────────────────────────────────
            if (smelterBE.isNeedsFuel() && isContainerEmpty(smelterBE.getFuelContainer())) {
                ItemStack foundFuel = findAnyFuelInChests(level, dispatcher);
                if (foundFuel != null) {
                    BlockPos chestPos = findChestWithExactItem(level, foundFuel, dispatcher);
                    if (chestPos != null) {
                        if (dispatcher != null) {
                            dispatcher.claimSmelter(workPos, courier.getUUID());
                            dispatcher.claimChest(chestPos, courier.getUUID());
                        }
                        targetSmelterPos   = workPos;
                        smelterDeliverFuel = true;
                        targetChestPos     = chestPos;
                        targetIngredient   = SmithRecipe.exact(foundFuel.getItem(), 1);
                        targetAmount       = 1;
                        courier.setCurrentTask("Fetching fuel for smelter");
                        navigateTo(chestPos);
                        navTimeout = NAV_TIMEOUT;
                        phase      = Phase.FETCH;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Scans linked workplaces for a {@link SmelterBlockEntity} whose output slot
     * is ready for collection, claims it, and starts a PICKUP_FROM_SMELTER leg.
     *
     * @return {@code true} if a task was started
     */
    private boolean tryStartPickupFromSmelter(ServerLevel level) {
        if (courier.isCarryingAnything()) return false;
        CourierDispatcher dispatcher = getDispatcher(level);
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return false;

        for (BlockPos workPos : heart.getLinkedWorkplaces()) {
            BlockEntity be = level.getBlockEntity(workPos);
            if (!(be instanceof SmelterBlockEntity smelterBE)) continue;
            if (!smelterBE.isOutputReady()) continue;
            if (isContainerEmpty(smelterBE.getOutputContainer())) continue;
            if (dispatcher != null && !dispatcher.isSmelterFree(workPos, courier.getUUID())) continue;

            if (dispatcher != null) dispatcher.claimSmelter(workPos, courier.getUUID());
            targetSmelterPos = workPos;
            courier.setCurrentTask("Collecting from smelter");
            navigateTo(workPos);
            navTimeout = NAV_TIMEOUT;
            phase = Phase.PICKUP_FROM_SMELTER;
            return true;
        }
        return false;
    }

    // ── Smelter chest search helpers ──────────────────────────────────────────

    /**
     * Returns a copy of the first item found in any unclaimed chest that has a
     * {@link RecipeType#BLASTING} recipe, or {@code null} if none found.
     */
    @Nullable
    private ItemStack findAnyBlastableInChests(ServerLevel level,
                                               @Nullable CourierDispatcher dispatcher) {
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return null;
        java.util.UUID myId = courier.getUUID();

        for (BlockPos chestPos : heart.getRegisteredChests()) {
            if (dispatcher != null && !dispatcher.isChestFree(chestPos, myId)) continue;
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof Container container)) continue;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack slot = container.getItem(i);
                if (!slot.isEmpty() && level.getRecipeManager()
                        .getRecipeFor(RecipeType.BLASTING, new SingleRecipeInput(slot), level)
                        .isPresent()) {
                    return slot.copy();
                }
            }
        }
        return null;
    }

    /**
     * Returns a copy of the first valid furnace-fuel item found in any unclaimed
     * chest, or {@code null} if none found.
     */
    @Nullable
    private ItemStack findAnyFuelInChests(ServerLevel level,
                                          @Nullable CourierDispatcher dispatcher) {
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return null;
        java.util.UUID myId = courier.getUUID();

        for (BlockPos chestPos : heart.getRegisteredChests()) {
            if (dispatcher != null && !dispatcher.isChestFree(chestPos, myId)) continue;
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof Container container)) continue;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack slot = container.getItem(i);
                if (!slot.isEmpty() && AbstractFurnaceBlockEntity.isFuel(slot)) {
                    return slot.copy();
                }
            }
        }
        return null;
    }

    // ── DELIVER TO ANIMAL PEN ─────────────────────────────────────────────────

    private void tickDeliverToAnimalPen(ServerLevel level) {
        if (targetAnimalPenPos == null) { resetToIdle(); return; }

        if (courier.getNavigation().isDone()) {
            navigateTo(targetAnimalPenPos);
        }

        if (distSqTo(targetAnimalPenPos) < REACH_SQ) {
            BlockEntity be = level.getBlockEntity(targetAnimalPenPos);
            if (be instanceof AnimalPenBlockEntity pen) {
                transferAll(courier.getCarriedInventory(), pen.getBreedingFoodInput());
                pen.setNeedsBreedingFood(false);
            }
            courier.clearCarried();
            CourierDispatcher dispatcher = getDispatcher(level);
            if (dispatcher != null) dispatcher.releaseWorkplace(targetAnimalPenPos);
            targetAnimalPenPos = null;
            resetToIdle();
        }
    }

    // ── Animal pen food task starter ──────────────────────────────────────────

    /**
     * If any animal pen has signalled it needs breeding food, fetches the
     * appropriate food item from a chest and delivers it to the pen.
     *
     * @return {@code true} if a task was started
     */
    private boolean tryStartAnimalPenFood(ServerLevel level) {
        if (courier.isCarryingAnything()) return false;
        CourierDispatcher dispatcher = getDispatcher(level);
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return false;

        for (BlockPos workPos : heart.getLinkedWorkplaces()) {
            BlockEntity be = level.getBlockEntity(workPos);
            if (!(be instanceof AnimalPenBlockEntity pen)) continue;
            if (!pen.isNeedsBreedingFood()) continue;
            if (dispatcher != null && !dispatcher.isWorkplaceFree(workPos, courier.getUUID())) continue;

            net.minecraft.world.item.Item breedingItem = pen.getTargetAnimalType().getBreedingFood();
            SmithRecipe.Ingredient ingredient = SmithRecipe.exact(breedingItem, 1);
            BlockPos chestPos = findChestWithItem(level, ingredient, dispatcher);
            if (chestPos == null) continue;

            if (dispatcher != null) {
                dispatcher.claimWorkplace(workPos, courier.getUUID());
                dispatcher.claimChest(chestPos, courier.getUUID());
            }
            targetAnimalPenPos = workPos;
            targetChestPos     = chestPos;
            targetIngredient   = ingredient;
            targetAmount       = 64;
            courier.setCurrentTask("Fetching for animal pen");
            navigateTo(chestPos);
            navTimeout = NAV_TIMEOUT;
            phase = Phase.FETCH;
            return true;
        }
        return false;
    }

    // ── Golem repair delivery ─────────────────────────────────────────────────

    /**
     * Looks for a linked {@link village.automation.mod.entity.SoulIronGolemEntity}
     * that is currently in repair mode and below full HP, then finds an iron ingot
     * in a registered chest so the courier can deliver it.
     *
     * @return {@code true} if a task was started
     */
    private boolean tryStartGolemRepair(ServerLevel level) {
        if (courier.isCarryingAnything()) return false;
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return false;
        BlockPos heartPos = courier.getLinkedHeartPos();
        if (heartPos == null) return false;

        // Find the nearest repairing golem linked to the same heart
        village.automation.mod.entity.SoulIronGolemEntity target = null;
        double bestDist = Double.MAX_VALUE;
        for (village.automation.mod.entity.SoulIronGolemEntity golem :
                level.getEntitiesOfClass(
                        village.automation.mod.entity.SoulIronGolemEntity.class,
                        new AABB(heartPos).inflate(heart.getRadius() + 32))) {
            if (!golem.isAlive()) continue;
            if (!golem.isRepairing()) continue;
            if (!heartPos.equals(golem.getLinkedHeartPos())) continue;
            if (golem.getHealth() >= golem.getMaxHealth()) continue;
            double d = courier.distanceToSqr(golem);
            if (d < bestDist) { bestDist = d; target = golem; }
        }
        if (target == null) return false;

        // Find an iron ingot in any unclaimed chest
        CourierDispatcher dispatcher = getDispatcher(level);
        SmithRecipe.Ingredient ironIngot = SmithRecipe.exact(Items.IRON_INGOT, 1);
        BlockPos chestPos = findChestWithItem(level, ironIngot, dispatcher);
        if (chestPos == null) return false;

        if (dispatcher != null) dispatcher.claimChest(chestPos, courier.getUUID());
        targetChestPos   = chestPos;
        targetIngredient = ironIngot;
        targetAmount     = 1;
        targetGolemUUID  = target.getUUID();

        courier.setCurrentTask("Delivering iron to golem");
        navigateTo(chestPos);
        navTimeout = NAV_TIMEOUT;
        phase      = Phase.FETCH;
        return true;
    }

    /**
     * Navigates to the damaged golem and transfers carried iron ingots.
     * Each ingot heals 25 HP (matching vanilla iron golem repair).
     */
    private void tickDeliverToGolem(ServerLevel level) {
        if (targetGolemUUID == null) { resetToIdle(); return; }

        Entity e = level.getEntity(targetGolemUUID);
        if (!(e instanceof village.automation.mod.entity.SoulIronGolemEntity golem)
                || !golem.isAlive()) {
            targetGolemUUID = null;
            resetToIdle();
            return;
        }

        // Abort if the golem finished repairing before we arrived
        if (!golem.isRepairing() || golem.getHealth() >= golem.getMaxHealth()) {
            targetGolemUUID = null;
            resetToIdle();
            return;
        }

        if (distSqTo(golem.blockPosition()) > REACH_SQ) {
            if (courier.getNavigation().isDone()) {
                courier.getNavigation().moveTo(golem, WALK_SPEED);
            }
            return;
        }

        // At the golem — consume all carried iron ingots and heal
        SimpleContainer carried = courier.getCarriedInventory();
        for (int i = 0; i < carried.getContainerSize(); i++) {
            ItemStack stack = carried.getItem(i);
            if (!stack.isEmpty() && stack.is(Items.IRON_INGOT)) {
                golem.heal(stack.getCount() * 25.0f);
                carried.setItem(i, ItemStack.EMPTY);
            }
        }
        targetGolemUUID = null;
        resetToIdle();
    }

    // ── WANDER ────────────────────────────────────────────────────────────────

    /**
     * Picks a random walkable point within the village radius and starts
     * navigating toward it, entering the WANDER phase.
     *
     * @return {@code true} if a wander leg was successfully started
     */
    private boolean tryStartWander(ServerLevel level) {
        if (courier.isCarryingAnything()) { totalIdleTicks = 0; return false; }
        BlockPos heartPos = courier.getLinkedHeartPos();
        if (heartPos == null) { totalIdleTicks = 0; return false; }

        VillageHeartBlockEntity heart = getHeart(level);
        int radius = heart != null ? Math.min(heart.getRadius(), 48) : 32;

        var rng = courier.getRandom();
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double dist  = (0.3 + rng.nextDouble() * 0.7) * radius; // 30–100 % of radius
            int    dx    = (int) (Math.sin(angle) * dist);
            int    dz    = (int) (Math.cos(angle) * dist);

            // Find the surface at that column
            BlockPos surface = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    heartPos.offset(dx, 0, dz));

            // moveTo returns true if a valid path was found
            if (courier.getNavigation().moveTo(
                    surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5,
                    WALK_SPEED)) {
                wanderTarget   = surface;
                navTimeout     = 300; // 15 s to reach the wander destination
                phase          = Phase.WANDER;
                totalIdleTicks = 0;
                courier.setCurrentTask("Wandering");
                return true;
            }
        }
        // Couldn't find a path — reset timer so we try again after another 15 s
        totalIdleTicks = 0;
        return false;
    }

    /**
     * Handles the WANDER phase.  Higher-priority tasks preempt wandering
     * immediately.  While walking, the courier occasionally looks for nearby
     * poppies to deliver to a Soul Iron Golem.
     */
    private void tickWander(ServerLevel level) {
        // Rate-limit expensive checks to every 20 ticks
        if (++wanderCheckTick < 20) {
            // Still check if we've arrived, so we don't overshoot
            if (wanderTarget != null && courier.getNavigation().isDone()) {
                resetToIdle();
            }
            return;
        }
        wanderCheckTick = 0;

        // Higher-priority tasks always win
        if (tryStartDirectDelivery(level)) return;
        if (tryStartSmithWork(level)) return;
        if (tryStartChefIngredients(level)) return;
        if (tryStartSmelterDelivery(level)) return;
        if (tryStartGolemRepair(level)) return;

        // Random chance to scan for poppies — ~33 % per second-ish check
        if (courier.getRandom().nextInt(3) == 0 && tryPickupPoppy(level)) return;

        // Done walking — return to idle for re-evaluation
        if (courier.getNavigation().isDone()) {
            resetToIdle();
        }
    }

    /**
     * Searches the area around the courier for a poppy flower block.
     * If one is found and a linked {@link village.automation.mod.entity.SoulIronGolemEntity}
     * exists, breaks the block and starts the DELIVER_POPPY phase.
     *
     * @return {@code true} if delivery was started
     */
    private boolean tryPickupPoppy(ServerLevel level) {
        BlockPos origin = courier.blockPosition();
        final int r = 8;
        BlockPos poppyPos = null;

        search:
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos check = origin.offset(dx, dy, dz);
                    if (level.getBlockState(check).is(Blocks.POPPY)) {
                        poppyPos = check;
                        break search;
                    }
                }
            }
        }
        if (poppyPos == null) return false;

        // Look for any linked golem to receive the poppy
        village.automation.mod.entity.SoulIronGolemEntity golem = findNearbyLinkedGolem(level);
        if (golem == null) return false;

        // Break the flower, place it in the courier's hand, and start delivery
        level.removeBlock(poppyPos, false);
        courier.getCarriedInventory().setItem(0, new ItemStack(Items.POPPY));
        targetGolemUUID = golem.getUUID();
        wanderTarget    = null;
        courier.setCurrentTask("Delivering poppy");
        courier.getNavigation().moveTo(golem, WALK_SPEED);
        navTimeout = NAV_TIMEOUT;
        phase      = Phase.DELIVER_POPPY;
        return true;
    }

    /**
     * Returns the nearest alive {@link village.automation.mod.entity.SoulIronGolemEntity}
     * that is linked to the same village heart, or {@code null} if none found.
     */
    @Nullable
    private village.automation.mod.entity.SoulIronGolemEntity findNearbyLinkedGolem(
            ServerLevel level) {
        BlockPos heartPos = courier.getLinkedHeartPos();
        if (heartPos == null) return null;
        VillageHeartBlockEntity heart = getHeart(level);
        double searchRadius = heart != null ? heart.getRadius() + 32.0 : 64.0;

        village.automation.mod.entity.SoulIronGolemEntity nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (village.automation.mod.entity.SoulIronGolemEntity g :
                level.getEntitiesOfClass(
                        village.automation.mod.entity.SoulIronGolemEntity.class,
                        new AABB(heartPos).inflate(searchRadius))) {
            if (!g.isAlive()) continue;
            if (!heartPos.equals(g.getLinkedHeartPos())) continue;
            double d = courier.distanceToSqr(g);
            if (d < bestDistSq) { bestDistSq = d; nearest = g; }
        }
        return nearest;
    }

    /**
     * Walks to the target golem and, on arrival, applies
     * <b>Strength II</b> for one minute (1 200 ticks).
     */
    private void tickDeliverPoppy(ServerLevel level) {
        if (targetGolemUUID == null) { courier.clearCarried(); resetToIdle(); return; }

        Entity e = level.getEntity(targetGolemUUID);
        if (!(e instanceof village.automation.mod.entity.SoulIronGolemEntity golem)
                || !golem.isAlive()) {
            targetGolemUUID = null;
            courier.clearCarried();
            resetToIdle();
            return;
        }

        if (distSqTo(golem.blockPosition()) > REACH_SQ) {
            if (courier.getNavigation().isDone()) {
                courier.getNavigation().moveTo(golem, WALK_SPEED);
            }
            return;
        }

        // At the golem — hand over the poppy and apply Strength II for 60 seconds
        courier.clearCarried();
        golem.addEffect(new MobEffectInstance(
                MobEffects.DAMAGE_BOOST,
                1200,  // 60 s = 1 200 ticks
                1,     // amplifier 1 = Strength II
                false,
                true));

        targetGolemUUID = null;
        resetToIdle();
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    private void resetToIdle() {
        releaseDispatcherLocks();
        courier.getNavigation().stop();
        courier.setUsingChest(false);
        courier.setCurrentTask("Idle");
        phase                    = Phase.IDLE;
        navTimeout               = 0;
        idleTick                 = 0;
        directDelivery           = false;
        directDeliveryWorkerUUID = null;
        targetWorkplacePos       = null;
        targetCookingPos         = null;
        targetSmelterPos         = null;
        smelterDeliverFuel       = false;
        targetAnimalPenPos       = null;
        targetGolemUUID          = null;
        totalIdleTicks           = 0;
        wanderCheckTick          = 0;
        wanderTarget             = null;
    }

    /**
     * Releases all dispatcher locks held by this courier.
     * Called from both {@link #resetToIdle()} and {@link #stop()}.
     */
    private void releaseDispatcherLocks() {
        if (courier.level() instanceof ServerLevel level) {
            CourierDispatcher d = getDispatcher(level);
            if (d != null) d.release(courier.getUUID());
        }
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    private void navigateTo(BlockPos pos) {
        courier.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, WALK_SPEED);
    }

    private double distSqTo(BlockPos pos) {
        return courier.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    // ── Chest search ──────────────────────────────────────────────────────────

    /**
     * Returns the first registered chest that (a) is not fetch-locked by
     * another courier and (b) contains at least one item matching
     * {@code ingredient}, or {@code null} if none found.
     */
    @Nullable
    private BlockPos findChestWithItem(ServerLevel level,
                                       SmithRecipe.Ingredient ingredient,
                                       @Nullable CourierDispatcher dispatcher) {
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return null;
        java.util.UUID myId = courier.getUUID();

        for (BlockPos chestPos : heart.getRegisteredChests()) {
            if (dispatcher != null && !dispatcher.isChestFree(chestPos, myId)) continue;
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof Container container)) continue;
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (ingredient.matches(container.getItem(i))) return chestPos;
            }
        }
        return null;
    }

    /**
     * Returns the first registered chest that (a) is not fetch-locked by
     * another courier and (b) contains at least one item matching {@code item}
     * by item type, or {@code null} if none found.
     */
    @Nullable
    private BlockPos findChestWithExactItem(ServerLevel level,
                                            ItemStack item,
                                            @Nullable CourierDispatcher dispatcher) {
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return null;
        java.util.UUID myId = courier.getUUID();

        for (BlockPos chestPos : heart.getRegisteredChests()) {
            if (dispatcher != null && !dispatcher.isChestFree(chestPos, myId)) continue;
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof Container container)) continue;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack slot = container.getItem(i);
                if (!slot.isEmpty() && ItemStack.isSameItem(slot, item)) return chestPos;
            }
        }
        return null;
    }

    @Nullable
    private BlockPos findAnyChest(ServerLevel level) {
        VillageHeartBlockEntity heart = getHeart(level);
        if (heart == null) return null;
        // Deposit chests are not locked — writing items is safe for concurrent couriers
        for (BlockPos pos : heart.getRegisteredChests()) {
            if (level.getBlockEntity(pos) instanceof Container) return pos;
        }
        return null;
    }

    // ── Item transfer utilities ───────────────────────────────────────────────

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

    /** Moves all items from a SimpleContainer source into a SimpleContainer dest. */
    private static void transferAllFromContainer(SimpleContainer from, SimpleContainer to) {
        for (int i = 0; i < from.getContainerSize(); i++) {
            ItemStack stack = from.getItem(i);
            if (stack.isEmpty()) continue;
            depositIntoContainer(to, stack.copy());
            from.setItem(i, ItemStack.EMPTY);
        }
    }

    /** Moves only wheat from a SimpleContainer source into a SimpleContainer dest. */
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
            // Merge with existing stacks first
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
            // Then fill empty slots
            for (int j = 0; j < to.getContainerSize() && !stack.isEmpty(); j++) {
                if (to.getItem(j).isEmpty()) {
                    to.setItem(j, stack.copy());
                    stack.setCount(0);
                }
            }
            from.setItem(i, ItemStack.EMPTY);
        }
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

    // ── Entity / heart lookups ─────────────────────────────────────────────────

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

    @Nullable
    private CourierDispatcher getDispatcher(ServerLevel level) {
        VillageHeartBlockEntity heart = getHeart(level);
        return heart != null ? heart.getCourierDispatcher() : null;
    }
}
