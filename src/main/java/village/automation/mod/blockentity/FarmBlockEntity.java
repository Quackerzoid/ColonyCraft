package village.automation.mod.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.FarmBlockMenu;

public class FarmBlockEntity extends WorkplaceBlockEntityBase {

    /**
     * Seed input inventory — 3 slots.  The assigned Farmer worker will pull
     * seeds from here into their personal inventory before planting.
     */
    private final SimpleContainer seedContainer   = new SimpleContainer(3);

    /**
     * Crop output inventory — 9 slots.  The assigned Farmer worker deposits
     * harvested (non-seed) crops here for the player to collect.
     */
    private final SimpleContainer outputContainer = new SimpleContainer(9);

    /**
     * Cached {@link IItemHandler} wrapping {@link #outputContainer}.
     * Exposed on the {@link net.minecraft.core.Direction#DOWN DOWN} face so that
     * a hopper placed underneath the Farm Block can pull crops out automatically.
     *
     * <p>Extending this pattern to other profession blocks: add an analogous
     * {@code IItemHandler} field wrapping whichever container should be
     * accessible, then register it in
     * {@link village.automation.mod.VillageMod#registerCapabilities}.
     */
    private final IItemHandler outputHandler = new InvWrapper(outputContainer);

    public FarmBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.FARM_BLOCK_BE.get(), pos, state);
        this.seedContainer  .addListener(c -> this.setChanged());
        this.outputContainer.addListener(c -> this.setChanged());
    }

    // ── IWorkplaceBlockEntity ─────────────────────────────────────────────────

    @Override
    public JobType getRequiredJob() { return JobType.FARMER; }

    // ── Containers ────────────────────────────────────────────────────────────

    public SimpleContainer getSeedContainer()   { return seedContainer;   }
    public SimpleContainer getOutputContainer() { return outputContainer; }

    /**
     * Returns the item-handler view of the output (crop) inventory.
     * Registered as the {@link net.neoforged.neoforge.capabilities.Capabilities.ItemHandler#BLOCK}
     * capability on the {@link net.minecraft.core.Direction#DOWN DOWN} face so
     * that hoppers placed beneath the block pull from this inventory.
     */
    public IItemHandler getOutputHandler() { return outputHandler; }

    // ── MenuProvider ──────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.farm_block");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new FarmBlockMenu(containerId, inventory, this);
    }

    // ── NBT ───────────────────────────────────────────────────────────────────
    // super.saveAdditional / loadAdditional handles linkedHeartPos + assignedWorkerUUID.

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        // Seed container
        net.minecraft.nbt.ListTag seedList = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < seedContainer.getContainerSize(); i++) {
            ItemStack stack = seedContainer.getItem(i);
            if (!stack.isEmpty()) {
                net.minecraft.nbt.CompoundTag slotTag = new net.minecraft.nbt.CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                slotTag.put("Item", stack.save(registries));
                seedList.add(slotTag);
            }
        }
        tag.put("SeedInventory", seedList);

        // Output container
        net.minecraft.nbt.ListTag outputList = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < outputContainer.getContainerSize(); i++) {
            ItemStack stack = outputContainer.getItem(i);
            if (!stack.isEmpty()) {
                net.minecraft.nbt.CompoundTag slotTag = new net.minecraft.nbt.CompoundTag();
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

        // Seed container
        this.seedContainer.clearContent();
        if (tag.contains("SeedInventory")) {
            net.minecraft.nbt.ListTag seedList = tag.getList("SeedInventory", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < seedList.size(); i++) {
                net.minecraft.nbt.CompoundTag slotTag = seedList.getCompound(i);
                int slot = slotTag.getByte("Slot") & 0xFF;
                if (slot < seedContainer.getContainerSize()) {
                    seedContainer.setItem(slot, ItemStack.parseOptional(registries, slotTag.getCompound("Item")));
                }
            }
        }

        // Output container
        this.outputContainer.clearContent();
        if (tag.contains("OutputInventory")) {
            net.minecraft.nbt.ListTag outputList = tag.getList("OutputInventory", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < outputList.size(); i++) {
                net.minecraft.nbt.CompoundTag slotTag = outputList.getCompound(i);
                int slot = slotTag.getByte("Slot") & 0xFF;
                if (slot < outputContainer.getContainerSize()) {
                    outputContainer.setItem(slot, ItemStack.parseOptional(registries, slotTag.getCompound("Item")));
                }
            }
        }
    }
}
