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
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.SmelterBlockMenu;

/**
 * Block entity for the Smelter Block.
 *
 * <p>Holds three single-slot inventories:
 * <dl>
 *   <dt>{@link #oreContainer}</dt>
 *   <dd>Items to be smelted — must be valid blast-furnace inputs.</dd>
 *   <dt>{@link #fuelContainer}</dt>
 *   <dd>Fuel consumed during smelting — any valid furnace fuel.</dd>
 *   <dt>{@link #outputContainer}</dt>
 *   <dd>Smelted results placed here by the worker; players take from here.</dd>
 * </dl>
 *
 * <p>No smelting happens inside this class.  All smelting logic lives in the
 * Smelter profession's work-goal (to be added later).
 */
public class SmelterBlockEntity extends WorkplaceBlockEntityBase {

    /** Input slot — holds one ore/metal item waiting to be smelted. */
    private final SimpleContainer oreContainer    = new SimpleContainer(1);
    /** Fuel slot — holds one fuel item consumed during smelting. */
    private final SimpleContainer fuelContainer   = new SimpleContainer(1);
    /** Output slot — holds the smelted result ready for pickup. */
    private final SimpleContainer outputContainer = new SimpleContainer(1);

    public SmelterBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.SMELTER_BLOCK_BE.get(), pos, state);
        this.oreContainer   .addListener(c -> this.setChanged());
        this.fuelContainer  .addListener(c -> this.setChanged());
        this.outputContainer.addListener(c -> this.setChanged());
    }

    // ── IWorkplaceBlockEntity ─────────────────────────────────────────────────

    @Override public JobType getRequiredJob() { return JobType.SMELTER; }

    // ── MenuProvider ──────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.smelter_block");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new SmelterBlockMenu(containerId, inventory, this);
    }

    // ── Accessors (used by future Smelter work-goal) ──────────────────────────

    public SimpleContainer getOreContainer()    { return oreContainer;    }
    public SimpleContainer getFuelContainer()   { return fuelContainer;   }
    public SimpleContainer getOutputContainer() { return outputContainer; }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("OreSlot",    saveSlot(oreContainer,    0, registries));
        tag.put("FuelSlot",   saveSlot(fuelContainer,   0, registries));
        tag.put("OutputSlot", saveSlot(outputContainer, 0, registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        loadSlot(oreContainer,    0, tag, "OreSlot",    registries);
        loadSlot(fuelContainer,   0, tag, "FuelSlot",   registries);
        loadSlot(outputContainer, 0, tag, "OutputSlot", registries);
    }

    // ── NBT helpers ───────────────────────────────────────────────────────────

    private static CompoundTag saveSlot(SimpleContainer container, int index,
                                         HolderLookup.Provider registries) {
        ItemStack stack = container.getItem(index);
        if (stack.isEmpty()) return new CompoundTag();
        CompoundTag slotTag = new CompoundTag();
        slotTag.put("Item", stack.save(registries));
        return slotTag;
    }

    private static void loadSlot(SimpleContainer container, int index,
                                  CompoundTag parent, String key,
                                  HolderLookup.Provider registries) {
        container.setItem(index, ItemStack.EMPTY);
        if (parent.contains(key, Tag.TAG_COMPOUND)) {
            CompoundTag slotTag = parent.getCompound(key);
            if (slotTag.contains("Item")) {
                container.setItem(index,
                        ItemStack.parseOptional(registries, slotTag.getCompound("Item")));
            }
        }
    }
}
