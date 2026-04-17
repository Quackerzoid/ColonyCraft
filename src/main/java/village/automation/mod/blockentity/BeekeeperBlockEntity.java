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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.entity.VillageBeeEntity;
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
    /** UUIDs of the {@link VillageBeeEntity} instances owned by this block. */
    private final Set<UUID> claimedBees = new HashSet<>();

    // ── Smoking state ─────────────────────────────────────────────────────────
    /** Number of pollen deposits waiting to be smoked into honeycomb. */
    private int pollenCount  = 0;
    /** Ticks remaining in the current smoking cycle, or 0 if not smoking. */
    private int smokingTimer = 0;
    /**
     * Countdown set to 10 by {@link BeekeeperWorkGoal} each tick the beekeeper
     * worker is standing at the block; decremented once per server tick.
     * Positive value means "worker was here very recently."  Using a countdown
     * instead of a single-tick boolean makes the state robust against the
     * exact ordering of entity-AI vs. block-entity ticks.
     * Not persisted — the keeper re-establishes presence on the next tick.
     */
    private int workerRecentTicks = 0;

    // ── ContainerData ─────────────────────────────────────────────────────────
    // Index 0 — pollenCount
    // Index 1 — smokingTimer
    // Index 2 — bee count
    // Index 3 — actively smoking: workerRecentTicks > 0 AND smokingTimer > 0  (0/1)
    // Index 4 — hasFuel: at least one log in fuelInput  (0/1)
    //
    // Uses a plain int[] so that set(i,v) actually stores the value on the CLIENT.
    // The previous lambda form only synced indices 0-1 — indices 2-4 were never
    // written on the client side, causing bee count, burning-state and fuel-flag
    // to always read as 0/false in the GUI.
    private final int[] syncData = new int[5];

    public final ContainerData data = new ContainerData() {
        @Override public int get(int i)         { return (i >= 0 && i < 5) ? syncData[i] : 0; }
        @Override public void set(int i, int v) { if (i >= 0 && i < 5) syncData[i] = v; }
        @Override public int getCount()         { return 5; }
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
        // Spawn smoking particles while the keeper is actively working
        if (workerRecentTicks > 0 && smokingTimer > 0 && level.getGameTime() % 8 == 0) {
            spawnSmokingParticles(level);
        }
        // Countdown — entity AI refreshes this to 10 each tick it calls markWorkerPresent()
        if (workerRecentTicks > 0) workerRecentTicks--;
        // Push live state into the syncData array so every index is properly
        // synced to the client (set() stores into this array on the client side)
        syncData[0] = pollenCount;
        syncData[1] = smokingTimer;
        syncData[2] = claimedBees.size();
        syncData[3] = (workerRecentTicks > 0 && smokingTimer > 0) ? 1 : 0;
        syncData[4] = hasFuel() ? 1 : 0;
        setChanged();
    }

    /** Puff of smoke + dripping honey above the beehive while it is being smoked. */
    private void spawnSmokingParticles(ServerLevel level) {
        BlockPos pos = getBlockPos();
        double cx  = pos.getX() + 0.5;
        double top = pos.getY() + 1.05;
        double cz  = pos.getZ() + 0.5;
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE,
                cx, top, cz, 1, 0.15, 0.05, 0.15, 0.005);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.DRIPPING_HONEY,
                cx, top, cz, 1, 0.2, 0.0, 0.2, 0.0);
    }

    // ── Bee cycle ─────────────────────────────────────────────────────────────

    /**
     * Prunes claimed-bee UUIDs whose {@link VillageBeeEntity} is loaded and dead
     * (or the wrong type).  Null-returns are skipped so bees in unloaded chunks
     * are not incorrectly pruned after a world reload.
     *
     * <p>Crop advancement and pollen deposit are driven entirely by
     * {@link village.automation.mod.entity.goal.VillageBeeGoal}; this method only
     * performs housekeeping.
     */
    private void tickBees(ServerLevel level) {
        // Rate-limit to once per second to avoid per-tick overhead
        if (level.getGameTime() % 20 != 0) return;

        claimedBees.removeIf(uuid -> {
            Entity entity = level.getEntity(uuid);
            // Entity not loaded → keep the UUID (don't prune on unloaded chunk)
            if (entity == null) return false;
            return !(entity instanceof VillageBeeEntity) || !entity.isAlive();
        });
    }

    /**
     * Called by {@link VillageBeeEntity#depositPollen} when a village bee returns
     * home after a successful pollination run.
     */
    public void depositPollen(UUID beeUUID) {
        if (!claimedBees.contains(beeUUID)) return;
        if (pollenCount < MAX_POLLEN) {
            pollenCount++;
        }
        setChanged();
    }

    // ── Smoking cycle ─────────────────────────────────────────────────────────

    /**
     * Called by {@link village.automation.mod.entity.goal.BeekeeperWorkGoal} each
     * tick that the beekeeper worker is standing at this block.  Smoking will not
     * progress until this is called.
     */
    public void markWorkerPresent() {
        workerRecentTicks = 10;
    }

    /** Whether the worker was at the block within the last ~10 ticks (read by the GUI). */
    public boolean isWorkerPresent() { return workerRecentTicks > 0; }

    private void tickSmoking() {
        // Smoking only progresses while the beekeeper is (or was very recently) working here
        if (workerRecentTicks <= 0) return;

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

    public boolean canClaimMoreBees()    { return claimedBees.size() < MAX_BEES; }
    public void claimBee(UUID uuid)      { claimedBees.add(uuid); setChanged(); }
    public void unclaimBee(UUID uuid)    { claimedBees.remove(uuid); setChanged(); }
    public boolean hasClaimed(UUID uuid) { return claimedBees.contains(uuid); }
    public Set<UUID> getClaimedBees()    { return Collections.unmodifiableSet(claimedBees); }

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

        // Claimed village bee UUIDs
        ListTag beeList = new ListTag();
        for (UUID uuid : claimedBees) {
            CompoundTag bt = new CompoundTag();
            bt.putUUID("UUID", uuid);
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

        // Claimed village bee UUIDs
        claimedBees.clear();
        if (tag.contains("ClaimedBees")) {
            ListTag list = tag.getList("ClaimedBees", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag bt = list.getCompound(i);
                if (bt.hasUUID("UUID")) {
                    claimedBees.add(bt.getUUID("UUID"));
                }
            }
        }
    }
}
