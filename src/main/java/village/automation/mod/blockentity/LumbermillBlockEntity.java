package village.automation.mod.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;
import village.automation.mod.menu.LumbermillBlockMenu;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Block entity for the Lumbermill workplace.
 *
 * <p>While an assigned Lumberjack worker with an axe is actively chopping a
 * tree ({@link VillagerWorkerEntity#isChoppingActive()} returns {@code true}),
 * a {@value #CHOP_INTERVAL}-tick countdown runs.  When it expires
 * {@link #chopCompleted} is set to {@code true} and the axe is damaged by one
 * point.  {@link village.automation.mod.entity.goal.LumberjackWorkGoal} polls
 * {@link #isChopCompleted()} each tick; on seeing it flip it breaks the whole
 * tree, collects loot into the worker's inventory, and transitions to the
 * return-and-deposit phase.
 *
 * <p>The 9-slot output inventory holds logs, sticks, saplings, and apples
 * deposited by the lumberjack after returning from the tree.
 */
public class LumbermillBlockEntity extends WorkplaceBlockEntityBase {

    // ── Tuning ────────────────────────────────────────────────────────────────
    /** Default chop interval (level 1 = 60 s). Overridden each tick by worker level. */
    public static final int CHOP_INTERVAL = 1200;

    // ── Output inventory (3×3) ────────────────────────────────────────────────
    private final SimpleContainer outputContainer = new SimpleContainer(9);

    /**
     * Cached {@link IItemHandler} wrapping {@link #outputContainer}.
     * Exposed on the {@link net.minecraft.core.Direction#DOWN DOWN} face so a
     * hopper below the Lumbermill can pull output items automatically.
     */
    private final IItemHandler outputHandler = new InvWrapper(outputContainer);

    // ── Chop progress ─────────────────────────────────────────────────────────
    private int     chopTimer     = CHOP_INTERVAL;
    private int     chopInterval  = CHOP_INTERVAL;
    /** Latched by {@link #serverTick} when the countdown reaches zero. */
    private boolean chopCompleted = false;

    // ── ContainerData — synced to the open GUI on the client ─────────────────
    // Index 0 : chopTimer    (current countdown)
    // Index 1 : chopInterval (constant; drives progress bar width)
    public final ContainerData data = new ContainerData() {
        @Override public int get(int index) {
            return switch (index) {
                case 0  -> chopTimer;
                case 1  -> chopInterval;
                default -> 0;
            };
        }
        @Override public void set(int index, int value) {
            if (index == 0) chopTimer    = value;
            if (index == 1) chopInterval = value;
        }
        @Override public int getCount() { return 2; }
    };

    public LumbermillBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.LUMBERMILL_BE.get(), pos, state);
        this.outputContainer.addListener(c -> this.setChanged());
    }

    // ── IWorkplaceBlockEntity ─────────────────────────────────────────────────

    @Override
    public JobType getRequiredJob() { return JobType.LUMBERJACK; }

    // ── Container / capability ────────────────────────────────────────────────

    public SimpleContainer getOutputContainer() { return outputContainer; }
    public IItemHandler     getOutputHandler()  { return outputHandler;   }

    // ── Chop-completion signal ────────────────────────────────────────────────

    /** Returns {@code true} once after each completed 30 s chop cycle. */
    public boolean isChopCompleted()  { return chopCompleted; }
    /** Called by the goal after it has acted on the signal. */
    public void    clearChopCompleted() { chopCompleted = false; setChanged(); }

    // ── MenuProvider ──────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.lumbermill");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new LumbermillBlockMenu(containerId, inventory, this);
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    /**
     * Called every server tick by
     * {@link village.automation.mod.block.LumbermillBlock#getTicker}.
     *
     * <p>Ticks only when:
     * <ul>
     *   <li>an assigned Lumberjack worker is alive,
     *   <li>they have an axe equipped, and
     *   <li>{@link VillagerWorkerEntity#isChoppingActive()} is {@code true}
     *       (set by {@link village.automation.mod.entity.goal.LumberjackWorkGoal}
     *       during the CHOP phase).
     * </ul>
     *
     * <p>When the 600-tick timer expires:
     * <ol>
     *   <li>The timer resets.
     *   <li>{@link #chopCompleted} is latched.
     *   <li>The worker's axe loses 1 durability.
     * </ol>
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  LumbermillBlockEntity be) {
        VillagerWorkerEntity worker = be.getActiveWorker(level);
        if (worker == null) return;

        int newInterval = worker.getLumberjackChopTicks();
        if (newInterval != be.chopInterval) {
            be.chopInterval = newInterval;
            if (be.chopTimer > be.chopInterval) be.chopTimer = be.chopInterval;
        }

        be.chopTimer--;
        if (be.chopTimer <= 0) {
            be.chopTimer    = be.chopInterval;
            be.chopCompleted = true;
            worker.getToolContainer().getItem(0)
                  .hurtAndBreak(1, worker, EquipmentSlot.MAINHAND);
            be.setChanged();
        }
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
        if (worker.getJob() != JobType.LUMBERJACK) return null;
        if (!(worker.getToolContainer().getItem(0).getItem() instanceof AxeItem)) return null;
        if (!worker.isChoppingActive()) return null;
        return worker;
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putInt("ChopTimer",    chopTimer);
        tag.putInt("ChopInterval", chopInterval);

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

        chopTimer    = tag.contains("ChopTimer")    ? tag.getInt("ChopTimer")    : CHOP_INTERVAL;
        chopInterval = tag.contains("ChopInterval") ? tag.getInt("ChopInterval") : CHOP_INTERVAL;

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
