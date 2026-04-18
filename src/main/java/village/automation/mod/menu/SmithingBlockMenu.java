package village.automation.mod.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
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
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.blockentity.SmithingBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.SmithRecipe;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Menu for the Smithing Block GUI.
 *
 * <p>Slot layout (panel-relative pixel positions):
 * <pre>
 *   0  – 26   player inventory   3×9 at (7 + col*18, 110 + row*18)
 *  27  – 35   player hotbar      1×9 at (7 + col*18, 168)
 * </pre>
 *
 * <p>ContainerData:
 * <pre>
 *   index 0 = smithCraftingState (0=IDLE, 1=AWAITING, 2=CRAFTING, 3=READY)
 *   index 1 = smithCraftingTimer (0-200, counts down during CRAFTING)
 *   index 2 = result item raw registry ID (-1 = no active recipe)
 * </pre>
 */
public class SmithingBlockMenu extends AbstractContainerMenu {

    @Nullable private final SmithingBlockEntity blockEntity;
    private final ContainerData containerData;
    private final String  workerName;
    private final JobType workerJob;

    // ── Server-side constructor ───────────────────────────────────────────────

    public SmithingBlockMenu(int containerId, Inventory inventory, SmithingBlockEntity be) {
        super(VillageMod.SMITHING_BLOCK_MENU.get(), containerId);
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

        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
        this.addDataSlots(containerData);
    }

    // ── Client-side constructor ───────────────────────────────────────────────

    public SmithingBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.SMITHING_BLOCK_MENU.get(), containerId);

        BlockPos pos       = buf.readBlockPos();
        this.workerName    = buf.readUtf();
        JobType job        = JobType.UNEMPLOYED;
        try { job = JobType.valueOf(buf.readUtf()); } catch (IllegalArgumentException ignored) {}
        this.workerJob = job;

        var be = inventory.player.level().getBlockEntity(pos);
        if (be instanceof SmithingBlockEntity smith) {
            this.blockEntity   = smith;
            this.containerData = smith.data;
        } else {
            this.blockEntity   = null;
            this.containerData = new SimpleContainerData(3);
        }

        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
        this.addDataSlots(containerData);
    }

    // ── Slot setup ────────────────────────────────────────────────────────────

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                this.addSlot(new Slot(inv, col + row * 9 + 9, 7 + col * 18, 110 + row * 18));
    }

    private void addPlayerHotbar(Inventory inv) {
        for (int col = 0; col < 9; col++)
            this.addSlot(new Slot(inv, col, 7 + col * 18, 168));
    }

    // ── Validity ──────────────────────────────────────────────────────────────

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        BlockPos pos = blockEntity.getBlockPos();
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64.0
                && player.level().getBlockState(pos).is(VillageMod.SMITHING_BLOCK.get());
    }

    // ── Shift-click ───────────────────────────────────────────────────────────

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack     = slot.getItem();
        ItemStack remainder = stack.copy();

        // Shuffle player inv ↔ hotbar
        boolean moved = index < 27
                ? this.moveItemStackTo(stack, 27, 36, false)
                : this.moveItemStackTo(stack, 0, 27, false);

        if (!moved) return ItemStack.EMPTY;

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return remainder;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String  getWorkerName() { return workerName; }
    public JobType getWorkerJob()  { return workerJob;  }
    public boolean hasWorker()     { return !workerName.isEmpty(); }

    /** Crafting state: 0=IDLE, 1=AWAITING, 2=CRAFTING, 3=READY. */
    public int getSmithState() { return containerData.get(0); }

    /** Craft timer (counts down to 0 during CRAFTING). */
    public int getSmithTimer() { return containerData.get(1); }

    /** Max craft timer set when crafting began (varies by blacksmith level). */
    public int getSmithMaxTimer() { return containerData.get(3); }

    /**
     * Progress 0.0 (just started) → 1.0 (done).
     * Returns 0 when not in CRAFTING state.
     */
    public float getSmithProgress() {
        int t   = getSmithTimer();
        int max = getSmithMaxTimer();
        if (t <= 0 || max <= 0) return 0f;
        return 1.0f - ((float) t / max);
    }

    /**
     * The item being produced, decoded from the raw registry ID in ContainerData.
     * Returns {@link Items#AIR} when there is no active recipe.
     */
    @Nullable
    public Item getSmithResultItem() {
        int id = containerData.get(2);
        if (id < 0) return null;
        Item item = BuiltInRegistries.ITEM.byId(id);
        return (item == Items.AIR) ? null : item;
    }

    /**
     * The SmithRecipe for the currently active result item, or {@code null}.
     * Safe to call client-side — SmithRecipe is a pure Java registry.
     */
    @Nullable
    public SmithRecipe getSmithRecipe() {
        Item result = getSmithResultItem();
        if (result == null) return null;
        return SmithRecipe.findFor(result).orElse(null);
    }

    public boolean isCrafting() { return getSmithState() == VillagerWorkerEntity.SMITH_CRAFTING; }
    public boolean isReady()    { return getSmithState() == VillagerWorkerEntity.SMITH_READY;    }
    public boolean isAwaiting() { return getSmithState() == VillagerWorkerEntity.SMITH_AWAITING; }
    public boolean isIdle()     { return getSmithState() == VillagerWorkerEntity.SMITH_IDLE;     }
}
