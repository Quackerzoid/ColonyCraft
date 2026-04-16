package village.automation.mod.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.FarmBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;

public class FarmBlockMenu extends AbstractContainerMenu {

    // ── Slot layout ───────────────────────────────────────────────────────────
    //  0 –  2 : seed input slots  (FarmBlockEntity.seedContainer)
    //  3 – 11 : crop output slots (FarmBlockEntity.outputContainer, display-only)
    // 12 – 38 : player main inventory
    // 39 – 47 : player hotbar

    @Nullable
    private final FarmBlockEntity blockEntity;

    // Worker info — resolved server-side and packed into the open-menu buf
    private final String  workerName;
    private final JobType workerJob;

    // ── Server-side constructor ───────────────────────────────────────────────
    public FarmBlockMenu(int containerId, Inventory inventory, FarmBlockEntity be) {
        super(VillageMod.FARM_BLOCK_MENU.get(), containerId);
        this.blockEntity = be;

        // Resolve worker display info
        String name = "";
        JobType job = JobType.UNEMPLOYED;
        if (be.getAssignedWorkerUUID() != null
                && be.getLevel() instanceof ServerLevel serverLevel) {
            var entity = serverLevel.getEntity(be.getAssignedWorkerUUID());
            if (entity instanceof VillagerWorkerEntity worker) {
                name = worker.getDisplayName().getString();
                job  = worker.getJob();
            }
        }
        this.workerName = name;
        this.workerJob  = job;

        addSeedSlots(be);
        addOutputSlots(be);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
    }

    // ── Client-side constructor ───────────────────────────────────────────────
    public FarmBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.FARM_BLOCK_MENU.get(), containerId);

        BlockPos pos = buf.readBlockPos();
        this.workerName = buf.readUtf();
        JobType job = JobType.UNEMPLOYED;
        try { job = JobType.valueOf(buf.readUtf()); } catch (IllegalArgumentException ignored) {}
        this.workerJob = job;

        BlockEntity be = inventory.player.level().getBlockEntity(pos);
        this.blockEntity = be instanceof FarmBlockEntity farm ? farm : null;

        addSeedSlots(this.blockEntity);
        addOutputSlots(this.blockEntity);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
    }

    // ── Slot setup ────────────────────────────────────────────────────────────

    /** Slots 0-2: seed input, 3 across starting at x=8, y=27. */
    private void addSeedSlots(@Nullable FarmBlockEntity be) {
        var container = be != null ? be.getSeedContainer()
                : new net.minecraft.world.SimpleContainer(3);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            this.addSlot(new Slot(container, idx, 8 + idx * 18, 27) {
                @Override public boolean mayPlace(ItemStack stack) { return isSeed(stack); }
            });
        }
    }

    /**
     * Slots 3-11: crop output — 3 columns × 3 rows on the right half of the
     * panel (x=98,116,134; y=27,45,63).  Players may take items out but not
     * insert them; only the Farmer worker deposits here.
     */
    private void addOutputSlots(@Nullable FarmBlockEntity be) {
        var container = be != null ? be.getOutputContainer()
                : new net.minecraft.world.SimpleContainer(9);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                final int idx = row * 3 + col;
                this.addSlot(new Slot(container, idx, 98 + col * 18, 27 + row * 18) {
                    // Output-only: players can take items out but not put items in
                    @Override public boolean mayPlace(ItemStack stack) { return false; }
                });
            }
        }
    }

    /** Slots 12-38: player main inventory (3 rows × 9 cols). */
    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 98 + row * 18));
    }

    /** Slots 39-47: player hotbar. */
    private void addPlayerHotbar(Inventory inv) {
        for (int col = 0; col < 9; col++)
            this.addSlot(new Slot(inv, col, 8 + col * 18, 156));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String  getWorkerName() { return workerName; }
    public JobType getWorkerJob()  { return workerJob;  }
    public boolean hasWorker()     { return !workerName.isEmpty(); }

    // ── Validity ──────────────────────────────────────────────────────────────

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        return AbstractContainerMenu.stillValid(
                ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos()),
                player, VillageMod.FARM_BLOCK.get());
    }

    // ── Shift-click ───────────────────────────────────────────────────────────

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack     = slot.getItem();
        ItemStack remainder = stack.copy();

        if (index < 3) {
            // Seed slot → player inventory
            if (!this.moveItemStackTo(stack, 12, 48, false)) return ItemStack.EMPTY;
        } else if (index < 12) {
            // Output slot → player inventory
            if (!this.moveItemStackTo(stack, 12, 48, false)) return ItemStack.EMPTY;
        } else {
            // Player slot
            if (isSeed(stack)) {
                // Seeds → seed slots first
                if (!this.moveItemStackTo(stack, 0, 3, false)) {
                    if (!shufflePlayer(stack, index)) return ItemStack.EMPTY;
                }
            } else {
                if (!shufflePlayer(stack, index)) return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return remainder;
    }

    private boolean shufflePlayer(ItemStack stack, int fromIndex) {
        // Main inv (12-38) ↔ hotbar (39-47)
        return fromIndex < 39
                ? this.moveItemStackTo(stack, 39, 48, false)
                : this.moveItemStackTo(stack, 12, 39, false);
    }

    // ── Seed check (mirrors FarmerWorkGoal.isSeed) ────────────────────────────

    private static boolean isSeed(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof CropBlock;
    }
}
