package village.automation.mod.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.AnimalType;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.AnimalPenBlockMenu;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Block entity for the Animal Pen workplace.
 *
 * <p>Inventories:
 * <ul>
 *   <li>{@link #breedingFoodInput} — 9-slot container; couriers deliver breeding food here.
 *   <li>{@link #outputContainer} — 9-slot container; sheared wool etc. are deposited here;
 *       a hopper below can pull from the DOWN face via {@link #outputHandler}.
 * </ul>
 *
 * <p>ContainerData index 0 is the {@link AnimalType} ordinal for GUI sync.
 */
public class AnimalPenBlockEntity extends WorkplaceBlockEntityBase {

    // ── Inventories ───────────────────────────────────────────────────────────
    private final SimpleContainer breedingFoodInput = new SimpleContainer(9);
    private final SimpleContainer outputContainer   = new SimpleContainer(9);
    private final IItemHandler    outputHandler     = new InvWrapper(outputContainer);

    // ── State ─────────────────────────────────────────────────────────────────
    private AnimalType targetAnimalType  = AnimalType.SHEEP;
    private boolean    needsBreedingFood = false;
    private final Set<UUID> claimedAnimals = new HashSet<>();

    // ── ContainerData (synced to open GUI) ────────────────────────────────────
    // Index 0 = animalType ordinal
    public final ContainerData data = new ContainerData() {
        @Override public int get(int index)            { return index == 0 ? targetAnimalType.ordinal() : 0; }
        @Override public void set(int index, int value) { if (index == 0) targetAnimalType = AnimalType.values()[value % AnimalType.values().length]; }
        @Override public int getCount()                { return 1; }
    };

    public AnimalPenBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.ANIMAL_PEN_BE.get(), pos, state);
        breedingFoodInput.addListener(c -> setChanged());
        outputContainer.addListener(c -> setChanged());
    }

    // ── IWorkplaceBlockEntity ─────────────────────────────────────────────────

    @Override
    public JobType getRequiredJob() { return JobType.SHEPHERD; }

    // ── Inventory accessors ───────────────────────────────────────────────────

    public SimpleContainer getBreedingFoodInput() { return breedingFoodInput; }
    public SimpleContainer getOutputContainer()   { return outputContainer; }
    public IItemHandler    getOutputHandler()     { return outputHandler; }

    // ── State accessors ───────────────────────────────────────────────────────

    public AnimalType getTargetAnimalType() { return targetAnimalType; }

    public void setTargetAnimalType(AnimalType type) {
        this.targetAnimalType = type;
        setChanged();
    }

    public boolean isNeedsBreedingFood() { return needsBreedingFood; }

    public void setNeedsBreedingFood(boolean needs) {
        this.needsBreedingFood = needs;
        setChanged();
    }

    // ── Claimed-animal management ─────────────────────────────────────────────

    public void addClaimedAnimal(UUID uuid)          { claimedAnimals.add(uuid);    setChanged(); }
    public void removeClaimedAnimal(UUID uuid)       { claimedAnimals.remove(uuid); setChanged(); }
    public boolean isClaimedAnimal(UUID uuid)        { return claimedAnimals.contains(uuid); }
    public Set<UUID> getClaimedAnimals()             { return Collections.unmodifiableSet(claimedAnimals); }

    // ── MenuProvider ──────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.animal_pen");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new AnimalPenBlockMenu(containerId, inventory, this);
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putInt("TargetAnimalType", targetAnimalType.ordinal());
        tag.putBoolean("NeedsBreedingFood", needsBreedingFood);

        // Breeding food input
        ListTag foodList = new ListTag();
        for (int i = 0; i < breedingFoodInput.getContainerSize(); i++) {
            ItemStack stack = breedingFoodInput.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                slotTag.put("Item", stack.save(registries));
                foodList.add(slotTag);
            }
        }
        tag.put("BreedingFoodInput", foodList);

        // Output container
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

        // Claimed animals
        ListTag animalList = new ListTag();
        for (UUID uuid : claimedAnimals) {
            CompoundTag uuidTag = new CompoundTag();
            uuidTag.putUUID("UUID", uuid);
            animalList.add(uuidTag);
        }
        tag.put("ClaimedAnimals", animalList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        if (tag.contains("TargetAnimalType")) {
            int ordinal = tag.getInt("TargetAnimalType");
            AnimalType[] values = AnimalType.values();
            targetAnimalType = values[Math.min(ordinal, values.length - 1)];
        }
        needsBreedingFood = tag.contains("NeedsBreedingFood") && tag.getBoolean("NeedsBreedingFood");

        // Breeding food input
        breedingFoodInput.clearContent();
        if (tag.contains("BreedingFoodInput")) {
            ListTag foodList = tag.getList("BreedingFoodInput", Tag.TAG_COMPOUND);
            for (int i = 0; i < foodList.size(); i++) {
                CompoundTag slotTag = foodList.getCompound(i);
                int slot = slotTag.getByte("Slot") & 0xFF;
                if (slot < breedingFoodInput.getContainerSize()) {
                    breedingFoodInput.setItem(slot,
                            ItemStack.parseOptional(registries, slotTag.getCompound("Item")));
                }
            }
        }

        // Output container
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

        // Claimed animals
        claimedAnimals.clear();
        if (tag.contains("ClaimedAnimals")) {
            ListTag animalList = tag.getList("ClaimedAnimals", Tag.TAG_COMPOUND);
            for (int i = 0; i < animalList.size(); i++) {
                CompoundTag uuidTag = animalList.getCompound(i);
                if (uuidTag.hasUUID("UUID")) {
                    claimedAnimals.add(uuidTag.getUUID("UUID"));
                }
            }
        }
    }
}
