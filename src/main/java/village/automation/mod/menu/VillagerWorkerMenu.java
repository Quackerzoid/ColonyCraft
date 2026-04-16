package village.automation.mod.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.SwordItem;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;

public class VillagerWorkerMenu extends AbstractContainerMenu {

    @Nullable
    private final VillagerWorkerEntity entity;
    private final SimpleContainer workerInventory;
    private final SimpleContainer toolContainer;

    // ── Server-side constructor ───────────────────────────────────────────────
    public VillagerWorkerMenu(int containerId, Inventory playerInventory, VillagerWorkerEntity entity) {
        super(VillageMod.VILLAGER_WORKER_MENU.get(), containerId);
        this.entity          = entity;
        this.workerInventory = entity.getWorkerInventory();
        this.toolContainer   = entity.getToolContainer();
        setup(playerInventory);
    }

    // ── Client-side constructor ───────────────────────────────────────────────
    public VillagerWorkerMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        super(VillageMod.VILLAGER_WORKER_MENU.get(), containerId);
        int entityId = buf.readInt();
        Entity e = playerInventory.player.level().getEntity(entityId);
        this.entity          = e instanceof VillagerWorkerEntity vwe ? vwe : null;
        this.workerInventory = this.entity != null ? this.entity.getWorkerInventory() : new SimpleContainer(9);
        this.toolContainer   = this.entity != null ? this.entity.getToolContainer()   : new SimpleContainer(1);
        setup(playerInventory);
    }

    private void setup(Inventory playerInventory) {
        // Slot 0 — Tool slot, left of the 3×3 grid, centred vertically (35, 36)
        this.addSlot(new Slot(toolContainer, 0, 35, 36) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return isTool(stack);
            }
        });

        // Slots 1–9 — Worker 3×3 grid at (62, 17)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                this.addSlot(new Slot(workerInventory, col + row * 3, 62 + col * 18, 17 + row * 18));
            }
        }

        // Slots 10–36 — Player main inventory at (8, 102)
        // Shifted down 18 px from the original 84 to fill the taller GUI.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 102 + row * 18));
            }
        }

        // Slots 37–45 — Hotbar at (8, 160)
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 160));
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Returns the worker's current job, read from synced entity data so it is
     * safe to call on the client inside the screen renderer.
     */
    public JobType getJob() {
        return entity != null ? entity.getSyncedJob() : JobType.UNEMPLOYED;
    }

    /**
     * Exposes the worker entity so the screen can render its model in the
     * entity preview panel.  Returns {@code null} on the client when the
     * entity has not yet been resolved from the level (should not occur in
     * normal play, but handled defensively).
     */
    @Nullable
    public VillagerWorkerEntity getEntity() {
        return entity;
    }

    /**
     * Returns the worker's food level (0–{@value VillagerWorkerEntity#MAX_FOOD}).
     * Reads from {@code SynchedEntityData} — safe to call on the client.
     */
    public int getFoodLevel() {
        return entity != null ? entity.getFoodLevel() : VillagerWorkerEntity.MAX_FOOD;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.entity == null || (this.entity.isAlive() && player.distanceToSqr(this.entity) < 64.0);
    }

    // ── Tool check ───────────────────────────────────────────────────────────
    private static boolean isTool(ItemStack stack) {
        return stack.getItem() instanceof DiggerItem   // pickaxe, axe, shovel, hoe — all tiers
            || stack.getItem() instanceof ShearsItem
            || stack.getItem() instanceof SwordItem;
    }

    // ── Shift-click ──────────────────────────────────────────────────────────
    // 0       = tool slot
    // 1–9     = worker 3×3
    // 10–36   = player inventory
    // 37–45   = hotbar
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack remainder = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            remainder = stack.copy();

            if (index == 0) {
                // Tool slot → player inventory then hotbar
                if (!this.moveItemStackTo(stack, 10, 46, false)) return ItemStack.EMPTY;
            } else if (index <= 9) {
                // Worker slot → player inventory then hotbar
                if (!this.moveItemStackTo(stack, 10, 46, false)) return ItemStack.EMPTY;
            } else if (isTool(stack)) {
                // Tool from player → tool slot first; if full, shuffle within player side
                if (!this.moveItemStackTo(stack, 0, 1, false)) {
                    if (index < 37) {
                        if (!this.moveItemStackTo(stack, 37, 46, false)) return ItemStack.EMPTY;
                    } else {
                        if (!this.moveItemStackTo(stack, 10, 37, false)) return ItemStack.EMPTY;
                    }
                }
            } else if (index < 37) {
                // Player inventory → hotbar
                if (!this.moveItemStackTo(stack, 37, 46, false)) return ItemStack.EMPTY;
            } else {
                // Hotbar → player inventory
                if (!this.moveItemStackTo(stack, 10, 37, false)) return ItemStack.EMPTY;
            }

            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }

        return remainder;
    }
}
