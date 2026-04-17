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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import village.automation.mod.ItemRequest;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.CourierDispatcher;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;
import village.automation.mod.menu.VillageHeartMenu;

import java.util.ArrayList;
import java.util.Collections;
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
    private final SimpleContainer inputContainer  = new SimpleContainer(1);
    // Single consumable upgrade input slot — item is absorbed and appliedUpgrades advances
    private final SimpleContainer upgradeInputSlot = new SimpleContainer(1);
    // How many upgrade tiers have been consumed (0 = base, 3 = max)
    private int appliedUpgrades = 0;

    private int storedWheat    = 0;
    private int consumeCooldown = 0;

    // UUIDs of workers spawned by this heart (server-side only)
    private final Set<UUID> workerUUIDs = new HashSet<>();

    // UUIDs of courier golems linked to this heart (server-side only)
    private final Set<UUID> courierUUIDs = new HashSet<>();

    // Shared task-coordination layer — prevents multiple couriers from duplicating work.
    // Transient: not persisted to NBT; resets cleanly on server restart.
    private final CourierDispatcher courierDispatcher = new CourierDispatcher();

    // Positions of all profession workplace blocks linked to this heart (server-side only)
    private final Set<BlockPos> linkedWorkplaces = new HashSet<>();

    // Cooldown between job-assignment checks (runs every 20 ticks / 1 second)
    private int jobCheckCooldown = 0;

    // Pending item requests from workers — one entry per worker UUID
    private final List<ItemRequest> pendingRequests = new ArrayList<>();
    // Incremented whenever the request list changes so menus know to re-sync
    private int requestsVersion = 0;

    // ── Chest registry ────────────────────────────────────────────────────────
    // All Container block entities (chests, barrels) within village territory.
    // Refreshed every CHEST_SCAN_INTERVAL ticks.
    private final Set<BlockPos> registeredChests = new HashSet<>();
    private int chestScanCooldown = 0;
    private static final int CHEST_SCAN_INTERVAL = 400;  // 20 s

    // Computed server-side each tick; synced to client via ContainerData
    private int syncedWorkerCount = 0;
    private int syncedWorkerCap   = BASE_WORKER_CAP;

    // ContainerData: index 0 = storedWheat, 1 = workerCount, 2 = workerCap,
    //                index 3 = radius (derived, read-only setter),
    //                index 4 = appliedUpgrades (directly synced so client mayPlace works)
    public final ContainerData data = new ContainerData() {
        @Override public int get(int i) {
            return switch (i) {
                case 0 -> storedWheat;
                case 1 -> syncedWorkerCount;
                case 2 -> syncedWorkerCap;
                case 3 -> getRadius();
                case 4 -> appliedUpgrades;
                default -> 0;
            };
        }
        @Override public void set(int i, int v) {
            switch (i) {
                case 0 -> storedWheat       = v;
                case 1 -> syncedWorkerCount = v;
                case 2 -> syncedWorkerCap   = v;
                // index 3 (radius) is derived — read-only
                case 4 -> appliedUpgrades   = v;
            }
        }
        @Override public int getCount() { return 5; }
    };

    public VillageHeartBlockEntity(BlockPos pos, BlockState state) {
        super(VillageMod.VILLAGE_HEART_BE.get(), pos, state);
        this.inputContainer.addListener(c -> this.setChanged());
        this.upgradeInputSlot.addListener(c -> this.setChanged());
    }

    // ── Ticker ───────────────────────────────────────────────────────────────

    public static void serverTick(Level level, BlockPos pos, BlockState state, VillageHeartBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        // ── Courier dispatcher — must tick first so expiry runs before goal logic ──
        be.courierDispatcher.tick();

        // ── Worker count + cap sync ──────────────────────────────────────────
        be.cleanDeadCouriers(serverLevel);
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

        // ── Consume upgrade item from input slot ──────────────────────────────
        if (be.appliedUpgrades < 3) {
            ItemStack upgStack = be.upgradeInputSlot.getItem(0);
            if (!upgStack.isEmpty()) {
                boolean ok = (be.appliedUpgrades == 0 && upgStack.is(VillageMod.VILLAGE_UPGRADE.get()))
                          || (be.appliedUpgrades == 1 && upgStack.is(VillageMod.VILLAGE_UPGRADE_II.get()))
                          || (be.appliedUpgrades == 2 && upgStack.is(VillageMod.VILLAGE_UPGRADE_III.get()));
                if (ok) {
                    be.upgradeInputSlot.setItem(0, ItemStack.EMPTY);
                    be.appliedUpgrades++;
                    be.setChanged();
                }
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

        // ── Chest scan (every 400 ticks) ──────────────────────────────────────
        if (be.chestScanCooldown > 0) {
            be.chestScanCooldown--;
        } else {
            be.scanForChests(serverLevel);
            be.chestScanCooldown = CHEST_SCAN_INTERVAL;
        }
    }

    // ── Worker tracking ───────────────────────────────────────────────────────

    /** Registers a courier golem as belonging to this heart's colony. */
    public void registerCourier(UUID uuid) {
        courierUUIDs.add(uuid);
        setChanged();
    }

    /** Returns the task dispatcher shared by all couriers linked to this heart. */
    public CourierDispatcher getCourierDispatcher() {
        return courierDispatcher;
    }

    /** Removes dead/unloaded courier UUIDs and releases their dispatcher locks. */
    private void cleanDeadCouriers(ServerLevel serverLevel) {
        courierUUIDs.removeIf(uuid -> {
            var entity = serverLevel.getEntity(uuid);
            boolean dead = entity == null || !entity.isAlive();
            if (dead) courierDispatcher.release(uuid);
            return dead;
        });
    }

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
        if (appliedUpgrades >= 3) return TIER3_WORKER_CAP;
        if (appliedUpgrades >= 2) return TIER2_WORKER_CAP;
        if (appliedUpgrades >= 1) return TIER1_WORKER_CAP;
        return BASE_WORKER_CAP;
    }

    public int getRadius() {
        if (appliedUpgrades >= 3) return TIER3_RADIUS;
        if (appliedUpgrades >= 2) return TIER2_RADIUS;
        if (appliedUpgrades >= 1) return TIER1_RADIUS;
        return BASE_RADIUS;
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

    // ── Chest scanning ────────────────────────────────────────────────────────

    /**
     * Scans all loaded chunks within the village territory and populates
     * {@link #registeredChests} with every {@code ChestBlockEntity} or
     * {@code BarrelBlockEntity} found.  Uses the same chunk-map approach as
     * {@link #findClaimingHeart} to avoid per-block iteration.
     */
    private void scanForChests(ServerLevel level) {
        registeredChests.clear();
        int radius = getRadius();
        BlockPos heartPos = getBlockPos();
        int cr  = (radius >> 4) + 1;
        int ocx = heartPos.getX() >> 4;
        int ocz = heartPos.getZ() >> 4;

        for (int cx = -cr; cx <= cr; cx++) {
            for (int cz = -cr; cz <= cr; cz++) {
                if (!level.hasChunk(ocx + cx, ocz + cz)) continue;
                LevelChunk chunk = level.getChunk(ocx + cx, ocz + cz);
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos bePos = entry.getKey();
                    BlockEntity be = entry.getValue();
                    if (!(be instanceof net.minecraft.world.Container)) continue;
                    if (be instanceof VillageHeartBlockEntity) continue;  // don't list ourselves
                    if (heartPos.distSqr(bePos) <= (double)(radius * radius)) {
                        registeredChests.add(bePos.immutable());
                    }
                }
            }
        }
        setChanged();
    }

    public Set<BlockPos> getRegisteredChests() {
        return java.util.Collections.unmodifiableSet(registeredChests);
    }

    public Set<BlockPos> getLinkedWorkplaces() {
        return java.util.Collections.unmodifiableSet(linkedWorkplaces);
    }

    // ── Blacksmith lookup ─────────────────────────────────────────────────────

    /**
     * Returns the live BLACKSMITH worker belonging to this heart, or {@code null}.
     */
    @javax.annotation.Nullable
    public VillagerWorkerEntity getBlacksmithWorker(ServerLevel level) {
        for (UUID uuid : workerUUIDs) {
            Entity e = level.getEntity(uuid);
            if (e instanceof VillagerWorkerEntity w && w.isAlive()
                    && w.getJob() == JobType.BLACKSMITH) return w;
        }
        return null;
    }

    // ── Item requests ─────────────────────────────────────────────────────────

    /**
     * Adds a request from a worker.  Only one active request per worker is
     * kept — if the same worker already has a request it is replaced so the
     * item name stays current (e.g. hoe broke and they now need a pickaxe after
     * a job change).
     */
    public void addRequest(ItemRequest req) {
        pendingRequests.removeIf(r -> r.getWorkerUUID().equals(req.getWorkerUUID()));
        pendingRequests.add(req);
        requestsVersion++;
        setChanged();
    }

    /**
     * Removes any pending request from the given worker (called when the
     * worker receives the item or is reassigned).
     */
    public void resolveRequest(UUID workerUUID) {
        if (pendingRequests.removeIf(r -> r.getWorkerUUID().equals(workerUUID))) {
            requestsVersion++;
            setChanged();
        }
    }

    public List<ItemRequest> getPendingRequests() {
        return Collections.unmodifiableList(pendingRequests);
    }

    /** Monotonically increasing counter — menus compare against this to detect changes. */
    public int getRequestsVersion() { return requestsVersion; }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getVillageName() { return villageName; }

    public void setVillageName(String name) {
        this.villageName = name.substring(0, Math.min(name.length(), MAX_NAME_LENGTH));
        this.setChanged();
    }

    public SimpleContainer getInputContainer()   { return inputContainer;    }
    public SimpleContainer getUpgradeInputSlot() { return upgradeInputSlot; }
    public Set<UUID> getCourierUUIDs()           { return Collections.unmodifiableSet(courierUUIDs); }

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

        tag.putInt("AppliedUpgrades", this.appliedUpgrades);
        ItemStack upgInputStack = this.upgradeInputSlot.getItem(0);
        if (!upgInputStack.isEmpty()) {
            tag.put("UpgradeInput", upgInputStack.save(registries));
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

        // Courier UUIDs
        ListTag courierList = new ListTag();
        for (UUID uuid : this.courierUUIDs) {
            CompoundTag t = new CompoundTag();
            t.putUUID("UUID", uuid);
            courierList.add(t);
        }
        tag.put("CourierUUIDs", courierList);

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

        // Pending item requests
        ListTag requestList = new ListTag();
        for (ItemRequest req : this.pendingRequests) {
            requestList.add(req.save());
        }
        tag.put("PendingRequests", requestList);

        // Registered chests (persist so the courier can start working immediately on reload)
        ListTag chestList = new ListTag();
        for (BlockPos cp : this.registeredChests) {
            CompoundTag ct = new CompoundTag();
            ct.putInt("X", cp.getX()); ct.putInt("Y", cp.getY()); ct.putInt("Z", cp.getZ());
            chestList.add(ct);
        }
        tag.put("RegisteredChests", chestList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        if (tag.contains("Input")) {
            this.inputContainer.setItem(0, ItemStack.parseOptional(registries, tag.getCompound("Input")));
        }
        // Load upgrade progress; migrate from legacy per-slot saves
        if (tag.contains("AppliedUpgrades")) {
            this.appliedUpgrades = tag.getInt("AppliedUpgrades");
        } else {
            // Legacy migration: count how many upgrade slots were filled
            int count = 0;
            if (tag.contains("Upgrade")) count = 1;
            for (int i = 0; i < 3; i++) {
                if (tag.contains("Upgrade" + i)) count = i + 1;
            }
            this.appliedUpgrades = count;
        }
        if (tag.contains("UpgradeInput")) {
            this.upgradeInputSlot.setItem(0, ItemStack.parseOptional(registries, tag.getCompound("UpgradeInput")));
        }

        this.villageName    = tag.getString("VillageName");
        this.storedWheat    = tag.getInt("StoredWheat");
        this.consumeCooldown = tag.getInt("ConsumeCooldown");

        this.workerUUIDs.clear();
        ListTag uuidList = tag.getList("WorkerUUIDs", Tag.TAG_COMPOUND);
        for (int i = 0; i < uuidList.size(); i++) {
            this.workerUUIDs.add(uuidList.getCompound(i).getUUID("UUID"));
        }

        this.courierUUIDs.clear();
        ListTag courierList = tag.getList("CourierUUIDs", Tag.TAG_COMPOUND);
        for (int i = 0; i < courierList.size(); i++) {
            this.courierUUIDs.add(courierList.getCompound(i).getUUID("UUID"));
        }

        this.linkedWorkplaces.clear();
        // Load new key; also migrate old "LinkedFarmBlocks" key from pre-refactor saves
        String workplaceKey = tag.contains("LinkedWorkplaces") ? "LinkedWorkplaces" : "LinkedFarmBlocks";
        ListTag workplaceList = tag.getList(workplaceKey, Tag.TAG_COMPOUND);
        for (int i = 0; i < workplaceList.size(); i++) {
            CompoundTag t = workplaceList.getCompound(i);
            this.linkedWorkplaces.add(new BlockPos(t.getInt("X"), t.getInt("Y"), t.getInt("Z")));
        }

        this.pendingRequests.clear();
        ListTag requestList = tag.getList("PendingRequests", Tag.TAG_COMPOUND);
        for (int i = 0; i < requestList.size(); i++) {
            try {
                this.pendingRequests.add(ItemRequest.load(requestList.getCompound(i)));
            } catch (Exception ignored) {
                // Corrupt or unknown item — skip silently
            }
        }

        // Registered chests
        this.registeredChests.clear();
        ListTag chestList = tag.getList("RegisteredChests", Tag.TAG_COMPOUND);
        for (int i = 0; i < chestList.size(); i++) {
            CompoundTag ct = chestList.getCompound(i);
            this.registeredChests.add(new BlockPos(ct.getInt("X"), ct.getInt("Y"), ct.getInt("Z")));
        }
    }
}
