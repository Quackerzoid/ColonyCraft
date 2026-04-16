package village.automation.mod.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Sends a hungry worker to a registered chest to collect up to {@value #MAX_FOOD_TAKE}
 * food items. Active only when the worker's inventory has no food and their food
 * level is below the eat threshold. The existing {@code tryEatFood()} tick on
 * {@link VillagerWorkerEntity} then consumes from the inventory automatically.
 */
public class FetchFoodGoal extends Goal {

    private static final double WALK_SPEED    = 0.7;
    private static final double REACH_SQ      = 9.0;
    private static final int    MAX_FOOD_TAKE = 16;
    private static final int    NAV_TIMEOUT   = 400;

    private final VillagerWorkerEntity worker;
    @Nullable private BlockPos targetChestPos = null;
    private int navTimeout = 0;

    public FetchFoodGoal(VillagerWorkerEntity worker) {
        this.worker = worker;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!worker.needsFood()) return false;
        if (inventoryHasFood()) return false;
        if (!(worker.level() instanceof ServerLevel level)) return false;
        targetChestPos = findChestWithFood(level);
        return targetChestPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return targetChestPos != null && navTimeout > 0;
    }

    @Override
    public void start() {
        navTimeout = NAV_TIMEOUT;
        worker.getNavigation().moveTo(
                targetChestPos.getX() + 0.5, targetChestPos.getY(), targetChestPos.getZ() + 0.5,
                WALK_SPEED);
    }

    @Override
    public void tick() {
        if (--navTimeout <= 0 || targetChestPos == null) { stop(); return; }

        double distSq = worker.distanceToSqr(
                targetChestPos.getX() + 0.5, targetChestPos.getY() + 0.5, targetChestPos.getZ() + 0.5);
        if (distSq < REACH_SQ) {
            BlockEntity be = ((ServerLevel) worker.level()).getBlockEntity(targetChestPos);
            if (be instanceof Container chest) {
                takeFoodFromChest(chest, worker.getWorkerInventory());
            }
            targetChestPos = null;
        }
    }

    @Override
    public void stop() {
        worker.getNavigation().stop();
        targetChestPos = null;
        navTimeout = 0;
    }

    private boolean inventoryHasFood() {
        SimpleContainer inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).get(DataComponents.FOOD) != null) return true;
        }
        return false;
    }

    @Nullable
    private BlockPos findChestWithFood(ServerLevel level) {
        BlockPos workPos = worker.getWorkplacePos();
        if (workPos == null) return null;
        BlockEntity wbe = level.getBlockEntity(workPos);
        if (!(wbe instanceof IWorkplaceBlockEntity workplace)) return null;
        BlockPos heartPos = workplace.getLinkedHeartPos();
        if (heartPos == null) return null;
        BlockEntity hbe = level.getBlockEntity(heartPos);
        if (!(hbe instanceof VillageHeartBlockEntity heart)) return null;

        for (BlockPos chestPos : heart.getRegisteredChests()) {
            BlockEntity be = level.getBlockEntity(chestPos);
            if (!(be instanceof Container container)) continue;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack slot = container.getItem(i);
                if (!slot.isEmpty() && slot.get(DataComponents.FOOD) != null) return chestPos;
            }
        }
        return null;
    }

    private static void takeFoodFromChest(Container chest, SimpleContainer dest) {
        int taken = 0;
        for (int i = 0; i < chest.getContainerSize() && taken < MAX_FOOD_TAKE; i++) {
            ItemStack slot = chest.getItem(i);
            if (slot.isEmpty() || slot.get(DataComponents.FOOD) == null) continue;
            int take = Math.min(slot.getCount(), MAX_FOOD_TAKE - taken);
            depositIntoContainer(dest, slot.copyWithCount(take));
            slot.shrink(take);
            chest.setItem(i, slot.isEmpty() ? ItemStack.EMPTY : slot);
            taken += take;
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
}
