package village.automation.mod.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.LumbermillBlockEntity;

/**
 * Menu for the Lumbermill GUI.
 *
 * <p>Inherits the standard slot layout from {@link WorkplaceBlockMenu}:
 * <pre>
 *   0 – 26  player main inventory
 *  27 – 35  player hotbar
 * </pre>
 *
 * <p>Output slots and ContainerData will be added here when the Lumberjack
 * work loop is implemented.
 */
public class LumbermillBlockMenu extends WorkplaceBlockMenu {

    // ── Server-side constructor ───────────────────────────────────────────────
    public LumbermillBlockMenu(int containerId, Inventory inventory, LumbermillBlockEntity be) {
        super(VillageMod.LUMBERMILL_MENU.get(), containerId, inventory, be);
    }

    // ── Client-side constructor ───────────────────────────────────────────────
    public LumbermillBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.LUMBERMILL_MENU.get(), containerId, inventory, buf);
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        return super.stillValid(player)
                && player.level().getBlockState(blockEntity.getBlockPos())
                          .is(VillageMod.LUMBERMILL.get());
    }
}
