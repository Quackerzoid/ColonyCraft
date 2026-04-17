package village.automation.mod.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.CourierEntity;

import javax.annotation.Nullable;

/**
 * Read-only container menu for the courier GUI.
 *
 * <p>Contains 9 view-only slots that mirror the courier's carried inventory so
 * the container protocol syncs their contents to the client automatically.
 * The player cannot move items in or out; the slots are strictly display-only.
 */
public class CourierMenu extends AbstractContainerMenu {

    @Nullable
    private final CourierEntity courier;

    // ── Server-side constructor ───────────────────────────────────────────────

    public CourierMenu(int containerId, Inventory playerInventory, CourierEntity courier) {
        super(VillageMod.COURIER_MENU.get(), containerId);
        this.courier = courier;
        setupSlots(courier.getCarriedInventory());
    }

    // ── Client-side constructor (called via IMenuTypeExtension / FriendlyByteBuf) ──

    public CourierMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        super(VillageMod.COURIER_MENU.get(), containerId);
        int entityId = buf.readInt();
        Entity e = playerInventory.player.level().getEntity(entityId);
        this.courier = e instanceof CourierEntity c ? c : null;
        setupSlots(courier != null ? courier.getCarriedInventory() : new SimpleContainer(9));
    }

    // ── Slot setup ────────────────────────────────────────────────────────────

    /**
     * Adds 9 display-only slots in a single row (y = 61, x = 7 + i * 18).
     * {@code mayPickup} and {@code mayPlace} both return {@code false} so the
     * player cannot interact with the courier's carried items through this GUI.
     */
    private void setupSlots(SimpleContainer inv) {
        for (int i = 0; i < 9; i++) {
            final int index = i;
            this.addSlot(new Slot(inv, index, 7 + index * 18, 52) {
                @Override public boolean mayPickup(Player player) { return false; }
                @Override public boolean mayPlace(ItemStack stack)  { return false; }
            });
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @Nullable
    public CourierEntity getCourier() { return courier; }

    /** Current task description — read from the synced entity data. */
    public String getCurrentTask() {
        return courier != null ? courier.getCurrentTask() : "Unknown";
    }

    // ── Contract ──────────────────────────────────────────────────────────────

    @Override
    public boolean stillValid(Player player) {
        return courier == null || (courier.isAlive() && player.distanceToSqr(courier) < 64.0);
    }

    /** No shift-click behaviour — this is a display-only screen. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
