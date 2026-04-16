package village.automation.mod.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.FishingBlockEntity;

public class FishingBlockMenu extends WorkplaceBlockMenu {

    // Server-side
    public FishingBlockMenu(int containerId, Inventory inventory, FishingBlockEntity be) {
        super(VillageMod.FISHING_BLOCK_MENU.get(), containerId, inventory, be);
    }

    // Client-side
    public FishingBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.FISHING_BLOCK_MENU.get(), containerId, inventory, buf);
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        return super.stillValid(player) && player.level()
                .getBlockState(blockEntity.getBlockPos()).is(VillageMod.FISHING_BLOCK.get());
    }
}
