package village.automation.mod.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.CookingBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Menu for the Cooking Block GUI.
 *
 * <p>Slot layout (panel-relative pixel positions):
 * <pre>
 *   0  –  8   ingredient input   3×3 at ( 8 + col*18, 44 + row*18)
 *   9  – 17   cooked output      3×3 at (98 + col*18, 44 + row*18)
 *  18  – 44   player inventory   3×9 at ( 7 + col*18, 108 + row*18)
 *  45  – 53   player hotbar      1×9 at ( 7 + col*18, 166)
 * </pre>
 *
 * <p>ContainerData index 0 = cookTimer (0–200; 0 = idle).
 */
public class CookingBlockMenu extends AbstractContainerMenu {

    @Nullable private final CookingBlockEntity blockEntity;
    private final ContainerData containerData;
    private final String  workerName;
    private final JobType workerJob;

    // ── Server-side constructor ───────────────────────────────────────────────

    public CookingBlockMenu(int containerId, Inventory inventory, CookingBlockEntity be) {
        super(VillageMod.COOKING_BLOCK_MENU.get(), containerId);
        this.blockEntity   = be;
        this.containerData = be.data;

        String name = "";
        JobType job = JobType.UNEMPLOYED;
        if (be instanceof IWorkplaceBlockEntity wbe) {
            UUID uuid = wbe.getAssignedWorkerUUID();
            if (uuid != null && be.getLevel() instanceof ServerLevel sl) {
                var entity = sl.getEntity(uuid);
                if (entity instanceof VillagerWorkerEntity worker) {
                    name = worker.getDisplayName().getString();
                    job  = worker.getJob();
                }
            }
        }
        this.workerName = name;
        this.workerJob  = job;

        addInputSlots(be);
        addOutputSlots(be);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
        this.addDataSlots(containerData);
    }

    // ── Client-side constructor ───────────────────────────────────────────────

    public CookingBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.COOKING_BLOCK_MENU.get(), containerId);

        BlockPos pos       = buf.readBlockPos();
        this.workerName    = buf.readUtf();
        JobType job        = JobType.UNEMPLOYED;
        try { job = JobType.valueOf(buf.readUtf()); } catch (IllegalArgumentException ignored) {}
        this.workerJob = job;

        var be = inventory.player.level().getBlockEntity(pos);
        if (be instanceof CookingBlockEntity cook) {
            this.blockEntity   = cook;
            this.containerData = cook.data;
        } else {
            this.blockEntity   = null;
            this.containerData = new SimpleContainerData(1);
        }

        addInputSlots(this.blockEntity);
        addOutputSlots(this.blockEntity);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
        this.addDataSlots(containerData);
    }

    // ── Slot setup ────────────────────────────────────────────────────────────

    /** Ingredient input (3×3). Player may insert cookable items. */
    private void addInputSlots(@Nullable CookingBlockEntity be) {
        var container = be != null ? be.getInputContainer() : new SimpleContainer(9);
        for (int i = 0; i < 9; i++) {
            int row = i / 3, col = i % 3;
            this.addSlot(new Slot(container, i, 8 + col * 18, 44 + row * 18) {
                @Override public boolean mayPlace(ItemStack stack) {
                    Item item = stack.getItem();
                    return item == Items.WHEAT || item == Items.COD || item == Items.SALMON;
                }
            });
        }
    }

    /** Cooked output (3×3). Player may only take. */
    private void addOutputSlots(@Nullable CookingBlockEntity be) {
        var container = be != null ? be.getOutputContainer() : new SimpleContainer(9);
        for (int i = 0; i < 9; i++) {
            int row = i / 3, col = i % 3;
            this.addSlot(new Slot(container, i, 98 + col * 18, 44 + row * 18) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
            });
        }
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                this.addSlot(new Slot(inv, col + row * 9 + 9, 7 + col * 18, 108 + row * 18));
    }

    private void addPlayerHotbar(Inventory inv) {
        for (int col = 0; col < 9; col++)
            this.addSlot(new Slot(inv, col, 7 + col * 18, 166));
    }

    // ── Validity ──────────────────────────────────────────────────────────────

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        BlockPos pos = blockEntity.getBlockPos();
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64.0
                && player.level().getBlockState(pos).is(VillageMod.COOKING_BLOCK.get());
    }

    // ── Shift-click ───────────────────────────────────────────────────────────

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack     = slot.getItem();
        ItemStack remainder = stack.copy();

        if (index < 9) {
            // Input → player
            if (!this.moveItemStackTo(stack, 18, 54, false)) return ItemStack.EMPTY;
        } else if (index < 18) {
            // Output → player
            if (!this.moveItemStackTo(stack, 18, 54, false)) return ItemStack.EMPTY;
        } else {
            // Player → input if cookable, else swap main ↔ hotbar
            Item item = stack.getItem();
            boolean cookable = item == Items.WHEAT || item == Items.COD || item == Items.SALMON;
            if (cookable) {
                if (!this.moveItemStackTo(stack, 0, 9, false)) return ItemStack.EMPTY;
            } else {
                boolean moved = index < 45
                        ? this.moveItemStackTo(stack, 45, 54, false)
                        : this.moveItemStackTo(stack, 18, 45, false);
                if (!moved) return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return remainder;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String  getWorkerName() { return workerName; }
    public JobType getWorkerJob()  { return workerJob;  }
    public boolean hasWorker()     { return !workerName.isEmpty(); }

    /** Raw cook timer (200 = just started, 0 = idle). */
    public int getCookTimer() { return containerData.get(0); }

    /** Progress 0.0 (just started) → 1.0 (done/idle). */
    public float getCookProgress() {
        int t = getCookTimer();
        return t <= 0 ? 0f : 1.0f - (t / 200.0f);
    }

    public boolean isCooking() { return getCookTimer() > 0; }
}
