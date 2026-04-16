package village.automation.mod.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;

/**
 * Abstract base menu for all profession workplace blocks.
 *
 * <p>Slot layout (template workplaces have no block-specific slots):
 * <pre>
 *   0 – 26  player main inventory (3 rows × 9)
 *  27 – 35  player hotbar
 * </pre>
 *
 * <p>Worker name and job are resolved server-side and baked into the
 * open-menu packet, so the client screen can display them without an
 * extra round-trip.
 *
 * <p>Subclasses must:
 * <ul>
 *   <li>Call the appropriate {@code super} constructor in both the
 *       server-side and {@link FriendlyByteBuf} variants.
 *   <li>Override {@link #stillValid} with the correct block reference.
 * </ul>
 */
public abstract class WorkplaceBlockMenu extends AbstractContainerMenu {

    @Nullable protected final BlockEntity blockEntity;
    private final String  workerName;
    private final JobType workerJob;

    // ── Server-side constructor ───────────────────────────────────────────────

    protected WorkplaceBlockMenu(MenuType<?> type, int id,
                                  Inventory inv, @Nullable BlockEntity be) {
        super(type, id);
        this.blockEntity = be;

        // Resolve worker info from the block entity
        String name = "";
        JobType job = JobType.UNEMPLOYED;
        if (be instanceof IWorkplaceBlockEntity wbe
                && wbe.getAssignedWorkerUUID() != null
                && be.getLevel() instanceof ServerLevel sl) {
            var entity = sl.getEntity(wbe.getAssignedWorkerUUID());
            if (entity instanceof VillagerWorkerEntity worker) {
                name = worker.getDisplayName().getString();
                job  = worker.getJob();
            }
        }
        this.workerName = name;
        this.workerJob  = job;

        addPlayerInventory(inv);
        addPlayerHotbar(inv);
    }

    // ── Client-side constructor ───────────────────────────────────────────────

    protected WorkplaceBlockMenu(MenuType<?> type, int id,
                                  Inventory inv, FriendlyByteBuf buf) {
        super(type, id);

        BlockPos pos = buf.readBlockPos();
        this.workerName = buf.readUtf();
        JobType job = JobType.UNEMPLOYED;
        try { job = JobType.valueOf(buf.readUtf()); } catch (IllegalArgumentException ignored) {}
        this.workerJob = job;

        this.blockEntity = inv.player.level().getBlockEntity(pos);

        addPlayerInventory(inv);
        addPlayerHotbar(inv);
    }

    // ── Slot setup ────────────────────────────────────────────────────────────

    /** Slots 0-26: player main inventory (3 rows × 9 cols, y=34). */
    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 34 + row * 18));
    }

    /** Slots 27-35: player hotbar (y=88). */
    private void addPlayerHotbar(Inventory inv) {
        for (int col = 0; col < 9; col++)
            this.addSlot(new Slot(inv, col, 8 + col * 18, 88));
    }

    // ── Validity ──────────────────────────────────────────────────────────────

    /**
     * Default: distance-based check (≤ 8 blocks).  Subclasses may override
     * to also validate the block type at the position.
     */
    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        BlockPos pos = blockEntity.getBlockPos();
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64.0;
    }

    // ── Shift-click ───────────────────────────────────────────────────────────

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack     = slot.getItem();
        ItemStack remainder = stack.copy();

        // Template workplaces have no block slots, so just shuffle player inv ↔ hotbar
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
}
