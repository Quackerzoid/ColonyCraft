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
import village.automation.mod.blockentity.AnimalPenBlockEntity;
import village.automation.mod.entity.AnimalType;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Menu for the Animal Pen GUI.
 *
 * <p>Slot layout:
 * <pre>
 *   0  –  8   output inventory (3×3 grid; player may take but not insert)
 *   9  – 35   player main inventory (3 rows × 9)
 *  36  – 44   player hotbar
 * </pre>
 *
 * <p>One {@link ContainerData} integer is synced while the menu is open:
 * <ul>
 *   <li>Index 0 — {@code targetAnimalType} ordinal
 * </ul>
 *
 * <p>Button 0 cycles the animal type forward; button 1 cycles it backward.
 */
public class AnimalPenBlockMenu extends AbstractContainerMenu {

    @Nullable private final AnimalPenBlockEntity blockEntity;
    private final ContainerData containerData;
    private final String  workerName;
    private final JobType workerJob;

    // ── Server-side constructor ───────────────────────────────────────────────

    public AnimalPenBlockMenu(int containerId, Inventory inventory, AnimalPenBlockEntity be) {
        super(VillageMod.ANIMAL_PEN_MENU.get(), containerId);
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

        addOutputSlots(be);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
        this.addDataSlots(containerData);
    }

    // ── Client-side constructor ───────────────────────────────────────────────

    public AnimalPenBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.ANIMAL_PEN_MENU.get(), containerId);

        BlockPos pos = buf.readBlockPos();
        this.workerName = buf.readUtf();
        JobType job = JobType.UNEMPLOYED;
        try { job = JobType.valueOf(buf.readUtf()); } catch (IllegalArgumentException ignored) {}
        this.workerJob = job;

        var be = inventory.player.level().getBlockEntity(pos);
        if (be instanceof AnimalPenBlockEntity pen) {
            this.blockEntity   = pen;
            this.containerData = pen.data;
        } else {
            this.blockEntity   = null;
            this.containerData = new SimpleContainerData(1);
        }

        addOutputSlots(this.blockEntity);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
        this.addDataSlots(containerData);
    }

    // ── Slot setup ────────────────────────────────────────────────────────────

    private void addOutputSlots(@Nullable AnimalPenBlockEntity be) {
        var container = be != null ? be.getOutputContainer() : new SimpleContainer(9);
        for (int i = 0; i < 9; i++) {
            int row = i / 3;
            int col = i % 3;
            this.addSlot(new Slot(container, i, 61 + col * 18, 34 + row * 18) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
            });
        }
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                this.addSlot(new Slot(inv, col + row * 9 + 9, 7 + col * 18, 103 + row * 18));
    }

    private void addPlayerHotbar(Inventory inv) {
        for (int col = 0; col < 9; col++)
            this.addSlot(new Slot(inv, col, 7 + col * 18, 157));
    }

    // ── Button handling ───────────────────────────────────────────────────────

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (blockEntity == null) return false;
        AnimalType current = blockEntity.getTargetAnimalType();
        if (id == 0) {
            blockEntity.setTargetAnimalType(current.next());
        } else if (id == 1) {
            blockEntity.setTargetAnimalType(current.prev());
        }
        return true;
    }

    // ── Validity ──────────────────────────────────────────────────────────────

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        BlockPos pos = blockEntity.getBlockPos();
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64.0
                && player.level().getBlockState(pos).is(VillageMod.ANIMAL_PEN.get());
    }

    // ── Shift-click ───────────────────────────────────────────────────────────

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack     = slot.getItem();
        ItemStack remainder = stack.copy();

        if (index < 9) {
            if (!this.moveItemStackTo(stack, 9, 45, false)) return ItemStack.EMPTY;
        } else {
            boolean moved = index < 36
                    ? this.moveItemStackTo(stack, 36, 45, false)
                    : this.moveItemStackTo(stack, 9,  36, false);
            if (!moved) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return remainder;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String     getWorkerName()        { return workerName; }
    public JobType    getWorkerJob()         { return workerJob; }
    public boolean    hasWorker()            { return !workerName.isEmpty(); }
    public int        getAnimalTypeOrdinal() { return containerData.get(0); }
    public AnimalType getAnimalType()        {
        AnimalType[] values = AnimalType.values();
        int ord = getAnimalTypeOrdinal();
        return values[Math.min(ord, values.length - 1)];
    }
}
