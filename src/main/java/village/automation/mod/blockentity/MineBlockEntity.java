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
import net.minecraft.world.item.Item;
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
 * <p>Mine speed is driven by the worker's <b>level</b> (exponential curve,
 * 60 s at level 1 → 5 s at level 20).  Drop <b>quality</b> is driven by the
 * worker's equipped <b>pickaxe tier</b> — see the LOOT TABLE section below.
 */
public class MineBlockEntity extends WorkplaceBlockEntityBase {

    // ── Tuning ────────────────────────────────────────────────────────────────
    /** Fallback interval used before a worker level is known (approx. mid-range). */
    public static final int MINE_INTERVAL = 400;

    // ═════════════════════════════════════════════════════════════════════════
    // LOOT TABLES  --  Edit weights here to change drop rates per pick tier.
    //
    //   * Weights are relative — they do NOT need to sum to any fixed number.
    //   * Higher weight = more common.  To remove an entry set its weight to 0
    //     or delete the line.  To add a new item, append a new LootEntry row.
    //   * Tiers in order: WOOD < STONE < IRON < GOLD < DIAMOND = NETHERITE
    // ═════════════════════════════════════════════════════════════════════════

    private record LootEntry(Item item, int weight) {}

    // -- WOOD pickaxe --
    private static final LootEntry[] TABLE_WOOD = {
        new LootEntry(Items.COBBLESTONE,  650),  // 65.0 %
        new LootEntry(Items.GRAVEL,       150),  // 15.0 %
        new LootEntry(Items.COAL,         120),  // 12.0 %
        new LootEntry(Items.RAW_COPPER,    80),  //  8.0 %
    };

    // -- STONE pickaxe --
    private static final LootEntry[] TABLE_STONE = {
        new LootEntry(Items.COBBLESTONE,  450),  // 45.0 %
        new LootEntry(Items.STONE,        150),  // 15.0 %
        new LootEntry(Items.GRAVEL,       100),  // 10.0 %
        new LootEntry(Items.COAL,         150),  // 15.0 %
        new LootEntry(Items.RAW_COPPER,    80),  //  8.0 %
        new LootEntry(Items.RAW_IRON,      70),  //  7.0 %
    };

    // -- IRON pickaxe --
    private static final LootEntry[] TABLE_IRON = {
        new LootEntry(Items.COBBLESTONE,  300),  // 30.0 %
        new LootEntry(Items.STONE,        100),  // 10.0 %
        new LootEntry(Items.COAL,         130),  // 13.0 %
        new LootEntry(Items.RAW_COPPER,   100),  // 10.0 %
        new LootEntry(Items.RAW_IRON,     200),  // 20.0 %
        new LootEntry(Items.RAW_GOLD,      80),  //  8.0 %
        new LootEntry(Items.REDSTONE,      60),  //  6.0 %
        new LootEntry(Items.LAPIS_LAZULI,  30),  //  3.0 %
    };

    // -- GOLD pickaxe (fast but rare; rewarded with luck-skewed drops) --
    private static final LootEntry[] TABLE_GOLD = {
        new LootEntry(Items.COBBLESTONE,  200),  // 20.0 %
        new LootEntry(Items.COAL,         100),  // 10.0 %
        new LootEntry(Items.RAW_COPPER,   100),  // 10.0 %
        new LootEntry(Items.RAW_IRON,     180),  // 18.0 %
        new LootEntry(Items.RAW_GOLD,     150),  // 15.0 %
        new LootEntry(Items.REDSTONE,     100),  // 10.0 %
        new LootEntry(Items.LAPIS_LAZULI,  80),  //  8.0 %
        new LootEntry(Items.EMERALD,       60),  //  6.0 %
        new LootEntry(Items.DIAMOND,       30),  //  3.0 %
    };

    // -- DIAMOND pickaxe --
    private static final LootEntry[] TABLE_DIAMOND = {
        new LootEntry(Items.COBBLESTONE,  100),  // 10.0 %
        new LootEntry(Items.DEEPSLATE,    100),  // 10.0 %
        new LootEntry(Items.COAL,          80),  //  8.0 %
        new LootEntry(Items.RAW_COPPER,    80),  //  8.0 %
        new LootEntry(Items.RAW_IRON,     150),  // 15.0 %
        new LootEntry(Items.RAW_GOLD,     100),  // 10.0 %
        new LootEntry(Items.REDSTONE,     100),  // 10.0 %
        new LootEntry(Items.LAPIS_LAZULI, 100),  // 10.0 %
        new LootEntry(Items.EMERALD,      120),  // 12.0 %
        new LootEntry(Items.DIAMOND,      120),  // 12.0 %
        new LootEntry(Items.ANCIENT_DEBRIS, 50), //  5.0 %
    };

    // -- NETHERITE pickaxe (same as diamond for now) --
    private static final LootEntry[] TABLE_NETHERITE = TABLE_DIAMOND;

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

        // Interval driven by worker level; tier drives loot quality
        int newInterval = worker.getMinerMineInterval();
        if (newInterval != be.mineInterval) {
            be.mineInterval = newInterval;
            if (be.mineTimer > be.mineInterval) be.mineTimer = be.mineInterval;
        }

        be.mineTimer--;
        if (be.mineTimer <= 0) {
            be.mineTimer = be.mineInterval;
            Tiers tier = getPickaxeTier(worker.getToolContainer().getItem(0));
            ItemStack drop = generateDrop(level.getRandom(), tier);
            depositItem(drop, be.outputContainer);
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

    // ── Pickaxe tier helper ───────────────────────────────────────────────────

    private static Tiers getPickaxeTier(ItemStack tool) {
        if (tool.getItem() instanceof TieredItem tiered) {
            var tier = tiered.getTier();
            if (tier == Tiers.WOOD)      return Tiers.WOOD;
            if (tier == Tiers.STONE)     return Tiers.STONE;
            if (tier == Tiers.IRON)      return Tiers.IRON;
            if (tier == Tiers.GOLD)      return Tiers.GOLD;
            if (tier == Tiers.DIAMOND)   return Tiers.DIAMOND;
            if (tier == Tiers.NETHERITE) return Tiers.NETHERITE;
        }
        return Tiers.STONE;
    }

    // ── Weighted loot table ───────────────────────────────────────────────────

    private static ItemStack generateDrop(RandomSource random, Tiers tier) {
        LootEntry[] table = switch (tier) {
            case WOOD      -> TABLE_WOOD;
            case STONE     -> TABLE_STONE;
            case IRON      -> TABLE_IRON;
            case GOLD      -> TABLE_GOLD;
            case DIAMOND   -> TABLE_DIAMOND;
            case NETHERITE -> TABLE_NETHERITE;
            default        -> TABLE_STONE;
        };
        return roll(random, table);
    }

    private static ItemStack roll(RandomSource random, LootEntry[] table) {
        int total = 0;
        for (LootEntry e : table) total += e.weight();
        int r = random.nextInt(total);
        for (LootEntry e : table) {
            r -= e.weight();
            if (r < 0) return new ItemStack(e.item());
        }
        return new ItemStack(table[table.length - 1].item());
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
