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
import net.minecraft.world.item.ItemStack;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.BeekeeperBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Menu for the Beekeeper GUI.
 *
 * <p>Slot layout:
 * <pre>
 *   0  –  8   fuel input (3×3 grid; logs go in, player may insert)
 *   9  – 17   output container (3×3 grid; honeycomb comes out, player may take)
 *  18  – 44   player main inventory (3×9)
 *  45  – 53   player hotbar (9)
 * </pre>
 *
 * <p>ContainerData (4 ints synced while open):
 * <pre>
 *   0 — pollenCount
 *   1 — smokingTimer
 *   2 — bee count
 *   3 — fuelBurning (0/1)
 * </pre>
 */
public class BeekeeperBlockMenu extends AbstractContainerMenu {

    @Nullable private final BeekeeperBlockEntity blockEntity;
    private final ContainerData containerData;
    private final String  workerName;
    private final JobType workerJob;

    // ── Server-side constructor ───────────────────────────────────────────────

    public BeekeeperBlockMenu(int containerId, Inventory inventory, BeekeeperBlockEntity be) {
        super(VillageMod.BEEKEEPER_MENU.get(), containerId);
        this.blockEntity   = be;
        this.containerData = be.data;

        String name = "";
        JobType job = JobType.UNEMPLOYED;
        UUID workerUUID = be.getAssignedWorkerUUID();
        if (workerUUID != null && be.getLevel() instanceof ServerLevel sl) {
            var entity = sl.getEntity(workerUUID);
            if (entity instanceof VillagerWorkerEntity worker) {
                name = worker.getDisplayName().getString();
                job  = worker.getJob();
            }
        }
        this.workerName = name;
        this.workerJob  = job;

        addFuelSlots(be);
        addOutputSlots(be);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
        this.addDataSlots(containerData);
    }

    // ── Client-side constructor ───────────────────────────────────────────────

    public BeekeeperBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.BEEKEEPER_MENU.get(), containerId);

        BlockPos pos = buf.readBlockPos();
        this.workerName = buf.readUtf();
        JobType job = JobType.UNEMPLOYED;
        try { job = JobType.valueOf(buf.readUtf()); } catch (IllegalArgumentException ignored) {}
        this.workerJob = job;

        var be = inventory.player.level().getBlockEntity(pos);
        if (be instanceof BeekeeperBlockEntity bk) {
            this.blockEntity   = bk;
            this.containerData = bk.data;
        } else {
            this.blockEntity   = null;
            this.containerData = new SimpleContainerData(4);
        }

        addFuelSlots(this.blockEntity);
        addOutputSlots(this.blockEntity);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
        this.addDataSlots(containerData);
    }

    // ── Slot setup ────────────────────────────────────────────────────────────

    /** Fuel input 3×3 (logs); player may insert only logs. */
    private void addFuelSlots(@Nullable BeekeeperBlockEntity be) {
        var container = be != null ? be.getFuelInput() : new SimpleContainer(9);
        for (int i = 0; i < 9; i++) {
            int row = i / 3;
            int col = i % 3;
            this.addSlot(new Slot(container, i, 8 + col * 18, 28 + row * 18) {
                @Override public boolean mayPlace(ItemStack stack) {
                    return BeekeeperBlockEntity.isWoodLog(stack);
                }
            });
        }
    }

    /** Output 3×3 (honeycomb); player may take but not insert. */
    private void addOutputSlots(@Nullable BeekeeperBlockEntity be) {
        var container = be != null ? be.getOutputContainer() : new SimpleContainer(9);
        for (int i = 0; i < 9; i++) {
            int row = i / 3;
            int col = i % 3;
            this.addSlot(new Slot(container, i, 98 + col * 18, 28 + row * 18) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
            });
        }
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 116 + row * 18));
    }

    private void addPlayerHotbar(Inventory inv) {
        for (int col = 0; col < 9; col++)
            this.addSlot(new Slot(inv, col, 8 + col * 18, 170));
    }

    // ── Validity / shift-click ────────────────────────────────────────────────

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        BlockPos pos = blockEntity.getBlockPos();
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64.0
                && player.level().getBlockState(pos).is(VillageMod.BEEKEEPER_BLOCK.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack remainder = stack.copy();

        if (index < 9) {
            // Fuel slot → player inventory
            if (!this.moveItemStackTo(stack, 18, 54, false)) return ItemStack.EMPTY;
        } else if (index < 18) {
            // Output slot → player inventory
            if (!this.moveItemStackTo(stack, 18, 54, false)) return ItemStack.EMPTY;
        } else if (BeekeeperBlockEntity.isWoodLog(stack)) {
            // Player inv has logs → move to fuel
            if (!this.moveItemStackTo(stack, 0, 9, false)) return ItemStack.EMPTY;
        } else {
            // Player inv: move between main/hotbar
            boolean moved = index < 45
                    ? this.moveItemStackTo(stack, 45, 54, false)
                    : this.moveItemStackTo(stack, 18, 45, false);
            if (!moved) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return remainder;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String  getWorkerName()    { return workerName; }
    public JobType getWorkerJob()     { return workerJob; }
    public boolean hasWorker()        { return !workerName.isEmpty(); }

    public int getPollenCount()       { return containerData.get(0); }
    public int getSmokingTimer()      { return containerData.get(1); }
    public int getBeeCount()          { return containerData.get(2); }
    public boolean isFuelBurning()    { return containerData.get(3) != 0; }
    public boolean hasFuel()          { return containerData.get(4) != 0; }

    /** Smoking progress 0.0–1.0 (0 = fresh/idle, 1 = almost done). */
    public float getSmokingProgress() {
        int t = getSmokingTimer();
        if (t <= 0) return 0f;
        return 1.0f - (t / (float) BeekeeperBlockEntity.SMOKE_TICKS);
    }
}
