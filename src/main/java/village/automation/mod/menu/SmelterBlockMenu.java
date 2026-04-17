package village.automation.mod.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.IWorkplaceBlockEntity;
import village.automation.mod.blockentity.SmelterBlockEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

import javax.annotation.Nullable;

/**
 * Container menu for the Smelter Block.
 *
 * <h3>Slot layout</h3>
 * <pre>
 *   0   Ore input   — blast-furnace smeltables only (mayPlace validated)
 *   1   Fuel        — valid furnace fuel only  (mayPlace validated)
 *   2   Output      — take-only (mayPlace = false)
 *   3–29  Player main inventory  (3 rows × 9)
 *  30–38  Player hotbar          (9 slots)
 * </pre>
 *
 * <p>Worker name and job are resolved server-side and baked into the
 * open-menu packet so the client screen displays them without an extra
 * round-trip (same approach as {@link WorkplaceBlockMenu}).
 */
public class SmelterBlockMenu extends AbstractContainerMenu {

    // ── Slot index ranges (inclusive start, exclusive end for moveItemStackTo) ─
    static final int SLOT_ORE        = 0;
    static final int SLOT_FUEL       = 1;
    static final int SLOT_OUTPUT     = 2;
    static final int BLOCK_SLOTS_END = 3;   // exclusive
    static final int PLAYER_INV_END  = 30;  // exclusive (slots 3-29)
    static final int HOTBAR_END      = 39;  // exclusive (slots 30-38)

    @Nullable private final SmelterBlockEntity blockEntity;
    private final String  workerName;
    private final JobType workerJob;

    // ── Server-side constructor ───────────────────────────────────────────────

    public SmelterBlockMenu(int containerId, Inventory playerInventory, SmelterBlockEntity be) {
        super(VillageMod.SMELTER_BLOCK_MENU.get(), containerId);
        this.blockEntity = be;

        // Resolve worker info server-side
        String name = "";
        JobType job = JobType.UNEMPLOYED;
        if (be.getAssignedWorkerUUID() != null && be.getLevel() instanceof ServerLevel sl) {
            var entity = sl.getEntity(be.getAssignedWorkerUUID());
            if (entity instanceof VillagerWorkerEntity worker) {
                name = worker.getDisplayName().getString();
                job  = worker.getJob();
            }
        }
        this.workerName = name;
        this.workerJob  = job;

        Level level = playerInventory.player.level();
        addBlockSlots(be, level);
        addPlayerInventory(playerInventory);
        addPlayerHotbar(playerInventory);
    }

    // ── Client-side constructor (via FriendlyByteBuf / IMenuTypeExtension) ────

    public SmelterBlockMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        super(VillageMod.SMELTER_BLOCK_MENU.get(), containerId);

        BlockPos pos = buf.readBlockPos();
        this.workerName = buf.readUtf();
        JobType job = JobType.UNEMPLOYED;
        try { job = JobType.valueOf(buf.readUtf()); } catch (IllegalArgumentException ignored) {}
        this.workerJob = job;

        BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
        this.blockEntity = be instanceof SmelterBlockEntity sbe ? sbe : null;

        Level level = playerInventory.player.level();
        if (this.blockEntity != null) {
            addBlockSlots(this.blockEntity, level);
        } else {
            // Fallback: add dummy slots so slot indices remain consistent
            addDummyBlockSlots();
        }
        addPlayerInventory(playerInventory);
        addPlayerHotbar(playerInventory);
    }

    // ── Slot setup ────────────────────────────────────────────────────────────

    /**
     * Adds the three smelter-specific slots with item-type validation.
     *
     * <ul>
     *   <li>Ore slot  — only accepts items that have a {@code blasting} recipe.</li>
     *   <li>Fuel slot — only accepts items with positive furnace burn time.</li>
     *   <li>Output    — take-only; cannot receive items from players.</li>
     * </ul>
     */
    private void addBlockSlots(SmelterBlockEntity be, Level level) {
        // Slot 0 — Ore input (x=56, y=35)
        this.addSlot(new Slot(be.getOreContainer(), 0, 56, 35) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                if (stack.isEmpty()) return false;
                return level.getRecipeManager()
                        .getRecipeFor(RecipeType.BLASTING, new SingleRecipeInput(stack), level)
                        .isPresent();
            }
        });

        // Slot 1 — Fuel (x=56, y=67)
        this.addSlot(new Slot(be.getFuelContainer(), 0, 56, 67) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                // AbstractFurnaceBlockEntity.isFuel checks vanilla + NeoForge-registered fuels.
                return AbstractFurnaceBlockEntity.isFuel(stack);
            }
        });

        // Slot 2 — Output (x=116, y=50) — take-only
        this.addSlot(new Slot(be.getOutputContainer(), 0, 116, 50) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
    }

    /** Fallback when the block entity cannot be found client-side. */
    private void addDummyBlockSlots() {
        net.minecraft.world.SimpleContainer dummy = new net.minecraft.world.SimpleContainer(3);
        this.addSlot(new Slot(dummy, 0, 56,  35) { @Override public boolean mayPlace(ItemStack s) { return false; } });
        this.addSlot(new Slot(dummy, 1, 56,  67) { @Override public boolean mayPlace(ItemStack s) { return false; } });
        this.addSlot(new Slot(dummy, 2, 116, 50) { @Override public boolean mayPlace(ItemStack s) { return false; } });
    }

    /** Slots 3–29: player main inventory (3 rows × 9, y = 90/108/126). */
    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 90 + row * 18));
    }

    /** Slots 30–38: player hotbar (y = 148). */
    private void addPlayerHotbar(Inventory inv) {
        for (int col = 0; col < 9; col++)
            this.addSlot(new Slot(inv, col, 8 + col * 18, 148));
    }

    // ── Validity ──────────────────────────────────────────────────────────────

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        BlockPos pos = blockEntity.getBlockPos();
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64.0
                && player.level().getBlockState(pos).is(VillageMod.SMELTER_BLOCK.get());
    }

    // ── Shift-click ───────────────────────────────────────────────────────────

    /**
     * Shift-click routing:
     * <ul>
     *   <li>Block slot (0-2) → player main inventory, then hotbar.</li>
     *   <li>Player slot → try ore slot (0) then fuel slot (1) first; if neither
     *       accepts, shuffle within player inventory (main ↔ hotbar).</li>
     * </ul>
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack    = slot.getItem();
        ItemStack original = stack.copy();

        if (index < BLOCK_SLOTS_END) {
            // Block slot → player inventory (fill from end to match vanilla convention)
            if (!this.moveItemStackTo(stack, BLOCK_SLOTS_END, HOTBAR_END, true))
                return ItemStack.EMPTY;
        } else {
            // Player slot → try ore input then fuel; if neither fits, shuffle player slots
            boolean movedToBlock = this.moveItemStackTo(stack, SLOT_ORE, BLOCK_SLOTS_END, false);
            if (!movedToBlock) {
                if (index < PLAYER_INV_END) {
                    // Main inventory → hotbar
                    if (!this.moveItemStackTo(stack, PLAYER_INV_END, HOTBAR_END, false))
                        return ItemStack.EMPTY;
                } else {
                    // Hotbar → main inventory
                    if (!this.moveItemStackTo(stack, BLOCK_SLOTS_END, PLAYER_INV_END, false))
                        return ItemStack.EMPTY;
                }
            }
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        return original;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String  getWorkerName() { return workerName; }
    public JobType getWorkerJob()  { return workerJob;  }
    public boolean hasWorker()     { return !workerName.isEmpty(); }

    @Nullable
    public SmelterBlockEntity getBlockEntity() { return blockEntity; }
}
