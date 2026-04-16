package village.automation.mod.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import village.automation.mod.menu.AnimalPenBlockMenu;

public class AnimalPenBlockScreen extends WorkplaceBlockScreen<AnimalPenBlockMenu> {

    public AnimalPenBlockScreen(AnimalPenBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
