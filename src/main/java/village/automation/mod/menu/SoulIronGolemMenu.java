package village.automation.mod.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.SoulIronGolemEntity;

import javax.annotation.Nullable;

/**
 * Display-only menu for the Soul Iron Golem GUI.
 *
 * <p>Contains no slots — the only data shown is the golem's status string
 * and its current/maximum health, both of which are synced to the client
 * automatically via {@link net.minecraft.network.syncher.SynchedEntityData}
 * and Minecraft's attribute system respectively.
 */
public class SoulIronGolemMenu extends AbstractContainerMenu {

    @Nullable
    private final SoulIronGolemEntity golem;

    // ── Server-side constructor ───────────────────────────────────────────────

    public SoulIronGolemMenu(int containerId, Inventory playerInventory, SoulIronGolemEntity golem) {
        super(VillageMod.SOUL_IRON_GOLEM_MENU.get(), containerId);
        this.golem = golem;
    }

    // ── Client-side constructor (IMenuTypeExtension / FriendlyByteBuf) ────────

    public SoulIronGolemMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        super(VillageMod.SOUL_IRON_GOLEM_MENU.get(), containerId);
        int entityId = buf.readInt();
        Entity e = playerInventory.player.level().getEntity(entityId);
        this.golem = e instanceof SoulIronGolemEntity g ? g : null;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @Nullable
    public SoulIronGolemEntity getGolem() { return golem; }

    public String getStatus() {
        return golem != null ? golem.getStatus() : "Unknown";
    }

    public float getHealth() {
        return golem != null ? golem.getHealth() : 0f;
    }

    public float getMaxHealth() {
        return golem != null ? golem.getMaxHealth() : 100f;
    }

    // ── Contract ──────────────────────────────────────────────────────────────

    @Override
    public boolean stillValid(Player player) {
        return golem == null || (golem.isAlive() && player.distanceToSqr(golem) < 64.0);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
