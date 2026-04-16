package village.automation.mod.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.CookingBlockEntity;

public class CookingBlockMenu extends WorkplaceBlockMenu {

    // Server-side
    public CookingBlockMenu(int containerId, Inventory inventory, CookingBlockEntity be) {
        super(VillageMod.COOKING_BLOCK_MENU.get(), containerId, inventory, be);
    }

    // Client-side
    public CookingBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.COOKING_BLOCK_MENU.get(), containerId, inventory, buf);
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        return super.stillValid(player) && player.level()
                .getBlockState(blockEntity.getBlockPos()).is(VillageMod.COOKING_BLOCK.get());
    }
}
