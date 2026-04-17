package village.automation.mod.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.ButcherBlockEntity;

import javax.annotation.Nullable;

/**
 * Menu for the Butcher block GUI.
 *
 * <p>Slot layout:
 * <pre>
 *   0  –  8   output container (3×3 grid, read-only)
 *   9  – 35   player main inventory (3×9)
 *  36  – 44   player hotbar (9)
 * </pre>
 */
public class ButcherBlockMenu extends AbstractContainerMenu {

    @Nullable private final ButcherBlockEntity blockEntity;

    // ── Server-side constructor ───────────────────────────────────────────────

    public ButcherBlockMenu(int containerId, Inventory inventory, ButcherBlockEntity be) {
        super(VillageMod.BUTCHER_MENU.get(), containerId);
        this.blockEntity = be;
        addOutputSlots(be);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
    }

    // ── Client-side constructor ───────────────────────────────────────────────

    public ButcherBlockMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.BUTCHER_MENU.get(), containerId);
        BlockPos pos = buf.readBlockPos();
        var be = inventory.player.level().getBlockEntity(pos);
        this.blockEntity = be instanceof ButcherBlockEntity b ? b : null;
        addOutputSlots(this.blockEntity);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
    }

    // ── Slot setup ────────────────────────────────────────────────────────────

    private void addOutputSlots(@Nullable ButcherBlockEntity be) {
        var container = be != null ? be.getOutputContainer() : new SimpleContainer(9);
        for (int i = 0; i < 9; i++) {
            int row = i / 3;
            int col = i % 3;
            this.addSlot(new Slot(container, i, 44 + col * 18, 28 + row * 18) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
            });
        }
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 102 + row * 18));
    }

    private void addPlayerHotbar(Inventory inv) {
        for (int col = 0; col < 9; col++)
            this.addSlot(new Slot(inv, col, 8 + col * 18, 156));
    }

    // ── Validity / shift-click ────────────────────────────────────────────────

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;
        BlockPos pos = blockEntity.getBlockPos();
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64.0
                && player.level().getBlockState(pos).is(VillageMod.BUTCHER_BLOCK.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack remainder = stack.copy();

        if (index < 9) {
            // Output → player inventory
            if (!this.moveItemStackTo(stack, 9, 45, false)) return ItemStack.EMPTY;
        } else if (index < 45) {
            // Player inv → can't insert into output; move within player inv
            boolean moved = index < 36
                    ? this.moveItemStackTo(stack, 36, 45, false)
                    : this.moveItemStackTo(stack, 9, 36, false);
            if (!moved) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return remainder;
    }
}
