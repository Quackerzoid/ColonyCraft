package village.automation.mod.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import village.automation.mod.menu.EnchantingBlockMenu;

public class EnchantingBlockScreen extends WorkplaceBlockScreen<EnchantingBlockMenu> {

    public EnchantingBlockScreen(EnchantingBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
