package village.automation.mod.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import village.automation.mod.menu.LumbermillBlockMenu;

/**
 * Screen for the Lumbermill GUI.
 *
 * <p>Inherits the full worker-info panel, divider, and player-inventory layout
 * from {@link WorkplaceBlockScreen}.  Custom elements (output grid, progress bar,
 * etc.) will be added here when the Lumberjack work loop is implemented.
 */
public class LumbermillBlockScreen extends WorkplaceBlockScreen<LumbermillBlockMenu> {

    public LumbermillBlockScreen(LumbermillBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
