package village.automation.mod.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.EnchantingBlockEntity;

public class EnchantingBlockMenu extends WorkplaceBlockMenu {

    // Server-side
    public EnchantingBlockMenu(int containerId, Inventory inventory, EnchantingBlockEntity be) {
        super(VillageMod.ENCHANTING_BLOCK_MENU.get(), containerId, inventory, be);
    }

    // Client-side
    public EnchantingBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.ENCHANTING_BLOCK_MENU.get(), containerId, inventory, buf);
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        return super.stillValid(player) && player.level()
                .getBlockState(blockEntity.getBlockPos()).is(VillageMod.ENCHANTING_BLOCK.get());
    }
}
