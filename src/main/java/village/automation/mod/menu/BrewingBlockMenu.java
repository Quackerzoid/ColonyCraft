package village.automation.mod.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.BrewingBlockEntity;

public class BrewingBlockMenu extends WorkplaceBlockMenu {

    // Server-side
    public BrewingBlockMenu(int containerId, Inventory inventory, BrewingBlockEntity be) {
        super(VillageMod.BREWING_BLOCK_MENU.get(), containerId, inventory, be);
    }

    // Client-side
    public BrewingBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.BREWING_BLOCK_MENU.get(), containerId, inventory, buf);
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        return super.stillValid(player) && player.level()
                .getBlockState(blockEntity.getBlockPos()).is(VillageMod.BREWING_BLOCK.get());
    }
}
