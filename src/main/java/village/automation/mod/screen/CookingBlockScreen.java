package village.automation.mod.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import village.automation.mod.menu.CookingBlockMenu;

public class CookingBlockScreen extends WorkplaceBlockScreen<CookingBlockMenu> {

    public CookingBlockScreen(CookingBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
