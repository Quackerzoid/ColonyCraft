package village.automation.mod.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import village.automation.mod.menu.BrewingBlockMenu;

public class BrewingBlockScreen extends WorkplaceBlockScreen<BrewingBlockMenu> {

    public BrewingBlockScreen(BrewingBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
