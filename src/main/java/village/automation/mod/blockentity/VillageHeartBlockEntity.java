package village.automation.mod.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;
import village.automation.mod.menu.VillageHeartMenu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class VillageHeartBlockEntity extends BlockEntity implements MenuProvider {

    public static final int MAX_WHEAT         = 16;
    public static final int MAX_NAME_LENGTH   = 32;
    public static final int BASE_WORKER_CAP   = 4;
    public static final int TIER1_WORKER_CAP  = 8;
    public static final int TIER2_WORKER_CAP  = 16;
    public static final int TIER3_WORKER_CAP  = 32;

    // ── Territory radii ───────────────────────────────────────────────────────
    public static final int BASE_RADIUS  = 32;
    public static final int TIER1_RADIUS = 48;
    public static final int TIER2_RADIUS = 64;
    public static final int TIER3_RADIUS = 96;

    // Village name — empty string means "not yet named"
    private String villageName = "";

    // Slot: wheat input (1 slot)
    private final SimpleContainer inputContainer   = new SimpleContainer(1);
    // Slots: village upgrades — index 0 = Tier I, 1 = Tier II, 2 = Tier III
    // Items placed here are permanent (non-removeable) and stack their benefits.
    private final SimpleContainer upgradeContainer = new SimpleContainer(3);

    private int storedWheat    = 0;
    private int consumeCooldown = 0;

    // UUIDs of workers spawned by this heart (server-side only)
    private final Set<UUID> workerUUIDs = new HashSet<>();

    // Positions of all profession workplace blocks linked to this heart (server-side only)
    private final Set<BlockPos> linkedWorkplaces = new HashSet<>();

    // Cooldown between job-assignment checks (runs every 20 ticks / 1 second)
    private int jobCheckCooldown = 0;

    // Computed server-side each tick; synced to client via ContainerData
    private int syncedWorkerCount = 0;
    private int syncedWorkerCap   = BASE_WORKER_CAP;

    // ContainerData: index 0 = storedWheat, 1 = workerCount, 2 = workerCap, 3 = radius
    public final ContainerData data = new ContainerData() {
        @Override public int get(int i) {
            return switch (i) {
                case 0 -> storedWheat;
                case 1 -> syncedWorkerCount;
                case 2 -> syncedWorkerCap;
                case 3 -> getRadius();
                default -> 0;
            };
        }
        @Override public void set(int i, int v) {
            switch (i) {
                case 0 -> storedWheat      = v;
                case 1 -> syncedWorkerCount = v;
                case 2 -> syncedWorkerCap   = v;
                // index 3 (radius) is derived from the upgrade slot — read-only
            }
        }
        @Override public int getCount() { return 4; }
    };

    public VillageHeartBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.VILLAGE_HEART_BE.get(), pos, state);
        this.inputContainer.addListener(c -> this.setChanged());
        this.upgradeContainer.addListener(c -> this.setChanged());
    }

    // ── Ticker ───────────────────────────────────────────────────────────────

    public static void serverTick(Level level, BlockPos pos, BlockState state, VillageHeartBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        // ── Worker count + cap sync ──────────────────────────────────────────
        int liveCount = be.countLiveWorkers(serverLevel);
        int cap       = be.getWorkerCap();
        be.syncedWorkerCount = liveCount;
        be.syncedWorkerCap   = cap;

        // ── Confine all live workers to the village territory ────────────────
        be.applyTerritoryRestriction(serverLevel);

        // ── Wheat consumption (every 4 ticks while bar is not full) ──────────
        if (be.consumeCooldown > 0) {
            be.consumeCooldown--;
        } else if (be.storedWheat < MAX_WHEAT) {
            ItemStack stack = be.inputContainer.getItem(0);
            if (!stack.isEmpty() && stack.is(VillageMod.BUNDLE_OF_WHEAT.get())) {
                stack.shrink(1);
                be.inputContainer.setItem(0, stack.isEmpty() ? ItemStack.EMPTY : stack);
                be.storedWheat++;
                be.consumeCooldown = 4;
                be.setChanged();
            }
        }

        // ── Spawn a worker when the bar is full and a slot is free ───────────
        if (be.storedWheat >= MAX_WHEAT) {
            if (liveCount < cap) {
                VillagerWorkerEntity worker = new VillagerWorkerEntity(VillageMod.VILLAGER_WORKER.get(), level);
                worker.moveTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5,
                        level.getRandom().nextFloat() * 360f, 0f);
                worker.finalizeSpawn(serverLevel, level.getCurrentDifficultyAt(pos),
                        MobSpawnType.MOB_SUMMONED, null);
                // Use setBaseName so the job-prefixed display name works correctly later
                worker.setBaseName(VillagerWorkerEntity.randomName(level.getRandom()));
                worker.setCustomNameVisible(true);
                level.addFreshEntity(worker);
                be.workerUUIDs.add(worker.getUUID());
                be.storedWheat = 0;
                be.setChanged();
            }
            // If at cap the bar stays full — a slot opens when a worker dies
        }

        // ── Job assignment (every 20 ticks) ──────────────────────────────────
        if (be.jobCheckCooldown > 0) {
            be.jobCheckCooldown--;
        } else {
            be.assignJobs(serverLevel);
            be.jobCheckCooldown = 20;
        }
    }

    // ── Worker tracking ───────────────────────────────────────────────────────

    /** Removes dead/missing UUIDs and returns the live count. */
    private int countLiveWorkers(ServerLevel serverLevel) {
        workerUUIDs.removeIf(uuid -> {
            var entity = serverLevel.getEntity(uuid);
            return entity == null || !entity.isAlive();
        });
        return workerUUIDs.size();
    }

    /**
     * Calls {@link net.minecraft.world.entity.PathfinderMob#restrictTo} on every
     * live worker so that {@link net.minecraft.world.entity.ai.goal.RandomStrollGoal}
     * (and similar wander goals) will never move them outside the village territory.
     */
    private void applyTerritoryRestriction(ServerLevel serverLevel) {
        int radius = getRadius();
        BlockPos heartPos = getBlockPos();
        for (UUID uuid : workerUUIDs) {
            var entity = serverLevel.getEntity(uuid);
            if (entity instanceof VillagerWorkerEntity worker && worker.isAlive()) {
                worker.restrictTo(heartPos, radius);
            }
        }
    }

    /**
     * Returns the worker cap by summing the contribution of every applied upgrade.
     * Tier I must be present before Tier II, and Tier II before Tier III (enforced
     * in the menu slots), so this naturally covers base → Tier 1 → Tier 2 → Tier 3.
     */
    public int getWorkerCap() {
        int cap = BASE_WORKER_CAP;
        if (!upgradeContainer.getItem(0).isEmpty()) cap = TIER1_WORKER_CAP;
        if (!upgradeContainer.getItem(1).isEmpty()) cap = TIER2_WORKER_CAP;
        if (!upgradeContainer.getItem(2).isEmpty()) cap = TIER3_WORKER_CAP;
        return cap;
    }

    /**
     * Returns the territory radius by summing the contribution of every applied upgrade.
     */
    public int getRadius() {
        int radius = BASE_RADIUS;
        if (!upgradeContainer.getItem(0).isEmpty()) radius = TIER1_RADIUS;
        if (!upgradeContainer.getItem(1).isEmpty()) radius = TIER2_RADIUS;
        if (!upgradeContainer.getItem(2).isEmpty()) radius = TIER3_RADIUS;
        return radius;
    }

    // ── Territory scan ────────────────────────────────────────────────────────

    /**
     * Searches loaded chunks near {@code pos} and returns the position of the
     * first Village Heart whose territory contains {@code pos}.
     *
     * <p>Uses chunk block-entity maps rather than iterating individual blocks,
     * so it is fast even at the maximum radius.
     *
     * @param skipHeart  A heart position to ignore (pass the newly-placed
     *                   heart's own pos so it isn't found by its own check),
     *                   or {@code null} to skip nothing.
     */
    public static Optional<BlockPos> findClaimingHeart(ServerLevel level, BlockPos pos,
                                                        @javax.annotation.Nullable BlockPos skipHeart) {
        int cr  = (TIER3_RADIUS >> 4) + 1;   // chunk radius covering the max territory
        int ocx = pos.getX() >> 4;
        int ocz = pos.getZ() >> 4;

        for (int cx = -cr; cx <= cr; cx++) {
            for (int cz = -cr; cz <= cr; cz++) {
                if (!level.hasChunk(ocx + cx, ocz + cz)) continue;
                LevelChunk chunk = level.getChunk(ocx + cx, ocz + cz);
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos heartPos = entry.getKey();
                    if (heartPos.equals(skipHeart)) continue;
                    if (!(entry.getValue() instanceof VillageHeartBlockEntity heartBE)) continue;
                    double r = heartBE.getRadius();
                    if (heartPos.distSqr(pos) <= r * r) {
                        return Optional.of(heartPos.immutable());
                    }
                }
            }
        }
        return Optional.empty();
    }

    // ── Workplace linking ─────────────────────────────────────────────────────

    /**
     * Registers a profession workplace block as linked to this heart.
     * Called by the Village Wand when a player links any workplace block.
     */
    public void linkWorkplace(BlockPos workplacePos) {
        if (linkedWorkplaces.add(workplacePos)) {
            setChanged();
        }
    }

    /**
     * Removes a workplace block from this heart's tracked set.
     * Called when a workplace is broken or relinked to a different heart.
     */
    public void unlinkWorkplace(BlockPos workplacePos) {
        if (linkedWorkplaces.remove(workplacePos)) {
            setChanged();
        }
    }

    // ── Employment logic ──────────────────────────────────────────────────────

    /**
     * Iterates all linked workplace blocks, validates existing assignments, and
     * assigns idle workers to any unoccupied workplace slot.
     *
     * <p>Works generically with every {@link IWorkplaceBlockEntity} — the job type
     * is driven by {@link IWorkplaceBlockEntity#getRequiredJob()}, so adding a new
     * profession only requires registering the new block; no changes here.
     */
    private void assignJobs(ServerLevel serverLevel) {
        List<BlockPos> toRemove = new ArrayList<>();

        for (BlockPos workPos : linkedWorkplaces) {
            // Validate the workplace still exists and is still linked to this heart
            if (!(serverLevel.getBlockEntity(workPos) instanceof IWorkplaceBlockEntity wbe)
                    || !getBlockPos().equals(wbe.getLinkedHeartPos())) {
                toRemove.add(workPos);
                continue;
            }

            UUID assignedUUID = wbe.getAssignedWorkerUUID();

            // Validate the existing assignment (worker alive and belongs to this heart)
            if (assignedUUID != null) {
                Entity entity = serverLevel.getEntity(assignedUUID);
                boolean valid = entity instanceof VillagerWorkerEntity worker
                        && worker.isAlive()
                        && workerUUIDs.contains(assignedUUID);
                if (!valid) {
                    wbe.setAssignedWorkerUUID(null);
                    assignedUUID = null;
                }
            }

            // No assigned worker — find an unemployed one and assign them
            if (assignedUUID == null) {
                JobType requiredJob = wbe.getRequiredJob();
                for (UUID uuid : workerUUIDs) {
                    Entity entity = serverLevel.getEntity(uuid);
                    if (entity instanceof VillagerWorkerEntity worker
                            && worker.isAlive()
                            && worker.getJob() == JobType.UNEMPLOYED) {
                        worker.assign(requiredJob, workPos);
                        wbe.setAssignedWorkerUUID(worker.getUUID());
                        setChanged();
                        break;
                    }
                }
            }
        }

        // Clean up stale entries outside the loop to avoid ConcurrentModificationException
        if (!toRemove.isEmpty()) {
            linkedWorkplaces.removeAll(toRemove);
            setChanged();
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getVillageName() { return villageName; }

    public void setVillageName(String name) {
        this.villageName = name.substring(0, Math.min(name.length(), MAX_NAME_LENGTH));
        this.setChanged();
    }

    public SimpleContainer getInputContainer()   { return inputContainer;   }
    public SimpleContainer getUpgradeContainer() { return upgradeContainer; }

    // ── MenuProvider ──────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.colonycraft.village_heart");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new VillageHeartMenu(containerId, inventory, this);
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        ItemStack inputStack = this.inputContainer.getItem(0);
        if (!inputStack.isEmpty()) {
            tag.put("Input", inputStack.save(registries));
        }

        for (int i = 0; i < 3; i++) {
            ItemStack upgradeStack = this.upgradeContainer.getItem(i);
            if (!upgradeStack.isEmpty()) {
                tag.put("Upgrade" + i, upgradeStack.save(registries));
            }
        }

        tag.putString("VillageName",   this.villageName);
        tag.putInt("StoredWheat",      this.storedWheat);
        tag.putInt("ConsumeCooldown",  this.consumeCooldown);

        // Worker UUIDs
        ListTag uuidList = new ListTag();
        for (UUID uuid : this.workerUUIDs) {
            CompoundTag t = new CompoundTag();
            t.putUUID("UUID", uuid);
            uuidList.add(t);
        }
        tag.put("WorkerUUIDs", uuidList);

        // Linked workplace positions (all profession blocks)
        ListTag workplaceList = new ListTag();
        for (BlockPos workPos : this.linkedWorkplaces) {
            CompoundTag t = new CompoundTag();
            t.putInt("X", workPos.getX());
            t.putInt("Y", workPos.getY());
            t.putInt("Z", workPos.getZ());
            workplaceList.add(t);
        }
        tag.put("LinkedWorkplaces", workplaceList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        if (tag.contains("Input")) {
            this.inputContainer.setItem(0, ItemStack.parseOptional(registries, tag.getCompound("Input")));
        }
        // Load new per-slot keys; migrate legacy single "Upgrade" key into slot 0
        if (tag.contains("Upgrade")) {
            this.upgradeContainer.setItem(0, ItemStack.parseOptional(registries, tag.getCompound("Upgrade")));
        }
        for (int i = 0; i < 3; i++) {
            String key = "Upgrade" + i;
            if (tag.contains(key)) {
                this.upgradeContainer.setItem(i, ItemStack.parseOptional(registries, tag.getCompound(key)));
            }
        }

        this.villageName    = tag.getString("VillageName");
        this.storedWheat    = tag.getInt("StoredWheat");
        this.consumeCooldown = tag.getInt("ConsumeCooldown");

        this.workerUUIDs.clear();
        ListTag uuidList = tag.getList("WorkerUUIDs", Tag.TAG_COMPOUND);
        for (int i = 0; i < uuidList.size(); i++) {
            this.workerUUIDs.add(uuidList.getCompound(i).getUUID("UUID"));
        }

        this.linkedWorkplaces.clear();
        // Load new key; also migrate old "LinkedFarmBlocks" key from pre-refactor saves
        String workplaceKey = tag.contains("LinkedWorkplaces") ? "LinkedWorkplaces" : "LinkedFarmBlocks";
        ListTag workplaceList = tag.getList(workplaceKey, Tag.TAG_COMPOUND);
        for (int i = 0; i < workplaceList.size(); i++) {
            CompoundTag t = workplaceList.getCompound(i);
            this.linkedWorkplaces.add(new BlockPos(t.getInt("X"), t.getInt("Y"), t.getInt("Z")));
        }
    }
}
