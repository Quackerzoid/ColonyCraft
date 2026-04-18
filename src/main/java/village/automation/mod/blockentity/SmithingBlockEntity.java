package village.automation.mod.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.SmithingBlockMenu;

public class SmithingBlockEntity extends WorkplaceBlockEntityBase {

    // ── GUI sync ──────────────────────────────────────────────────────────────
    // [0] = smithCraftingState  (0=IDLE, 1=AWAITING, 2=CRAFTING, 3=READY)
    // [1] = smithCraftingTimer  (counts down to 0 during CRAFTING)
    // [2] = result item raw registry ID  (-1 = no active recipe)
    // [3] = maxCraftingTimer (value when crafting started, for progress bar)
    private final int[] syncData = new int[]{0, 0, -1, 1200};
    public final ContainerData data = new ContainerData() {
        @Override public int get(int i)         { return (i >= 0 && i < 4) ? syncData[i] : 0; }
        @Override public void set(int i, int v) { if (i >= 0 && i < 4) syncData[i] = v; }
        @Override public int getCount()         { return 4; }
    };

    /** Called by {@link village.automation.mod.entity.goal.SmithCraftGoal} every tick. */
    public void setSmithState(int state, int timer, int resultItemId, int maxTimer) {
        syncData[0] = state;
        syncData[1] = timer;
        syncData[2] = resultItemId;
        syncData[3] = maxTimer;
    }

    public SmithingBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.SMITHING_BLOCK_BE.get(), pos, state);
    }

    @Override public JobType getRequiredJob() { return JobType.BLACKSMITH; }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.smithing_block");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new SmithingBlockMenu(containerId, inventory, this);
    }
}
