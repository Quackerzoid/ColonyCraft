package village.automation.mod.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import village.automation.mod.menu.SmithingBlockMenu;

public class SmithingBlockScreen extends WorkplaceBlockScreen<SmithingBlockMenu> {

    public SmithingBlockScreen(SmithingBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
