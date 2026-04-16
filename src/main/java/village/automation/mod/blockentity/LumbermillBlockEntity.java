package village.automation.mod.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.LumbermillBlockMenu;

/**
 * Block entity for the Lumbermill workplace.
 *
 * <p>Currently a template — stores the linked-heart position and assigned-worker
 * UUID (via {@link WorkplaceBlockEntityBase}) and opens the standard workplace
 * GUI.  Custom inventory, AI goals, and ticker logic will be added here when
 * the Lumberjack job is fully implemented.
 */
public class LumbermillBlockEntity extends WorkplaceBlockEntityBase {

    public LumbermillBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.LUMBERMILL_BE.get(), pos, state);
    }

    @Override
    public JobType getRequiredJob() { return JobType.LUMBERJACK; }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.lumbermill");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new LumbermillBlockMenu(containerId, inventory, this);
    }
}
