package village.automation.mod.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.SmithingBlockEntity;

public class SmithingBlockMenu extends WorkplaceBlockMenu {

    // Server-side
    public SmithingBlockMenu(int containerId, Inventory inventory, SmithingBlockEntity be) {
        super(VillageMod.SMITHING_BLOCK_MENU.get(), containerId, inventory, be);
    }

    // Client-side
    public SmithingBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.SMITHING_BLOCK_MENU.get(), containerId, inventory, buf);
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        return super.stillValid(player) && player.level()
                .getBlockState(blockEntity.getBlockPos()).is(VillageMod.SMITHING_BLOCK.get());
    }
}
