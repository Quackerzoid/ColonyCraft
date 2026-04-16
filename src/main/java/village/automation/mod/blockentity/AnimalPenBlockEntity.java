package village.automation.mod.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.AnimalPenBlockMenu;

public class AnimalPenBlockEntity extends WorkplaceBlockEntityBase {

    public AnimalPenBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.ANIMAL_PEN_BE.get(), pos, state);
    }

    @Override public JobType getRequiredJob() { return JobType.SHEPHERD; }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.animal_pen");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new AnimalPenBlockMenu(containerId, inventory, this);
    }
}
