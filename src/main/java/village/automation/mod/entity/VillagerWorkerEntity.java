package village.automation.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.npc.AbstractVillager;
import village.automation.mod.ItemRequest;
import village.automation.mod.entity.SmithRecipe;
import village.automation.mod.entity.goal.ChefWorkGoal;
import village.automation.mod.entity.goal.FarmerWorkGoal;
import village.automation.mod.entity.goal.AnimalKeeperWorkGoal;
import village.automation.mod.entity.goal.OpenNearbyDoorsGoal;
import village.automation.mod.entity.goal.FishermanWorkGoal;
import village.automation.mod.entity.goal.LumberjackWorkGoal;
import village.automation.mod.entity.goal.FetchFoodGoal;
import village.automation.mod.entity.goal.MinerWorkGoal;
import village.automation.mod.entity.goal.SmithCraftGoal;
import village.automation.mod.entity.goal.SmelterWorkGoal;
import village.automation.mod.entity.goal.WorkerSleepGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import village.automation.mod.menu.VillagerWorkerMenu;

import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.List;

public class VillagerWorkerEntity extends AbstractVillager {

    // ── Synced data ───────────────────────────────────────────────────────────
    // Synced so the client renderer can switch the profession overlay.
    private static final EntityDataAccessor<String>  DATA_JOB  =
            SynchedEntityData.defineId(VillagerWorkerEntity.class, EntityDataSerializers.STRING);
    // Synced so the worker GUI screen can display an up-to-date food bar.
    private static final EntityDataAccessor<Integer> DATA_FOOD =
            SynchedEntityData.defineId(VillagerWorkerEntity.class, EntityDataSerializers.INT);

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_JOB,  JobType.UNEMPLOYED.name());
        builder.define(DATA_FOOD, MAX_FOOD);
    }

    // ── Food constants ────────────────────────────────────────────────────────
    /** Maximum food level — mirrors vanilla's hunger bar scale. */
    public static final int MAX_FOOD              = 20;
    /**
     * Food level below which the worker refuses to work (20 % of max).
     * Matches the "no work" condition checked by every work goal.
     */
    public static final int WORK_FOOD_THRESHOLD   = 4;
    /** Food level below which a worker will proactively eat from their inventory. */
    private static final int EAT_THRESHOLD        = 16;   // 80 %

    /** Ticks between automatic food drain (1 point per 30 s of in-game time). */
    private static final int FOOD_DRAIN_INTERVAL  = 600;
    /** How often the entity checks its inventory for something to eat. */
    private static final int EAT_CHECK_INTERVAL   = 40;
    /** Ticks between starvation damage pulses (every 4 s). */
    private static final int STARVE_DAMAGE_INTERVAL = 80;

    // ── Food state ────────────────────────────────────────────────────────────
    /** Countdown to the next automatic food drain tick. */
    private int foodDrainTimer    = FOOD_DRAIN_INTERVAL;
    /** Countdown to the next eat-from-inventory check. */
    private int eatCheckTimer     = EAT_CHECK_INTERVAL;
    /** Countdown to the next starvation damage pulse. */
    private int starvationTimer   = STARVE_DAMAGE_INTERVAL;

    // ── Name pool ─────────────────────────────────────────────────────────────
    private static final List<String> NAMES = List.of(
        // --- classic medieval names ---
        "Aldric",    "Barnaby",   "Cedric",    "Duncan",    "Edmund",
        "Fletcher",  "Garrett",   "Harold",    "Ingram",    "Jasper",
        "Kendrick",  "Leofric",   "Mortimer",  "Nigel",     "Oswald",
        "Percy",     "Quentin",   "Roland",    "Seward",    "Thaddeus",
        "Ulric",     "Vance",     "Warren",    "Yorick",    "Zephyr",
        "Thomas",    "William",   "Robert",    "Henry",     "Edward",
        "Richard",   "George",    "Francis",   "Roger",     "Walter",
        "Hugh",      "Simon",     "Nicholas",  "Philip",    "Stephen",
        "Martin",    "Elias",     "Gideon",    "Horatio",   "Jerome",
        "Lysander",  "Merrick",   "Nathaniel", "Piers",     "Tobias",
        // --- classic medieval names (feminine) ---
        "Alice",     "Agnes",     "Beatrice",  "Catherine", "Dorothy",
        "Eleanor",   "Florence",  "Grace",     "Helen",     "Isabelle",
        "Joan",      "Katherine", "Lilian",    "Margaret",  "Nora",
        "Ophelia",   "Prudence",  "Rose",      "Sylvia",    "Tabitha",
        "Ursula",    "Violet",    "Winifred",  "Yolanda",   "Zelda",
        "Mabel",     "Hazel",     "Edith",     "Cecily",    "Matilda",
        "Evelyn",    "Constance", "Aveline",   "Bridget",   "Clara",
        "Della",     "Emma",      "Fern",      "Gemma",     "Harriet",
        "Iris",      "Juniper",   "Lydia",     "Miriam",    "Nell",
        "Opal",      "Petra",     "Quinn",     "Rowena",    "Serena"
    );

    /** Picks a random name from the pool. */
    public static String randomName(RandomSource random) {
        return NAMES.get(random.nextInt(NAMES.size()));
    }

    // ── Smith crafting states ─────────────────────────────────────────────────
    public static final int SMITH_IDLE     = 0;
    public static final int SMITH_AWAITING = 1;
    public static final int SMITH_CRAFTING = 2;
    public static final int SMITH_READY    = 3;

    // ── Smith state (active when job == BLACKSMITH) ───────────────────────────
    private final SimpleContainer smithInputContainer  = new SimpleContainer(9);
    private final SimpleContainer smithOutputContainer = new SimpleContainer(1);
    private int       smithCraftingState  = SMITH_IDLE;
    private int       smithCraftingTimer  = 0;
    @Nullable private ItemRequest smithCurrentRequest = null;
    @Nullable private SmithRecipe smithCurrentRecipe  = null;

    // ── Inventory ─────────────────────────────────────────────────────────────
    private final SimpleContainer workerInventory = new SimpleContainer(9);
    private final SimpleContainer toolContainer   = new SimpleContainer(1);

    // ── Mining state (transient — not persisted) ──────────────────────────────
    /**
     * Set to {@code true} by {@link village.automation.mod.entity.goal.MinerWorkGoal}
     * while the miner is standing at a floor block and actively animating.
     * Read by {@link village.automation.mod.blockentity.MineBlockEntity#serverTick}
     * to decide whether the output-drop timer should tick down.
     */
    private boolean miningActive = false;

    public void setMiningActive(boolean active) { this.miningActive = active; }
    public boolean isMiningActive()             { return miningActive; }

    // ── Chopping state (transient — not persisted) ────────────────────────────
    /**
     * Set to {@code true} by {@link village.automation.mod.entity.goal.LumberjackWorkGoal}
     * while the lumberjack is standing at a tree base and actively animating.
     * Read by {@link village.automation.mod.blockentity.LumbermillBlockEntity#serverTick}
     * to decide whether the chop timer should tick down.
     */
    private boolean choppingActive = false;

    public void setChoppingActive(boolean active) { this.choppingActive = active; }
    public boolean isChoppingActive()             { return choppingActive; }

    // ── Fishing state (transient — not persisted) ─────────────────────────────
    /**
     * Set to {@code true} by {@link village.automation.mod.entity.goal.FishermanWorkGoal}
     * while the fisherman is standing near water and actively animating.
     * Read by {@link village.automation.mod.blockentity.FishingBlockEntity#serverTick}
     * to decide whether the fish timer should tick down.
     */
    private boolean fishingActive = false;

    public void setFishingActive(boolean active) { this.fishingActive = active; }
    public boolean isFishingActive()             { return fishingActive; }

    // ── Employment (server-side authoritative) ────────────────────────────────
    /** Personal name without any job prefix (e.g. "Alice"). */
    private String baseName = "";
    /** Current job. */
    private JobType job = JobType.UNEMPLOYED;
    /** Position of the assigned workplace, or {@code null} when unemployed. */
    @Nullable
    private BlockPos workplacePos = null;

    // ── Constructor / attributes / goals ─────────────────────────────────────

    public VillagerWorkerEntity(EntityType<? extends VillagerWorkerEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return VillageNavigation.createOpenDoorsNavigation(this, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return AbstractVillager.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(0, new OpenNearbyDoorsGoal(this, true));
        this.goalSelector.addGoal(1, new WorkerSleepGoal(this));
        this.goalSelector.addGoal(2, new FetchFoodGoal(this));
        this.goalSelector.addGoal(2, new FarmerWorkGoal(this));
        this.goalSelector.addGoal(2, new MinerWorkGoal(this));
        this.goalSelector.addGoal(2, new SmithCraftGoal(this));
        this.goalSelector.addGoal(2, new SmelterWorkGoal(this));
        this.goalSelector.addGoal(2, new ChefWorkGoal(this));
        this.goalSelector.addGoal(2, new LumberjackWorkGoal(this));
        this.goalSelector.addGoal(2, new FishermanWorkGoal(this));
        this.goalSelector.addGoal(2, new AnimalKeeperWorkGoal(this));
        this.goalSelector.addGoal(3, new RandomStrollGoal(this, 0.6));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
    }

    // ── Food API ──────────────────────────────────────────────────────────────

    /** Current food level (0–{@value #MAX_FOOD}), safe to read on the client. */
    public int getFoodLevel() { return this.entityData.get(DATA_FOOD); }

    private void setFoodLevel(int level) {
        this.entityData.set(DATA_FOOD, Mth.clamp(level, 0, MAX_FOOD));
    }

    /**
     * Returns {@code true} when the worker is too hungry to perform job tasks.
     * Work goals should gate on this each tick.
     */
    public boolean isTooHungryToWork() { return getFoodLevel() < WORK_FOOD_THRESHOLD; }

    /** Returns {@code true} when the worker's food is low enough to warrant fetching more. */
    public boolean needsFood() { return getFoodLevel() < EAT_THRESHOLD; }

    // ── Server tick ───────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) return;
        tickFood();
    }

    /**
     * Drains food over time, eats from inventory when hungry, and applies
     * starvation damage when the food bar is empty.
     *
     * <ul>
     *   <li>Food does <em>not</em> drain while the worker is sleeping.</li>
     *   <li>The worker tries to eat whenever food falls below
     *       {@value #EAT_THRESHOLD} (80 %).</li>
     *   <li>At 0 food the worker takes 1 HP of starvation damage every
     *       {@value #STARVE_DAMAGE_INTERVAL} ticks.</li>
     * </ul>
     */
    private void tickFood() {
        // Sleeping workers don't burn calories
        if (this.getPose() != Pose.SLEEPING) {
            if (--foodDrainTimer <= 0) {
                foodDrainTimer = FOOD_DRAIN_INTERVAL;
                setFoodLevel(getFoodLevel() - 1);
            }
        }

        // Proactively eat when below the eat threshold
        if (getFoodLevel() < EAT_THRESHOLD) {
            if (--eatCheckTimer <= 0) {
                eatCheckTimer = EAT_CHECK_INTERVAL;
                tryEatFood();
            }
        }

        // Starvation damage when fully depleted
        if (getFoodLevel() <= 0) {
            if (--starvationTimer <= 0) {
                starvationTimer = STARVE_DAMAGE_INTERVAL;
                this.hurt(this.level().damageSources().starve(), 1.0f);
            }
        } else {
            starvationTimer = STARVE_DAMAGE_INTERVAL; // reset while fed
        }
    }

    /**
     * Searches the worker's 3×3 inventory for any food item and eats the first
     * one found.  Nutrition is read from the item's {@code FOOD} data component
     * so every vanilla food (and any modded food that follows the standard) is
     * supported automatically.
     */
    private void tryEatFood() {
        for (int i = 0; i < workerInventory.getContainerSize(); i++) {
            ItemStack stack = workerInventory.getItem(i);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null || food.nutrition() <= 0) continue;

            setFoodLevel(getFoodLevel() + food.nutrition());
            stack.shrink(1);
            workerInventory.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
            return; // eat one item per check
        }
    }

    // ── Employment API ────────────────────────────────────────────────────────

    /**
     * Sets the worker's personal (base) name and refreshes the display name.
     * Call once at spawn instead of {@link #setCustomName} directly.
     */
    public void setBaseName(String name) {
        this.baseName = name;
        refreshDisplayName();
    }

    public String getBaseName() { return baseName; }

    /** Server-side job field. Use {@link #getSyncedJob()} client-side. */
    public JobType getJob() { return job; }

    @Nullable
    public BlockPos getWorkplacePos() { return workplacePos; }

    /**
     * Returns the job synced to the client via {@code SynchedEntityData}.
     * Safe to call from the renderer.
     */
    public JobType getSyncedJob() {
        try {
            return JobType.valueOf(this.entityData.get(DATA_JOB));
        } catch (IllegalArgumentException e) {
            return JobType.UNEMPLOYED;
        }
    }

    /**
     * Assigns a job and workplace, updates the display name, and syncs the job
     * to all clients. Called server-side by the Village Heart ticker.
     */
    public void assign(JobType newJob, @Nullable BlockPos workplace) {
        this.job          = newJob;
        this.workplacePos = workplace;
        this.entityData.set(DATA_JOB, newJob.name());
        refreshDisplayName();
    }

    /**
     * Returns this worker to the unemployed pool so the heart can reassign
     * them. Resets the display name to the plain base name.
     */
    public void makeUnemployed() {
        this.job          = JobType.UNEMPLOYED;
        this.workplacePos = null;
        this.entityData.set(DATA_JOB, JobType.UNEMPLOYED.name());
        refreshDisplayName();
    }

    /** Rebuilds the display name as {@code "<prefix><baseName>"} (e.g. "Farmer Alice"). */
    private void refreshDisplayName() {
        if (!baseName.isEmpty()) {
            setCustomName(Component.literal(job.getDisplayPrefix() + baseName));
        }
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.isBaby() && !player.isSecondaryUseActive() && this.isAlive()) {
            if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
                serverPlayer.openMenu(
                        new SimpleMenuProvider(
                                (containerId, playerInv, p) -> new VillagerWorkerMenu(containerId, playerInv, this),
                                this.getDisplayName()
                        ),
                        buf -> buf.writeInt(this.getId())
                );
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        return InteractionResult.PASS;
    }

    // ── Equipment — wire MAINHAND through the tool container ─────────────────
    //
    // ServerEntity polls getItemBySlot() every tick for all equipment slots and
    // sends ClientboundSetEquipmentPacket when anything changes.  By returning
    // toolContainer's item here, the held tool is automatically synced to every
    // tracking client with no extra networking code needed.
    //
    // setItemSlot() is called client-side when that packet arrives; updating
    // toolContainer there keeps the worker GUI slot in sync on the client too.

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        if (slot == EquipmentSlot.MAINHAND) return toolContainer.getItem(0);
        return super.getItemBySlot(slot);
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        super.setItemSlot(slot, stack);
        if (slot == EquipmentSlot.MAINHAND
                && !ItemStack.matches(toolContainer.getItem(0), stack)) {
            // Sync the client-side container without triggering a recursive call
            toolContainer.setItem(0, stack.copy());
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public SimpleContainer getWorkerInventory()  { return workerInventory;  }
    public SimpleContainer getToolContainer()    { return toolContainer;    }

    // ── Smith API ─────────────────────────────────────────────────────────────
    public SimpleContainer getSmithInputContainer()  { return smithInputContainer;  }
    public SimpleContainer getSmithOutputContainer() { return smithOutputContainer; }
    public int  getSmithCraftingState()  { return smithCraftingState;  }
    public int  getSmithCraftingTimer()  { return smithCraftingTimer;  }
    @Nullable public ItemRequest getSmithCurrentRequest() { return smithCurrentRequest; }
    @Nullable public SmithRecipe getSmithCurrentRecipe()  { return smithCurrentRecipe;  }
    public void setSmithCraftingState(int s)          { smithCraftingState  = s; }
    public void setSmithCraftingTimer(int t)          { smithCraftingTimer  = t; }
    public void setSmithCurrentRequest(ItemRequest r) { smithCurrentRequest = r; }
    public void setSmithCurrentRecipe(SmithRecipe r)  { smithCurrentRecipe  = r; }
    public void tickSmithCraftingTimer() { if (smithCraftingTimer > 0) smithCraftingTimer--; }

    // ── NBT save / load ──────────────────────────────────────────────────────

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        // Employment
        tag.putString("BaseName", baseName);
        tag.putString("Job", job.name());
        if (workplacePos != null) {
            tag.putInt("WorkplaceX", workplacePos.getX());
            tag.putInt("WorkplaceY", workplacePos.getY());
            tag.putInt("WorkplaceZ", workplacePos.getZ());
        }

        // Inventory
        ListTag inventoryTag = new ListTag();
        for (int i = 0; i < this.workerInventory.getContainerSize(); i++) {
            ItemStack stack = this.workerInventory.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                slotTag.put("Item", stack.save(this.registryAccess()));
                inventoryTag.add(slotTag);
            }
        }
        tag.put("WorkerInventory", inventoryTag);

        ItemStack toolStack = this.toolContainer.getItem(0);
        if (!toolStack.isEmpty()) {
            tag.put("ToolSlot", toolStack.save(this.registryAccess()));
        }

        // Food
        tag.putInt("FoodLevel",     getFoodLevel());
        tag.putInt("FoodDrainTimer", foodDrainTimer);

        // Smith state
        tag.putInt("SmithCraftingState", smithCraftingState);
        tag.putInt("SmithCraftingTimer", smithCraftingTimer);
        if (smithCurrentRequest != null) tag.put("SmithRequest", smithCurrentRequest.save());
        if (smithCurrentRecipe  != null) tag.putString("SmithRecipeResult",
                net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(smithCurrentRecipe.result).toString());
        ListTag smithInputTag = new ListTag();
        for (int i = 0; i < smithInputContainer.getContainerSize(); i++) {
            ItemStack s = smithInputContainer.getItem(i);
            if (!s.isEmpty()) {
                CompoundTag st = new CompoundTag(); st.putByte("Slot", (byte) i);
                st.put("Item", s.save(this.registryAccess())); smithInputTag.add(st);
            }
        }
        tag.put("SmithInput", smithInputTag);
        ItemStack smithOut = smithOutputContainer.getItem(0);
        if (!smithOut.isEmpty()) tag.put("SmithOutput", smithOut.save(this.registryAccess()));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        // Employment
        this.baseName = tag.getString("BaseName");
        try {
            this.job = JobType.valueOf(tag.getString("Job"));
        } catch (IllegalArgumentException ignored) {
            this.job = JobType.UNEMPLOYED;
        }
        if (tag.contains("WorkplaceX")) {
            this.workplacePos = new BlockPos(
                    tag.getInt("WorkplaceX"),
                    tag.getInt("WorkplaceY"),
                    tag.getInt("WorkplaceZ"));
        } else {
            this.workplacePos = null;
        }
        // Restore synced data from loaded job so the renderer is correct after reload
        this.entityData.set(DATA_JOB, this.job.name());

        // Inventory
        if (tag.contains("WorkerInventory")) {
            ListTag inventoryTag = tag.getList("WorkerInventory", 10);
            for (int i = 0; i < inventoryTag.size(); i++) {
                CompoundTag slotTag = inventoryTag.getCompound(i);
                int slot = slotTag.getByte("Slot") & 255;
                if (slot < this.workerInventory.getContainerSize()) {
                    this.workerInventory.setItem(slot,
                            ItemStack.parseOptional(this.registryAccess(), slotTag.getCompound("Item")));
                }
            }
        }
        if (tag.contains("ToolSlot")) {
            this.toolContainer.setItem(0,
                    ItemStack.parseOptional(this.registryAccess(), tag.getCompound("ToolSlot")));
        }

        // Food
        setFoodLevel(tag.contains("FoodLevel") ? tag.getInt("FoodLevel") : MAX_FOOD);
        foodDrainTimer = tag.contains("FoodDrainTimer") ? tag.getInt("FoodDrainTimer") : FOOD_DRAIN_INTERVAL;

        // Smith state
        smithCraftingState = tag.getInt("SmithCraftingState");
        smithCraftingTimer = tag.getInt("SmithCraftingTimer");
        smithCurrentRequest = tag.contains("SmithRequest")
                ? ItemRequest.load(tag.getCompound("SmithRequest")) : null;
        if (tag.contains("SmithRecipeResult")) {
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraft.resources.ResourceLocation.parse(tag.getString("SmithRecipeResult"));
            smithCurrentRecipe = SmithRecipe.findFor(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl)
                            .orElse(net.minecraft.world.item.Items.AIR)).orElse(null);
        }
        smithInputContainer.clearContent();
        ListTag smithInputTag = tag.getList("SmithInput", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < smithInputTag.size(); i++) {
            CompoundTag st = smithInputTag.getCompound(i);
            int slot = st.getByte("Slot") & 255;
            if (slot < smithInputContainer.getContainerSize())
                smithInputContainer.setItem(slot,
                        ItemStack.parseOptional(this.registryAccess(), st.getCompound("Item")));
        }
        smithOutputContainer.clearContent();
        if (tag.contains("SmithOutput"))
            smithOutputContainer.setItem(0,
                    ItemStack.parseOptional(this.registryAccess(), tag.getCompound("SmithOutput")));
    }

    // ── Required Merchant / AgeableMob stubs ─────────────────────────────────

    /**
     * Returns {@code null} so the sleeping renderer skips its eye-height
     * translation step, which is designed for players and would shift the
     * visual model outside the bed for a villager-sized entity.
     * {@link village.automation.mod.entity.goal.WorkerSleepGoal} sets the
     * body yaw directly using the renderer's own sleepDirectionToRotation
     * mapping, so the orientation is still correct.
     */
    @Nullable
    @Override
    public Direction getBedOrientation() {
        return null;
    }

    @Nullable
    @Override
    public AbstractVillager getBreedOffspring(ServerLevel level, AgeableMob otherParent) { return null; }

    @Override public MerchantOffers getOffers()                       { return new MerchantOffers(); }
    @Override public void overrideOffers(MerchantOffers offers)       {}
    @Override public void notifyTrade(MerchantOffer offer)            {}
    @Override public void notifyTradeUpdated(ItemStack stack)         {}
    @Override public int  getVillagerXp()                             { return 0; }
    @Override public boolean showProgressBar()                        { return false; }
    @Override protected void rewardTradeXp(MerchantOffer offer)       {}
    @Override protected void updateTrades()                           {}
}
