package village.automation.mod.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.AnimalPenBlockEntity;

public class AnimalPenBlockMenu extends WorkplaceBlockMenu {

    // Server-side
    public AnimalPenBlockMenu(int containerId, Inventory inventory, AnimalPenBlockEntity be) {
        super(VillageMod.ANIMAL_PEN_MENU.get(), containerId, inventory, be);
    }

    // Client-side
    public AnimalPenBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.ANIMAL_PEN_MENU.get(), containerId, inventory, buf);
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        return super.stillValid(player) && player.level()
                .getBlockState(blockEntity.getBlockPos()).is(VillageMod.ANIMAL_PEN.get());
    }
}
