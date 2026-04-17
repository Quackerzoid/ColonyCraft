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
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.CookingBlockMenu;

public class CookingBlockEntity extends WorkplaceBlockEntityBase {

    /** Wheat (and future ingredients) placed here by the courier for the chef to cook. */
    private final SimpleContainer inputContainer  = new SimpleContainer(9);
    /** Cooked food produced by the chef, collected by the courier for chest deposit. */
    private final SimpleContainer outputContainer = new SimpleContainer(9);
    /** Set by the chef when the input is empty so the golem treats it as a delivery request. */
    private boolean needsIngredients = false;

    // ── GUI sync ──────────────────────────────────────────────────────────────
    // Index 0 = cookTimer (200 when a batch starts, counts down to 0, 0 = idle).
    // Pushed every tick by ChefWorkGoal; read by CookingBlockMenu / screen.
    private final int[] syncData = new int[1];
    public final ContainerData data = new ContainerData() {
        @Override public int get(int i)         { return (i == 0) ? syncData[0] : 0; }
        @Override public void set(int i, int v) { if (i == 0) syncData[0] = v; }
        @Override public int getCount()         { return 1; }
    };

    /** Called by {@link village.automation.mod.entity.goal.ChefWorkGoal} each tick. */
    public void setCookProgress(int timer) { syncData[0] = timer; }

    public CookingBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.COOKING_BLOCK_BE.get(), pos, state);
        this.inputContainer .addListener(c -> this.setChanged());
        this.outputContainer.addListener(c -> this.setChanged());
    }

    @Override public JobType getRequiredJob() { return JobType.CHEF; }

    public SimpleContainer getInputContainer()  { return inputContainer;  }
    public SimpleContainer getOutputContainer() { return outputContainer; }

    public boolean isNeedsIngredients() { return needsIngredients; }
    public void setNeedsIngredients(boolean b) { this.needsIngredients = b; setChanged(); }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.cooking_block");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new CookingBlockMenu(containerId, inventory, this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        ListTag inputList = new ListTag();
        for (int i = 0; i < inputContainer.getContainerSize(); i++) {
            ItemStack stack = inputContainer.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                slotTag.put("Item", stack.save(registries));
                inputList.add(slotTag);
            }
        }
        tag.put("InputInventory", inputList);

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
        tag.putBoolean("NeedsIngredients", needsIngredients);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        inputContainer.clearContent();
        if (tag.contains("InputInventory")) {
            ListTag inputList = tag.getList("InputInventory", Tag.TAG_COMPOUND);
            for (int i = 0; i < inputList.size(); i++) {
                CompoundTag slotTag = inputList.getCompound(i);
                int slot = slotTag.getByte("Slot") & 0xFF;
                if (slot < inputContainer.getContainerSize()) {
                    inputContainer.setItem(slot, ItemStack.parseOptional(registries, slotTag.getCompound("Item")));
                }
            }
        }

        needsIngredients = tag.getBoolean("NeedsIngredients");

        outputContainer.clearContent();
        if (tag.contains("OutputInventory")) {
            ListTag outputList = tag.getList("OutputInventory", Tag.TAG_COMPOUND);
            for (int i = 0; i < outputList.size(); i++) {
                CompoundTag slotTag = outputList.getCompound(i);
                int slot = slotTag.getByte("Slot") & 0xFF;
                if (slot < outputContainer.getContainerSize()) {
                    outputContainer.setItem(slot, ItemStack.parseOptional(registries, slotTag.getCompound("Item")));
                }
            }
        }
    }
}
