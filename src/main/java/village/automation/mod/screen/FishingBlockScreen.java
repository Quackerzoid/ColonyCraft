package village.automation.mod.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import village.automation.mod.menu.FishingBlockMenu;

public class FishingBlockScreen extends WorkplaceBlockScreen<FishingBlockMenu> {

    public FishingBlockScreen(FishingBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
