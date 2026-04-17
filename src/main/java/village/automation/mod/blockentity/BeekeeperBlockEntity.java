package village.automation.mod.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.BeekeeperBlockMenu;

import java.util.*;

/**
 * Block entity for the Beekeeper workplace.
 *
 * <h3>Inventories</h3>
 * <ul>
 *   <li>{@link #fuelInput}   — 9 slots; couriers deliver wood logs here as smoking fuel.</li>
 *   <li>{@link #outputContainer} — 9 slots; honeycomb is deposited here by the smoking process.</li>
 * </ul>
 *
 * <h3>Bee state machine</h3>
 * Up to {@value #MAX_BEES} bees are claimed by UUID.  Each bee has a timer that counts
 * down every tick.  When it reaches zero the bee is considered to have finished a
 * pollination cycle: the nearest unfinished crop within the village-heart radius is
 * advanced one growth stage, one pollen unit is added, and the timer resets.
 *
 * <h3>Smoking</h3>
 * While {@code pollenCount > 0} the smoking timer counts down.  When it reaches zero one
 * honeycomb is produced and one pollen unit is consumed.  Each honeycomb production cycle
 * consumes one wood log from {@link #fuelInput}.
 *
 * <h3>ContainerData (synced to open GUI)</h3>
 * <pre>
 *   Index 0 — pollenCount  (0–{@value #MAX_POLLEN})
 *   Index 1 — smokingTimer (0–{@value #SMOKE_TICKS})
 *   Index 2 — bee count    (0–{@value #MAX_BEES})
 *   Index 3 — fuelBurning  (0 = no fuel, 1 = burning)
 * </pre>
 */
public class BeekeeperBlockEntity extends WorkplaceBlockEntityBase {

    // ── Constants ─────────────────────────────────────────────────────────────
    public static final int MAX_BEES    = 4;
    public static final int MAX_POLLEN  = 32;
    public static final int BEE_CYCLE_TICKS  = 400;  // 20 s per bee pollination cycle
    public static final int SMOKE_TICKS      = 200;  // 10 s per honeycomb production

    // ── Inventories ───────────────────────────────────────────────────────────
    private final SimpleContainer fuelInput       = new SimpleContainer(9);
    private final SimpleContainer outputContainer = new SimpleContainer(9);
    private final IItemHandler    outputHandler   = new InvWrapper(outputContainer);

    // ── Bee tracking ──────────────────────────────────────────────────────────
    private final Set<UUID>         claimedBees = new HashSet<>();
    private final Map<UUID, Integer> beeTimers  = new HashMap<>();

    // ── Smoking state ─────────────────────────────────────────────────────────
    /** Number of pollen deposits waiting to be smoked into honeycomb. */
    private int pollenCount  = 0;
    /** Ticks remaining in the current smoking cycle, or 0 if not smoking. */
    private int smokingTimer = 0;

    // ── ContainerData ─────────────────────────────────────────────────────────
    public final ContainerData data = new ContainerData() {
        @Override public int get(int i) {
            return switch (i) {
                case 0 -> pollenCount;
                case 1 -> smokingTimer;
                case 2 -> claimedBees.size();
                case 3 -> smokingTimer > 0 ? 1 : 0;
                default -> 0;
            };
        }
        @Override public void set(int i, int v) {
            switch (i) {
                case 0 -> pollenCount  = v;
                case 1 -> smokingTimer = v;
            }
        }
        @Override public int getCount() { return 4; }
    };

    // ── Constructor ───────────────────────────────────────────────────────────

    public BeekeeperBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.BEEKEEPER_BE.get(), pos, state);
        fuelInput.addListener(c -> setChanged());
        outputContainer.addListener(c -> setChanged());
    }

    // ── IWorkplaceBlockEntity ─────────────────────────────────────────────────

    @Override
    public JobType getRequiredJob() { return JobType.BEEKEEPER; }

    // ── Tick (server only) ────────────────────────────────────────────────────

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos,
                                  BlockState state, BeekeeperBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        be.tick(serverLevel);
    }

    private void tick(ServerLevel level) {
        tickBees(level);
        tickSmoking();
        setChanged();
    }

    // ── Bee cycle ─────────────────────────────────────────────────────────────

    private void tickBees(ServerLevel level) {
        Iterator<UUID> it = claimedBees.iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            Entity entity = level.getEntity(uuid);
            if (!(entity instanceof Bee bee) || !bee.isAlive()) {
                it.remove();
                beeTimers.remove(uuid);
                continue;
            }

            int timer = beeTimers.getOrDefault(uuid, BEE_CYCLE_TICKS) - 1;
            if (timer <= 0) {
                // Bee completed a pollination cycle
                advanceNearestCrop(level);
                if (pollenCount < MAX_POLLEN) {
                    pollenCount++;
                }
                beeTimers.put(uuid, BEE_CYCLE_TICKS);
            } else {
                beeTimers.put(uuid, timer);
            }
        }
    }

    /**
     * Finds the nearest un-finished crop within 48 blocks of the linked heart (or this block)
     * and advances it one growth stage.
     */
    private void advanceNearestCrop(ServerLevel level) {
        BlockPos centre = getLinkedHeartPos() != null ? getLinkedHeartPos() : this.worldPosition;
        int radius = 48;

        BlockPos bestPos  = null;
        double   bestDist = Double.MAX_VALUE;

        for (BlockPos scan : BlockPos.betweenClosed(
                centre.offset(-radius, -8, -radius),
                centre.offset( radius,  8,  radius))) {
            BlockState state = level.getBlockState(scan);
            if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                double d = scan.distSqr(centre);
                if (d < bestDist) {
                    bestDist = d;
                    bestPos  = scan.immutable();
                }
            }
        }

        if (bestPos != null) {
            BlockState oldState = level.getBlockState(bestPos);
            if (oldState.getBlock() instanceof CropBlock crop) {
                BlockState newState = crop.getStateForAge(
                        Math.min(crop.getAge(oldState) + 1, crop.getMaxAge()));
                level.setBlock(bestPos, newState, 3);
            }
        }
    }

    // ── Smoking cycle ─────────────────────────────────────────────────────────

    private void tickSmoking() {
        if (smokingTimer > 0) {
            smokingTimer--;
            if (smokingTimer == 0) {
                // Produce one honeycomb
                if (outputHasSpace()) {
                    outputContainer.addItem(new ItemStack(Items.HONEYCOMB, 1));
                }
                pollenCount = Math.max(0, pollenCount - 1);
            }
        } else if (pollenCount > 0) {
            // Start next smoking cycle if fuel is available and output has space
            if (outputHasSpace() && consumeFuel()) {
                smokingTimer = SMOKE_TICKS;
            }
        }
    }

    private boolean outputHasSpace() {
        for (int i = 0; i < outputContainer.getContainerSize(); i++) {
            ItemStack slot = outputContainer.getItem(i);
            if (slot.isEmpty()) return true;
            if (slot.is(Items.HONEYCOMB) && slot.getCount() < slot.getMaxStackSize()) return true;
        }
        return false;
    }

    /**
     * Tries to consume one wood log from {@link #fuelInput}.
     *
     * @return {@code true} if a log was successfully consumed
     */
    private boolean consumeFuel() {
        for (int i = 0; i < fuelInput.getContainerSize(); i++) {
            ItemStack stack = fuelInput.getItem(i);
            if (!stack.isEmpty() && isWoodLog(stack)) {
                stack.shrink(1);
                if (stack.isEmpty()) fuelInput.setItem(i, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    /** Returns {@code true} if the stack is any wood log. */
    public static boolean isWoodLog(ItemStack stack) {
        return stack.is(net.minecraft.tags.ItemTags.LOGS);
    }

    /** Returns {@code true} if there is at least one fuel log in {@link #fuelInput}. */
    public boolean hasFuel() {
        for (int i = 0; i < fuelInput.getContainerSize(); i++) {
            if (isWoodLog(fuelInput.getItem(i))) return true;
        }
        return false;
    }

    // ── Bee claiming ──────────────────────────────────────────────────────────

    public boolean canClaimMoreBees()            { return claimedBees.size() < MAX_BEES; }
    public void claimBee(UUID uuid)              { claimedBees.add(uuid); beeTimers.put(uuid, BEE_CYCLE_TICKS); setChanged(); }
    public void unclaimBee(UUID uuid)            { claimedBees.remove(uuid); beeTimers.remove(uuid); setChanged(); }
    public boolean hasClaimed(UUID uuid)         { return claimedBees.contains(uuid); }
    public Set<UUID> getClaimedBees()            { return Collections.unmodifiableSet(claimedBees); }

    // ── Inventory accessors ───────────────────────────────────────────────────

    public SimpleContainer getFuelInput()       { return fuelInput; }
    public SimpleContainer getOutputContainer() { return outputContainer; }
    public IItemHandler    getOutputHandler()   { return outputHandler; }

    // ── State accessors ───────────────────────────────────────────────────────

    public int  getPollenCount()  { return pollenCount; }
    public int  getSmokingTimer() { return smokingTimer; }

    // ── MenuProvider ──────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.beekeeper_block");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new BeekeeperBlockMenu(id, inv, this);
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putInt("PollenCount",  pollenCount);
        tag.putInt("SmokingTimer", smokingTimer);

        // Fuel input
        ListTag fuelList = new ListTag();
        for (int i = 0; i < fuelInput.getContainerSize(); i++) {
            ItemStack stack = fuelInput.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag st = new CompoundTag();
                st.putByte("Slot", (byte) i);
                st.put("Item", stack.save(registries));
                fuelList.add(st);
            }
        }
        tag.put("FuelInput", fuelList);

        // Output
        ListTag outList = new ListTag();
        for (int i = 0; i < outputContainer.getContainerSize(); i++) {
            ItemStack stack = outputContainer.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag st = new CompoundTag();
                st.putByte("Slot", (byte) i);
                st.put("Item", stack.save(registries));
                outList.add(st);
            }
        }
        tag.put("OutputInventory", outList);

        // Claimed bees + timers
        ListTag beeList = new ListTag();
        for (UUID uuid : claimedBees) {
            CompoundTag bt = new CompoundTag();
            bt.putUUID("UUID", uuid);
            bt.putInt("Timer", beeTimers.getOrDefault(uuid, BEE_CYCLE_TICKS));
            beeList.add(bt);
        }
        tag.put("ClaimedBees", beeList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        pollenCount  = tag.contains("PollenCount")  ? tag.getInt("PollenCount")  : 0;
        smokingTimer = tag.contains("SmokingTimer") ? tag.getInt("SmokingTimer") : 0;

        // Fuel input
        fuelInput.clearContent();
        if (tag.contains("FuelInput")) {
            ListTag list = tag.getList("FuelInput", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag st = list.getCompound(i);
                int slot = st.getByte("Slot") & 0xFF;
                if (slot < fuelInput.getContainerSize()) {
                    fuelInput.setItem(slot, ItemStack.parseOptional(registries, st.getCompound("Item")));
                }
            }
        }

        // Output
        outputContainer.clearContent();
        if (tag.contains("OutputInventory")) {
            ListTag list = tag.getList("OutputInventory", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag st = list.getCompound(i);
                int slot = st.getByte("Slot") & 0xFF;
                if (slot < outputContainer.getContainerSize()) {
                    outputContainer.setItem(slot, ItemStack.parseOptional(registries, st.getCompound("Item")));
                }
            }
        }

        // Claimed bees
        claimedBees.clear();
        beeTimers.clear();
        if (tag.contains("ClaimedBees")) {
            ListTag list = tag.getList("ClaimedBees", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag bt = list.getCompound(i);
                if (bt.hasUUID("UUID")) {
                    UUID uuid = bt.getUUID("UUID");
                    claimedBees.add(uuid);
                    beeTimers.put(uuid, bt.contains("Timer") ? bt.getInt("Timer") : BEE_CYCLE_TICKS);
                }
            }
        }
    }
}
