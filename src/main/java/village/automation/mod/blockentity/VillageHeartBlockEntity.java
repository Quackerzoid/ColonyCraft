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
import village.automation.mod.entity.CourierEntity;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;
import village.automation.mod.menu.VillageHeartMenu;
import village.automation.mod.raid.RaidEventHandler;
import village.automation.mod.raid.RaidLootTable;
import village.automation.mod.raid.RaidSpawnHelper;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;

import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.AABB;

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

    public enum RaidOutcome { NONE, VICTORY, DEFEAT }

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

    // UUIDs of soul iron golems linked to this heart (server-side only)
    private final Set<UUID> golemUUIDs = new HashSet<>();

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

    // ── Raid state ────────────────────────────────────────────────────────────
    private boolean raidActive                  = false;
    private int     raidWave                    = 0;
    private int     raidMaxWaves                = 3;
    private int     raidCombatMobsThisWave      = 0;  // combat mobs spawned this wave (excl. supply/donkeys)
    private int     raidCombatMobsKilled        = 0;  // how many killed so far this wave
    private int     raidWorkersAtStart          = 0;
    private int     raidWorkersLost             = 0;  // deaths + kidnaps since raid start
    private boolean supplyKeeperAlive           = false;
    private long    raidCooldownUntil           = 0L;
    private final List<UUID> raidMobUUIDs       = new ArrayList<>();
    private boolean raidRetreating              = false;
    private int     raidRetreatingTicksRemaining = 0;
    private RaidOutcome pendingOutcome          = RaidOutcome.NONE;
    private float   raidMorale                  = 0.5f;
    private boolean waveGearingUp               = false;

    // Transient — recreated from raidMorale on first tick after load
    private transient ServerBossEvent raidBossBar = null;
    private int bossBarUpdateTimer = 0;

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
        be.cleanDeadGolems(serverLevel);
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

        // ── Boss bar: lazy init + player list refresh ─────────────────────────
        if (be.raidActive) {
            if (be.raidBossBar == null) be.initRaidBar();
            if (--be.bossBarUpdateTimer <= 0) {
                be.updateBossBarPlayers(serverLevel);
                be.bossBarUpdateTimer = 20;
            }
        }

        // ── Raid retreat countdown ─────────────────────────────────────────────
        if (be.raidRetreating && be.raidRetreatingTicksRemaining > 0) {
            be.raidRetreatingTicksRemaining--;
            if (be.raidRetreatingTicksRemaining <= 0) {
                be.endRaid(be.pendingOutcome, serverLevel);
            }
        }
    }

    // ── Worker tracking ───────────────────────────────────────────────────────

    /** Re-registers a resurrected (or externally created) worker with this heart's colony. */
    public void registerWorker(UUID uuid) {
        workerUUIDs.add(uuid);
        setChanged();
    }

    /** Registers a courier golem as belonging to this heart's colony. */
    public void registerCourier(UUID uuid) {
        courierUUIDs.add(uuid);
        setChanged();
    }

    /** Returns the task dispatcher shared by all couriers linked to this heart. */
    public CourierDispatcher getCourierDispatcher() {
        return courierDispatcher;
    }

    /** Registers a soul iron golem as belonging to this heart's colony. */
    public void registerGolem(UUID uuid) {
        golemUUIDs.add(uuid);
        setChanged();
    }

    public Set<UUID> getGolemUUIDs() { return java.util.Collections.unmodifiableSet(golemUUIDs); }

    /** Removes dead/unloaded soul iron golem UUIDs. */
    private void cleanDeadGolems(ServerLevel serverLevel) {
        golemUUIDs.removeIf(uuid -> {
            var entity = serverLevel.getEntity(uuid);
            return entity == null || !entity.isAlive();
        });
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

            // No assigned worker — find an unemployed one and assign them.
            // Prefer workers whose preferredJob matches the required job (e.g. a
            // resurrected farmer gets their farm back before a generic worker does).
            if (assignedUUID == null) {
                JobType requiredJob = wbe.getRequiredJob();
                VillagerWorkerEntity preferred = null;
                VillagerWorkerEntity fallback  = null;
                for (UUID uuid : workerUUIDs) {
                    Entity entity = serverLevel.getEntity(uuid);
                    if (entity instanceof VillagerWorkerEntity worker
                            && worker.isAlive()
                            && worker.getJob() == JobType.UNEMPLOYED) {
                        if (preferred == null && worker.getPreferredJob() == requiredJob) {
                            preferred = worker;
                        } else if (fallback == null) {
                            fallback = worker;
                        }
                    }
                }
                VillagerWorkerEntity chosen = preferred != null ? preferred : fallback;
                if (chosen != null) {
                    chosen.assign(requiredJob, workPos);
                    wbe.setAssignedWorkerUUID(chosen.getUUID());
                    setChanged();
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

    /** Returns {@code true} if at least one living redstone-variant courier is linked to this heart. */
    public boolean hasRedstoneCourier(ServerLevel level) {
        for (UUID uuid : courierUUIDs) {
            Entity e = level.getEntity(uuid);
            if (e instanceof CourierEntity c && c.isAlive() && c.isRedstoneVariant()) return true;
        }
        return false;
    }

    // ── Raid ──────────────────────────────────────────────────────────────────

    public boolean isRaidActive()    { return raidActive; }
    public boolean isRaidRetreating(){ return raidRetreating; }
    public long getRaidCooldownUntil(){ return raidCooldownUntil; }
    public List<UUID> getRaidMobUUIDs(){ return raidMobUUIDs; }

    public void addRaidMob(UUID uuid) {
        raidMobUUIDs.add(uuid);
        setChanged();
    }

    public void startRaid(ServerLevel level) {
        raidActive             = true;
        raidWave               = 0;
        raidMaxWaves = switch (appliedUpgrades) {
            case 1  -> 4;
            case 2  -> 5;
            case 3  -> 6;
            default -> 3;
        };
        raidCombatMobsThisWave = 0;
        raidCombatMobsKilled   = 0;
        raidWorkersAtStart     = countLiveWorkers(level);
        raidWorkersLost        = 0;
        supplyKeeperAlive      = true;
        raidRetreating         = false;
        pendingOutcome         = RaidOutcome.NONE;
        raidMorale             = 0.5f;
        waveGearingUp          = false;
        initRaidBar();
        setChanged();
        advanceWave(level);
    }

    public void advanceWave(ServerLevel level) {
        raidWave++;
        if (raidWave > raidMaxWaves) {
            triggerRetreat(RaidOutcome.VICTORY, level);
            return;
        }
        refreshRaidBarTitle();
        RaidSpawnHelper.spawnWave(this, level, raidWave);
        setChanged();
        broadcastSubtitle(level, "§6Wave " + raidWave + " of " + raidMaxWaves + " — §eGearing Up!", 96);
    }

    public void endRaid(RaidOutcome outcome, ServerLevel level) {
        BlockPos hp = getBlockPos();

        if (outcome == RaidOutcome.VICTORY) {
            float scale = raidMaxWaves / 3.0f;
            for (ItemStack stack : RaidLootTable.rollWithLevel(level.getRandom(), raidWave, level)) {
                int cnt = Math.min((int)(stack.getCount() * scale), stack.getMaxStackSize());
                stack.setCount(Math.max(1, cnt));
                level.addFreshEntity(new ItemEntity(level,
                        hp.getX() + 0.5, hp.getY() + 1.0, hp.getZ() + 0.5, stack));
            }
            broadcastTitle(level, "§aRaid Defeated! §7Your village stands strong.", 96);
        } else if (outcome == RaidOutcome.DEFEAT) {
            broadcastTitle(level, "§cYour village has fallen...", 96);
        }

        // Discard all living raid mobs
        for (UUID uuid : raidMobUUIDs) {
            Entity e = level.getEntity(uuid);
            if (e != null && e.isAlive()) e.discard();
        }
        raidMobUUIDs.clear();

        // Release any kidnapped workers
        AABB box = new AABB(hp).inflate(200);
        level.getEntitiesOfClass(VillagerWorkerEntity.class, box,
                w -> w.getPersistentData().getBoolean("Kidnapped"))
             .forEach(w -> {
                 w.stopRiding();
                 w.getPersistentData().remove("Kidnapped");
             });

        RaidEventHandler.clearGearingUp(this);
        destroyRaidBar();
        raidActive                   = false;
        raidWave                     = 0;
        raidRetreating               = false;
        pendingOutcome               = RaidOutcome.NONE;
        raidCombatMobsThisWave       = 0;
        raidCombatMobsKilled         = 0;
        raidWorkersLost              = 0;
        supplyKeeperAlive            = false;
        raidRetreatingTicksRemaining = 0;
        raidMorale                   = 0.5f;
        waveGearingUp                = false;
        setChanged();
    }

    public boolean isWaveGearingUp()            { return waveGearingUp; }
    public void setWaveGearingUp(boolean v)     { waveGearingUp = v; setChanged(); }
    public float getRaidMorale()                { return raidMorale; }

    /**
     * Shifts the morale bar by {@code delta}.
     * At 0: advances to the next wave (or triggers victory retreat on the final wave).
     * At 1: ends the raid in defeat.
     */
    public void adjustMorale(float delta, ServerLevel level) {
        if (!raidActive || raidRetreating) return;
        raidMorale = Math.max(0.0f, Math.min(1.0f, raidMorale + delta));
        updateRaidBar(level);
        setChanged();
        if (raidMorale <= 0.0f && !waveGearingUp) {
            if (raidWave < raidMaxWaves && supplyKeeperAlive) {
                // Advance to the next wave and reset the bar to 50%
                raidMorale           = 0.5f;
                raidCombatMobsKilled = 0;
                updateRaidBar(level);
                advanceWave(level);
            } else {
                triggerRetreat(RaidOutcome.VICTORY, level);
            }
        } else if (raidMorale >= 1.0f) {
            endRaid(RaidOutcome.DEFEAT, level);
        }
    }

    /** Called by RaidSpawnHelper after spawning a wave's combat mobs. */
    public void setCombatMobsThisWave(int n) {
        raidCombatMobsThisWave = n;
        raidCombatMobsKilled   = 0;
        setChanged();
    }

    /** Called when a non-supply, non-donkey raider dies. Drains morale; forces to 0 at 75% kills. */
    public void onCombatMobKilled(ServerLevel level) {
        if (!raidActive || raidRetreating) return;
        raidCombatMobsKilled++;
        setChanged();
        if (raidCombatMobsThisWave > 0) {
            float perKill = 0.5f / (raidCombatMobsThisWave * 0.75f);
            adjustMorale(-perKill, level);
            if (raidRetreating || !raidActive) return;
        }
        // Hard threshold: force morale to 0 at exactly 75% kills (guards against float drift)
        if (raidCombatMobsKilled >= raidCombatMobsThisWave * 0.75f && raidMorale > 0f) {
            adjustMorale(-1.0f, level);
        }
    }

    /** Called when the supply keeper dies. No more waves will spawn; morale goes to 100% (defeat). */
    public void onSupplyKeeperKilled(ServerLevel level) {
        supplyKeeperAlive = false;
        setChanged();
        adjustMorale(1.0f, level);
    }

    /** Called when a worker dies inside territory or is successfully kidnapped. */
    public void onWorkerLost(ServerLevel level) {
        if (!raidActive) return;
        raidWorkersLost++;
        setChanged();
        if (raidWorkersAtStart > 0) {
            adjustMorale(2.0f / raidWorkersAtStart, level);
        }
    }

    private void initRaidBar() {
        raidBossBar = new ServerBossEvent(
                Component.literal("§c⚔ §rRaiders' Morale  §7(Wave §f" + raidWave + "§7/§f" + raidMaxWaves + "§7)"),
                BossEvent.BossBarColor.YELLOW,
                BossEvent.BossBarOverlay.NOTCHED_10);
        raidBossBar.setProgress(raidMorale);
    }

    private void updateRaidBar(ServerLevel level) {
        if (raidBossBar == null) return;
        raidBossBar.setProgress(raidMorale);
        BossEvent.BossBarColor color;
        if (raidMorale < 0.33f)      color = BossEvent.BossBarColor.GREEN;
        else if (raidMorale < 0.66f) color = BossEvent.BossBarColor.YELLOW;
        else                         color = BossEvent.BossBarColor.RED;
        raidBossBar.setColor(color);
    }

    // Refreshes the title (called each time the wave number changes).
    public void refreshRaidBarTitle() {
        if (raidBossBar == null) return;
        raidBossBar.setName(Component.literal(
                "§c⚔ §rRaiders' Morale  §7(Wave §f" + raidWave + "§7/§f" + raidMaxWaves + "§7)"));
    }

    private void destroyRaidBar() {
        if (raidBossBar != null) {
            raidBossBar.removeAllPlayers();
            raidBossBar = null;
        }
    }

    private void updateBossBarPlayers(ServerLevel level) {
        if (raidBossBar == null) return;
        BlockPos hp = getBlockPos();
        double r2   = 96.0 * 96.0;
        // Remove out-of-range players
        for (ServerPlayer p : List.copyOf(raidBossBar.getPlayers())) {
            if (p.distanceToSqr(hp.getX(), hp.getY(), hp.getZ()) > r2) raidBossBar.removePlayer(p);
        }
        // Add in-range players
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(hp.getX(), hp.getY(), hp.getZ()) <= r2) raidBossBar.addPlayer(p);
        }
    }

    private void triggerRetreat(RaidOutcome outcome, ServerLevel level) {
        raidRetreating               = true;
        pendingOutcome               = outcome;
        raidRetreatingTicksRemaining = 600;
        setChanged();

        String subtitle = outcome == RaidOutcome.VICTORY
                ? "§eThe raiders are retreating!"
                : "§cThe raiders have taken enough — they're withdrawing!";
        broadcastSubtitle(level, subtitle, 96);

        BlockPos hp = getBlockPos();
        for (UUID uuid : raidMobUUIDs) {
            Entity e = level.getEntity(uuid);
            if (!(e instanceof PathfinderMob mob) || !mob.isAlive()) continue;
            if (mob.getPersistentData().getInt("RaidCaptain") == 1) continue;
            if (mob.getPersistentData().getInt("SupplyKeeper") == 1) continue;

            RaidSpawnHelper.attemptKidnap(mob, this, level);

            double dx  = mob.getX() - hp.getX();
            double dz  = mob.getZ() - hp.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.01) { dx = 1; dz = 0; len = 1; }
            double dist = 80 + level.getRandom().nextDouble() * 40;
            int rx = (int)(hp.getX() + (dx / len) * dist);
            int rz = (int)(hp.getZ() + (dz / len) * dist);
            BlockPos retreatPos = level.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(rx, 0, rz));

            mob.getPersistentData().putInt("RetreatX", retreatPos.getX());
            mob.getPersistentData().putInt("RetreatY", retreatPos.getY());
            mob.getPersistentData().putInt("RetreatZ", retreatPos.getZ());
            mob.getPersistentData().putBoolean("Retreating", true);

            var speedAttr = mob.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) speedAttr.setBaseValue(speedAttr.getBaseValue() * 1.3);

            mob.getNavigation().moveTo(retreatPos.getX() + 0.5,
                    retreatPos.getY(), retreatPos.getZ() + 0.5, 1.3);
        }
    }

    // Broadcasts a full-screen title to players within radius blocks of this heart.
    public void broadcastTitle(ServerLevel level, String title, double radius) {
        BlockPos hp = getBlockPos();
        double r2   = radius * radius;
        Component c = Component.literal(title);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(hp.getX(), hp.getY(), hp.getZ()) <= r2) {
                p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
                p.connection.send(new ClientboundSetTitleTextPacket(c));
            }
        }
    }

    public void broadcastSubtitle(ServerLevel level, String subtitle, double radius) {
        BlockPos hp = getBlockPos();
        double r2   = radius * radius;
        Component c = Component.literal(subtitle);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(hp.getX(), hp.getY(), hp.getZ()) <= r2) {
                p.connection.send(new ClientboundSetSubtitleTextPacket(c));
            }
        }
    }

    public void broadcastActionBar(ServerLevel level, String msg, double radius) {
        BlockPos hp = getBlockPos();
        double r2   = radius * radius;
        Component c = Component.literal(msg);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(hp.getX(), hp.getY(), hp.getZ()) <= r2) {
                p.displayClientMessage(c, true);
            }
        }
    }

    /** Finds the nearest VillageHeartBlockEntity within maxDist blocks, or null. */
    @javax.annotation.Nullable
    public static VillageHeartBlockEntity findNearestWithin(ServerLevel level, BlockPos origin, double maxDist) {
        int cr  = ((int) maxDist >> 4) + 1;
        int ocx = origin.getX() >> 4;
        int ocz = origin.getZ() >> 4;
        double bestSq = maxDist * maxDist;
        VillageHeartBlockEntity best = null;
        for (int cx = -cr; cx <= cr; cx++) {
            for (int cz = -cr; cz <= cr; cz++) {
                if (!level.hasChunk(ocx + cx, ocz + cz)) continue;
                LevelChunk chunk = level.getChunk(ocx + cx, ocz + cz);
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    if (!(entry.getValue() instanceof VillageHeartBlockEntity hbe)) continue;
                    double dSq = entry.getKey().distSqr(origin);
                    if (dSq <= bestSq) { bestSq = dSq; best = hbe; }
                }
            }
        }
        return best;
    }

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

        // Soul Iron Golem UUIDs
        ListTag golemList = new ListTag();
        for (UUID uuid : this.golemUUIDs) {
            CompoundTag t = new CompoundTag();
            t.putUUID("UUID", uuid);
            golemList.add(t);
        }
        tag.put("GolemUUIDs", golemList);

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

        // Raid state
        tag.putBoolean("RaidActive",                  this.raidActive);
        tag.putInt("RaidWave",                        this.raidWave);
        tag.putInt("RaidMaxWaves",                    this.raidMaxWaves);
        tag.putInt("RaidCombatMobsThisWave",          this.raidCombatMobsThisWave);
        tag.putInt("RaidCombatMobsKilled",            this.raidCombatMobsKilled);
        tag.putInt("RaidWorkersAtStart",              this.raidWorkersAtStart);
        tag.putInt("RaidWorkersLost",                 this.raidWorkersLost);
        tag.putBoolean("SupplyKeeperAlive",           this.supplyKeeperAlive);
        tag.putLong("RaidCooldownUntil",              this.raidCooldownUntil);
        tag.putBoolean("RaidRetreating",              this.raidRetreating);
        tag.putInt("RaidRetreatingTicksRemaining",    this.raidRetreatingTicksRemaining);
        tag.putString("PendingOutcome",               this.pendingOutcome.name());
        ListTag raidMobList = new ListTag();
        for (UUID uuid : this.raidMobUUIDs) {
            CompoundTag t = new CompoundTag();
            t.putUUID("UUID", uuid);
            raidMobList.add(t);
        }
        tag.put("RaidMobUUIDs", raidMobList);
        tag.putFloat("RaidMorale",      this.raidMorale);
        tag.putBoolean("WaveGearingUp", this.waveGearingUp);
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

        this.golemUUIDs.clear();
        ListTag golemList = tag.getList("GolemUUIDs", Tag.TAG_COMPOUND);
        for (int i = 0; i < golemList.size(); i++) {
            this.golemUUIDs.add(golemList.getCompound(i).getUUID("UUID"));
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

        // Raid state
        this.raidActive                   = tag.getBoolean("RaidActive");
        this.raidWave                     = tag.getInt("RaidWave");
        this.raidMaxWaves                 = tag.contains("RaidMaxWaves") ? tag.getInt("RaidMaxWaves") : 3;
        this.raidCombatMobsThisWave       = tag.getInt("RaidCombatMobsThisWave");
        this.raidCombatMobsKilled         = tag.getInt("RaidCombatMobsKilled");
        this.raidWorkersAtStart           = tag.getInt("RaidWorkersAtStart");
        this.raidWorkersLost              = tag.getInt("RaidWorkersLost");
        this.supplyKeeperAlive            = tag.getBoolean("SupplyKeeperAlive");
        this.raidCooldownUntil            = tag.getLong("RaidCooldownUntil");
        this.raidRetreating               = tag.getBoolean("RaidRetreating");
        this.raidRetreatingTicksRemaining = tag.getInt("RaidRetreatingTicksRemaining");
        try {
            this.pendingOutcome = RaidOutcome.valueOf(tag.getString("PendingOutcome"));
        } catch (IllegalArgumentException ignored) {
            this.pendingOutcome = RaidOutcome.NONE;
        }
        this.raidMobUUIDs.clear();
        ListTag raidMobList = tag.getList("RaidMobUUIDs", Tag.TAG_COMPOUND);
        for (int i = 0; i < raidMobList.size(); i++) {
            this.raidMobUUIDs.add(raidMobList.getCompound(i).getUUID("UUID"));
        }
        this.raidMorale    = tag.contains("RaidMorale")    ? tag.getFloat("RaidMorale")      : 0.5f;
        // waveGearingUp is not restored on reload — mobs resume attacking immediately after restart
    }
}
