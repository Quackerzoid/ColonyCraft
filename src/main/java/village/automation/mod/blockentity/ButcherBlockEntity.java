package village.automation.mod.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.ButcherBlockMenu;

/**
 * Block entity for the Butcher workplace.
 *
 * <p>Holds a single 9-slot output container where the butcher deposits
 * mob drops after a kill.  Copper golems collect from the DOWN-face
 * {@link IItemHandler} capability and deliver items to a chest.
 */
public class ButcherBlockEntity extends WorkplaceBlockEntityBase {

    // ── Inventory ─────────────────────────────────────────────────────────────
    private final SimpleContainer outputContainer = new SimpleContainer(9);
    private final IItemHandler    outputHandler   = new InvWrapper(outputContainer);

    // ── Construction ──────────────────────────────────────────────────────────

    public ButcherBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.BUTCHER_BE.get(), pos, state);
        outputContainer.addListener(c -> setChanged());
    }

    // ── IWorkplaceBlockEntity ─────────────────────────────────────────────────

    @Override
    public JobType getRequiredJob() { return JobType.BUTCHER; }

    // ── Inventory accessors ───────────────────────────────────────────────────

    public SimpleContainer getOutputContainer() { return outputContainer; }
    public IItemHandler    getOutputHandler()   { return outputHandler; }

    // ── MenuProvider ──────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.butcher_block");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ButcherBlockMenu(containerId, inventory, this);
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        ListTag outputList = new ListTag();
        for (int i = 0; i < outputContainer.getContainerSize(); i++) {
            ItemStack stack = outputContainer.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                slotTag.put("Item", stack.save(registries));
                outputList.add(slotTag);
            }
        }
        tag.put("OutputInventory", outputList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        outputContainer.clearContent();
        if (tag.contains("OutputInventory")) {
            ListTag outputList = tag.getList("OutputInventory", Tag.TAG_COMPOUND);
            for (int i = 0; i < outputList.size(); i++) {
                CompoundTag slotTag = outputList.getCompound(i);
                int slot = slotTag.getByte("Slot") & 0xFF;
                if (slot < outputContainer.getContainerSize()) {
                    outputContainer.setItem(slot,
                            ItemStack.parseOptional(registries, slotTag.getCompound("Item")));
                }
            }
        }
    }
}
