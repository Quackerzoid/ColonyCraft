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
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;

import javax.annotation.Nullable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;
import village.automation.mod.menu.MineBlockMenu;

import java.util.UUID;

/**
 * Block entity for the Mine Block workplace.
 *
 * <p>While an assigned Miner worker with a pickaxe is alive, a {@link #MINE_INTERVAL}
 * tick countdown runs. When it expires a randomly-weighted block is deposited into
 * the 9-slot output inventory. Weights (out of 1 000):
 *
 * <pre>
 *   Cobblestone  600  (60.0 %)
 *   Stone        120  (12.0 %)
 *   Coal         100  (10.0 %)
 *   Raw Iron      80   (8.0 %)
 *   Raw Copper    50   (5.0 %)
 *   Raw Gold      25   (2.5 %)
 *   Redstone      12   (1.2 %)
 *   Lapis          8   (0.8 %)
 *   Emerald        3   (0.3 %)
 *   Diamond        2   (0.2 %)
 * </pre>
 */
public class MineBlockEntity extends WorkplaceBlockEntityBase {

    // ── Tuning ────────────────────────────────────────────────────────────────
    /** Default interval used as a fallback (stone pickaxe, 20 s). */
    public static final int MINE_INTERVAL = 400;

    // ── Per-tier intervals (ticks) ────────────────────────────────────────────
    private static final int INTERVAL_WOOD      = 600;   // 30 s
    private static final int INTERVAL_STONE     = 400;   // 20 s
    private static final int INTERVAL_IRON      = 300;   // 15 s
    private static final int INTERVAL_DIAMOND   = 160;   //  8 s
    private static final int INTERVAL_GOLD      = 100;   //  5 s
    private static final int INTERVAL_NETHERITE = 100;   //  5 s

    // ── Output inventory (3×3) ────────────────────────────────────────────────
    private final SimpleContainer outputContainer = new SimpleContainer(9);

    /**
     * Cached {@link IItemHandler} wrapping {@link #outputContainer}.
     * Exposed on the {@link net.minecraft.core.Direction#DOWN DOWN} face so that
     * a hopper placed underneath the Mine Block can pull mined items out automatically.
     */
    private final IItemHandler outputHandler = new InvWrapper(outputContainer);

    // ── Mining progress ───────────────────────────────────────────────────────
    private int mineTimer    = MINE_INTERVAL;
    /** Effective interval for the current tool tier — updated every active tick. */
    private int mineInterval = MINE_INTERVAL;

    // ── ContainerData — synced to the open GUI on the client ─────────────────
    // Index 0 : mineTimer    (current countdown)
    // Index 1 : mineInterval (effective interval for current tool; drives progress bar)
    public final ContainerData data = new ContainerData() {
        @Override public int get(int index) {
            return switch (index) {
                case 0  -> mineTimer;
                case 1  -> mineInterval;
                default -> 0;
            };
        }
        @Override public void set(int index, int value) {
            if (index == 0) mineTimer    = value;
            if (index == 1) mineInterval = value;
        }
        @Override public int getCount() { return 2; }
    };

    public MineBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.MINE_BLOCK_BE.get(), pos, state);
        this.outputContainer.addListener(c -> this.setChanged());
    }

    // ── IWorkplaceBlockEntity ─────────────────────────────────────────────────

    @Override
    public JobType getRequiredJob() { return JobType.MINER; }

    // ── Container ─────────────────────────────────────────────────────────────

    public SimpleContainer getOutputContainer() { return outputContainer; }
    public IItemHandler     getOutputHandler()   { return outputHandler;   }

    // ── MenuProvider ──────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.mine_block");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new MineBlockMenu(containerId, inventory, this);
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    /**
     * Called every server tick by {@link village.automation.mod.block.MineBlock#getTicker}.
     *
     * <ol>
     *   <li>Resolves the active worker (alive, MINER, has pickaxe, {@code isMiningActive}).
     *   <li>Derives the effective interval from the pickaxe tier and clamps the
     *       timer if the player upgraded the tool mid-cycle.
     *   <li>Counts down; on expiry deposits a weighted drop and damages the pickaxe
     *       by 1 durability (respects the Unbreaking enchantment automatically).
     * </ol>
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, MineBlockEntity be) {
        VillagerWorkerEntity worker = be.getActiveWorker(level);
        if (worker == null) return;

        // Sync interval to the worker's current tool tier
        int newInterval = getIntervalForTool(worker.getToolContainer().getItem(0));
        if (newInterval != be.mineInterval) {
            be.mineInterval = newInterval;
            // If the miner upgraded to a faster tool, clamp the timer so the bar
            // never shows >100 % and the next drop doesn't take longer than expected.
            if (be.mineTimer > be.mineInterval) be.mineTimer = be.mineInterval;
        }

        be.mineTimer--;
        if (be.mineTimer <= 0) {
            be.mineTimer = be.mineInterval;
            ItemStack drop = generateDrop(level.getRandom());
            depositItem(drop, be.outputContainer);
            // Damage the pickaxe — hurtAndBreak handles Unbreaking and fires the
            // break event which calls setItemSlot(MAINHAND, EMPTY) if it shatters.
            worker.getToolContainer().getItem(0)
                  .hurtAndBreak(1, worker, EquipmentSlot.MAINHAND);
            be.setChanged();
        }
    }

    // ── Worker validity check ─────────────────────────────────────────────────

    /**
     * Returns the assigned worker if they are alive, hold the MINER job, have a
     * pickaxe equipped, and are actively animating at a floor block.
     * Returns {@code null} (pausing the timer) in every other case.
     */
    @Nullable
    private VillagerWorkerEntity getActiveWorker(Level level) {
        UUID workerUUID = getAssignedWorkerUUID();
        if (workerUUID == null) return null;
        if (!(level instanceof ServerLevel sl)) return null;
        var entity = sl.getEntity(workerUUID);
        if (!(entity instanceof VillagerWorkerEntity worker)) return null;
        if (!worker.isAlive()) return null;
        if (worker.getJob() != JobType.MINER) return null;
        if (!(worker.getToolContainer().getItem(0).getItem() instanceof PickaxeItem)) return null;
        // isMiningActive is set by MinerWorkGoal: true only in MINE phase.
        if (!worker.isMiningActive()) return null;
        return worker;
    }

    // ── Tool-tier → interval ──────────────────────────────────────────────────

    /**
     * Maps the pickaxe tier to the number of ticks between drops.
     *
     * <pre>
     *   Wood      → 30 s (600 ticks)
     *   Stone     → 20 s (400 ticks)
     *   Iron      → 15 s (300 ticks)
     *   Diamond   →  8 s (160 ticks)
     *   Gold      →  5 s (100 ticks)
     *   Netherite →  5 s (100 ticks)
     * </pre>
     */
    private static int getIntervalForTool(ItemStack tool) {
        if (tool.getItem() instanceof TieredItem tiered) {
            var tier = tiered.getTier();
            if (tier == Tiers.WOOD)       return INTERVAL_WOOD;
            if (tier == Tiers.STONE)      return INTERVAL_STONE;
            if (tier == Tiers.IRON)       return INTERVAL_IRON;
            if (tier == Tiers.DIAMOND)    return INTERVAL_DIAMOND;
            if (tier == Tiers.GOLD)       return INTERVAL_GOLD;
            if (tier == Tiers.NETHERITE)  return INTERVAL_NETHERITE;
        }
        return MINE_INTERVAL;   // fallback (stone speed)
    }

    // ── Weighted loot table ───────────────────────────────────────────────────

    private static ItemStack generateDrop(RandomSource random) {
        int roll = random.nextInt(1000);
        if      (roll <  600) return new ItemStack(Items.COBBLESTONE);   // 60.0 %
        else if (roll <  720) return new ItemStack(Items.STONE);         // 12.0 %
        else if (roll <  820) return new ItemStack(Items.COAL);          // 10.0 %
        else if (roll <  900) return new ItemStack(Items.RAW_IRON);      //  8.0 %
        else if (roll <  950) return new ItemStack(Items.RAW_COPPER);    //  5.0 %
        else if (roll <  975) return new ItemStack(Items.RAW_GOLD);      //  2.5 %
        else if (roll <  987) return new ItemStack(Items.REDSTONE);      //  1.2 %
        else if (roll <  995) return new ItemStack(Items.LAPIS_LAZULI);  //  0.8 %
        else if (roll <  998) return new ItemStack(Items.EMERALD);       //  0.3 %
        else                  return new ItemStack(Items.DIAMOND);        //  0.2 %
    }

    /** Inserts {@code stack} into the first available slot of {@code container}. */
    private static void depositItem(ItemStack stack, SimpleContainer container) {
        if (stack.isEmpty()) return;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, stack);
                return;
            }
            if (ItemStack.isSameItemSameComponents(slot, stack)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int move  = Math.min(space, stack.getCount());
                slot.grow(move);
                stack.shrink(move);
                if (stack.isEmpty()) return;
            }
        }
        // Container full — drop is silently discarded (same as vanilla overflow)
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        // Mine timer and effective interval
        tag.putInt("MineTimer",    mineTimer);
        tag.putInt("MineInterval", mineInterval);

        // Output inventory
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

        // Mine timer and effective interval
        mineTimer    = tag.contains("MineTimer")    ? tag.getInt("MineTimer")    : MINE_INTERVAL;
        mineInterval = tag.contains("MineInterval") ? tag.getInt("MineInterval") : MINE_INTERVAL;

        // Output inventory
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
