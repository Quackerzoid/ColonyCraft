package village.automation.mod.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;
import village.automation.mod.menu.FishingBlockMenu;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Block entity for the Fishing Block workplace.
 *
 * <p>While an assigned Fisherman worker with a fishing rod is actively fishing
 * ({@link VillagerWorkerEntity#isFishingActive()} returns {@code true}), a
 * {@value #FISH_INTERVAL}-tick countdown runs.  When it expires
 * {@link #fishComplete} is set to {@code true}, a loot roll is made directly
 * into the 9-slot output container, and the rod loses one durability point.
 * {@link village.automation.mod.entity.goal.FishermanWorkGoal} polls
 * {@link #isFishComplete()} each tick; on seeing it flip it plays the retrieve
 * sound and clears the flag so the next cast can begin.
 */
public class FishingBlockEntity extends WorkplaceBlockEntityBase {

    // ── Tuning ────────────────────────────────────────────────────────────────
    /** Fixed fishing duration — 20 s regardless of rod enchantments. */
    public static final int FISH_INTERVAL = 400;

    // ── Output inventory (3×3) ────────────────────────────────────────────────
    private final SimpleContainer outputContainer = new SimpleContainer(9);

    /**
     * Cached {@link IItemHandler} wrapping {@link #outputContainer}.
     * Exposed on the {@link net.minecraft.core.Direction#DOWN DOWN} face so a
     * hopper below the Fishing Block can pull output items automatically.
     */
    private final IItemHandler outputHandler = new InvWrapper(outputContainer);

    // ── Fish progress ─────────────────────────────────────────────────────────
    private int     fishTimer    = FISH_INTERVAL;
    private int     fishInterval = FISH_INTERVAL;
    /** Latched by {@link #serverTick} when the countdown reaches zero. */
    private boolean fishComplete = false;

    // ── ContainerData — synced to the open GUI on the client ─────────────────
    // Index 0 : fishTimer    (current countdown)
    // Index 1 : fishInterval (constant; drives progress bar width)
    public final ContainerData data = new ContainerData() {
        @Override public int get(int index) {
            return switch (index) {
                case 0  -> fishTimer;
                case 1  -> fishInterval;
                default -> 0;
            };
        }
        @Override public void set(int index, int value) {
            if (index == 0) fishTimer    = value;
            if (index == 1) fishInterval = value;
        }
        @Override public int getCount() { return 2; }
    };

    public FishingBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.FISHING_BLOCK_BE.get(), pos, state);
        this.outputContainer.addListener(c -> this.setChanged());
    }

    // ── IWorkplaceBlockEntity ─────────────────────────────────────────────────

    @Override
    public JobType getRequiredJob() { return JobType.FISHERMAN; }

    // ── Container / capability ────────────────────────────────────────────────

    public SimpleContainer getOutputContainer() { return outputContainer; }
    public IItemHandler     getOutputHandler()  { return outputHandler;   }

    // ── Fish-completion signal ────────────────────────────────────────────────

    /** Returns {@code true} once after each completed 20 s fishing cycle. */
    public boolean isFishComplete()  { return fishComplete; }
    /** Called by the goal after it has acted on the signal. */
    public void    clearFishComplete() { fishComplete = false; setChanged(); }

    // ── MenuProvider ──────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.fishing_block");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new FishingBlockMenu(containerId, inventory, this);
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    /**
     * Called every server tick by
     * {@link village.automation.mod.block.FishingBlock#getTicker}.
     *
     * <p>Ticks only when:
     * <ul>
     *   <li>an assigned Fisherman worker is alive,
     *   <li>they have a fishing rod equipped, and
     *   <li>{@link VillagerWorkerEntity#isFishingActive()} is {@code true}
     *       (set by {@link village.automation.mod.entity.goal.FishermanWorkGoal}
     *       during the FISH phase).
     * </ul>
     *
     * <p>When the 400-tick timer expires:
     * <ol>
     *   <li>The timer resets.</li>
     *   <li>A fishing loot roll is added to the output container.</li>
     *   <li>{@link #fishComplete} is latched.</li>
     *   <li>The worker's rod loses 1 durability.</li>
     * </ol>
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  FishingBlockEntity be) {
        VillagerWorkerEntity worker = be.getActiveWorker(level);
        if (worker == null) return;

        be.fishTimer--;
        if (be.fishTimer <= 0) {
            be.fishTimer    = be.fishInterval;
            be.fishComplete = true;
            be.rollFishingLoot(level.getRandom());
            worker.getToolContainer().getItem(0)
                  .hurtAndBreak(1, worker, EquipmentSlot.MAINHAND);
            be.setChanged();
        }
    }

    // ── Loot generation ───────────────────────────────────────────────────────

    /**
     * Rolls the fishing loot table and inserts one item into the output
     * container.  If the output is completely full the catch is silently
     * discarded (the fisherman will continue; the courier should collect sooner).
     */
    private void rollFishingLoot(RandomSource random) {
        ItemStack loot = generateFishLoot(random);
        if (loot.isEmpty()) return;

        // Merge with an existing stack first, then fill an empty slot
        for (int i = 0; i < outputContainer.getContainerSize(); i++) {
            ItemStack slot = outputContainer.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, loot)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                if (space > 0) {
                    slot.grow(Math.min(space, loot.getCount()));
                    return;
                }
            }
        }
        for (int i = 0; i < outputContainer.getContainerSize(); i++) {
            if (outputContainer.getItem(i).isEmpty()) {
                outputContainer.setItem(i, loot);
                return;
            }
        }
        // Output full — discard silently
    }

    /**
     * Mirrors vanilla fishing probabilities (approximate):
     * <ul>
     *   <li>85 % fish pool  — Raw Cod 60 %, Salmon 25 %, Pufferfish 13 %,
     *       Tropical Fish 2 %</li>
     *   <li>10 % junk pool  — lily pad, leather, rotten flesh, string, etc.</li>
     *   <li>5 %  treasure   — name tag, saddle, nautilus shell, fishing rod</li>
     * </ul>
     */
    private static ItemStack generateFishLoot(RandomSource random) {
        int roll = random.nextInt(1000);

        if (roll < 510) {
            return new ItemStack(Items.COD);
        } else if (roll < 723) {
            return new ItemStack(Items.SALMON);
        } else if (roll < 834) {
            return new ItemStack(Items.PUFFERFISH);
        } else if (roll < 850) {
            return new ItemStack(Items.TROPICAL_FISH);
        } else if (roll < 940) {
            return generateJunkLoot(random);
        } else if (roll < 980) {
            return generateTreasureLoot(random);
        } else {
            return new ItemStack(Items.COD);
        }
    }

    private static ItemStack generateJunkLoot(RandomSource random) {
        return switch (random.nextInt(9)) {
            case 0  -> new ItemStack(Items.LILY_PAD);
            case 1  -> new ItemStack(Items.LEATHER);
            case 2  -> new ItemStack(Items.ROTTEN_FLESH);
            case 3  -> new ItemStack(Items.STRING);
            case 4  -> new ItemStack(Items.STICK);
            case 5  -> new ItemStack(Items.BONE);
            case 6  -> new ItemStack(Items.INK_SAC);
            case 7  -> new ItemStack(Items.TRIPWIRE_HOOK);
            default -> new ItemStack(Items.BOWL);
        };
    }

    private static ItemStack generateTreasureLoot(RandomSource random) {
        return switch (random.nextInt(4)) {
            case 0  -> new ItemStack(Items.NAME_TAG);
            case 1  -> new ItemStack(Items.SADDLE);
            case 2  -> new ItemStack(Items.NAUTILUS_SHELL);
            default -> new ItemStack(Items.FISHING_ROD);
        };
    }

    // ── Worker validity check ─────────────────────────────────────────────────

    @Nullable
    private VillagerWorkerEntity getActiveWorker(Level level) {
        UUID workerUUID = getAssignedWorkerUUID();
        if (workerUUID == null) return null;
        if (!(level instanceof ServerLevel sl)) return null;
        var entity = sl.getEntity(workerUUID);
        if (!(entity instanceof VillagerWorkerEntity worker)) return null;
        if (!worker.isAlive()) return null;
        if (worker.getJob() != JobType.FISHERMAN) return null;
        if (!worker.getToolContainer().getItem(0).is(Items.FISHING_ROD)) return null;
        if (!worker.isFishingActive()) return null;
        return worker;
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putInt("FishTimer",    fishTimer);
        tag.putInt("FishInterval", fishInterval);

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

        fishTimer    = tag.contains("FishTimer")    ? tag.getInt("FishTimer")    : FISH_INTERVAL;
        fishInterval = tag.contains("FishInterval") ? tag.getInt("FishInterval") : FISH_INTERVAL;

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
