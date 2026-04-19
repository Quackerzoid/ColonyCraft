package village.automation.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsTargetGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.goal.OpenNearbyDoorsGoal;
import village.automation.mod.menu.SoulIronGolemMenu;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;

public class SoulIronGolemEntity extends IronGolem {

    // ── Synced data ───────────────────────────────────────────────────────────

    /** Human-readable status displayed above the head. Synced to clients. */
    private static final EntityDataAccessor<String> DATA_STATUS =
            SynchedEntityData.defineId(SoulIronGolemEntity.class, EntityDataSerializers.STRING);

    /** Whether the golem is currently in repair mode. Drives the model animation. */
    private static final EntityDataAccessor<Boolean> DATA_REPAIRING =
            SynchedEntityData.defineId(SoulIronGolemEntity.class, EntityDataSerializers.BOOLEAN);

    /** Whether this golem has been transformed into a Bell Guardian. */
    private static final EntityDataAccessor<Boolean> DATA_IS_BELL_GOLEM =
            SynchedEntityData.defineId(SoulIronGolemEntity.class, EntityDataSerializers.BOOLEAN);

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STATUS,       "Patrolling");
        builder.define(DATA_REPAIRING,    false);
        builder.define(DATA_IS_BELL_GOLEM, false);
    }

    public String  getStatus()              { return entityData.get(DATA_STATUS);    }
    public void    setStatus(String status) { entityData.set(DATA_STATUS, status);   }
    /** Client-readable; true while the repair animation should play. */
    public boolean isRepairing()            { return entityData.get(DATA_REPAIRING); }
    /** True if this golem has been converted into a Bell Guardian. */
    public boolean isBellGolem()           { return entityData.get(DATA_IS_BELL_GOLEM); }
    public void    setBellGolem(boolean v) { entityData.set(DATA_IS_BELL_GOLEM, v); }

    // ── Server-side repair state ──────────────────────────────────────────────

    /** Last tick on which the golem had a live combat target. */
    private int     lastCombatTick  = 0;
    /** True when the server has decided the golem should be repairing. */
    private boolean repairingState  = false;

    private static final int   COMBAT_COOLDOWN_TICKS = 200;   // 10 s
    private static final float ENTER_REPAIR_HP_RATIO  = 0.90f; // < 90 % max HP
    private static final float EXIT_REPAIR_HP_RATIO   = 1.00f; // fully healed

    // ── Heart link ────────────────────────────────────────────────────────────

    @Nullable private BlockPos linkedHeartPos;

    // Bell guardian state — stored on entity so the goal can persist across preemptions
    @Nullable BlockPos activeBellPos = null;
    int lastBellRingTick = Integer.MIN_VALUE / 2;

    // ── Constructor / attributes ──────────────────────────────────────────────

    public SoulIronGolemEntity(EntityType<? extends IronGolem> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return IronGolem.createAttributes();
    }

    // ── Goal registration ─────────────────────────────────────────────────────

    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return VillageNavigation.createOpenDoorsNavigation(this, level);
    }

    @Override
    protected void registerGoals() {
        // Do NOT call super — we intentionally omit the vanilla player-anger targeting goal.
        this.goalSelector.addGoal(0, new OpenNearbyDoorsGoal(this, true));
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, true));
        this.goalSelector.addGoal(3, new MoveTowardsTargetGoal(this, 0.9, 32.0f));
        // RepairGoal (priority 4): runs when repairing and no combat target;
        // higher-priority attack goals (2, 3) naturally interrupt it when a target appears.
        this.goalSelector.addGoal(4, new RepairGoal());
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.6, 0.0f));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6.0f));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // Never target creepers — an explosion inside the village would be bad.
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                this, Mob.class, 5, false, false,
                entity -> entity instanceof Enemy && !(entity instanceof Creeper)));
    }

    // ── Right-click → open GUI ────────────────────────────────────────────────

    /** HP restored per iron ingot (matches vanilla iron golem repair). */
    private static final float REPAIR_PER_INGOT = 25.0f;

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Crouch + iron ingot in hand → repair the golem without opening the UI
        if (player.isShiftKeyDown()) {
            ItemStack held = player.getItemInHand(hand);
            // Shift + Soul Bell → transform into Bell Guardian (one-time, consumes bell)
            if (held.is(VillageMod.SOUL_BELL.get())) {
                if (!this.level().isClientSide()) {
                    if (!isBellGolem()) {
                        setBellGolem(true);
                        if (!player.getAbilities().instabuild) held.shrink(1);
                        this.level().playSound(null,
                                this.getX(), this.getY(), this.getZ(),
                                net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                                this.getSoundSource(), 1.0f, 1.0f);
                        return InteractionResult.CONSUME;
                    }
                }
                return InteractionResult.sidedSuccess(this.level().isClientSide());
            }
            if (held.is(net.minecraft.world.item.Items.IRON_INGOT)) {
                if (!this.level().isClientSide()) {
                    float missing = this.getMaxHealth() - this.getHealth();
                    if (missing > 0) {
                        this.heal(REPAIR_PER_INGOT);
                        this.level().playSound(null,
                                this.getX(), this.getY(), this.getZ(),
                                net.minecraft.sounds.SoundEvents.IRON_GOLEM_REPAIR,
                                this.getSoundSource(), 1.0f, 1.0f);
                        if (!player.getAbilities().instabuild) {
                            held.shrink(1);
                        }
                        return InteractionResult.CONSUME;
                    }
                }
                return InteractionResult.sidedSuccess(this.level().isClientSide());
            }
        }

        // Any other right-click → open the status UI
        if (!this.level().isClientSide() && player instanceof ServerPlayer sp) {
            sp.openMenu(
                    new SimpleMenuProvider(
                            (id, inv, p) -> new SoulIronGolemMenu(id, inv, this),
                            this.getDisplayName()),
                    buf -> buf.writeInt(this.getId()));
            return InteractionResult.CONSUME;
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide());
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) {
            tickSoulFireParticles();
            return;
        }

        // ── Territory restriction ────────────────────────────────────────────
        // Skip when the bell goal has set a tighter restriction around the bell.
        if (linkedHeartPos != null && activeBellPos == null) {
            BlockEntity be = this.level().getBlockEntity(linkedHeartPos);
            if (be instanceof VillageHeartBlockEntity heart) {
                this.restrictTo(linkedHeartPos, heart.getRadius());
            } else {
                linkedHeartPos = null;
            }
        }

        // ── Repair state machine ──────────────────────────────────────────────
        LivingEntity target = this.getTarget();
        boolean inCombat = target != null && target.isAlive();

        if (inCombat) {
            lastCombatTick = this.tickCount;
        }

        float hp    = this.getHealth();
        float maxHp = (float) this.getAttributeValue(Attributes.MAX_HEALTH);

        if (!repairingState) {
            // Enter repair: low HP and no combat for 10 s
            if (hp < maxHp * ENTER_REPAIR_HP_RATIO
                    && (this.tickCount - lastCombatTick) > COMBAT_COOLDOWN_TICKS) {
                repairingState = true;
            }
        } else {
            // Exit repair: fully recovered, OR a combat target appeared
            if (hp >= maxHp * EXIT_REPAIR_HP_RATIO || inCombat) {
                repairingState = false;
            }
        }

        // Push synced flag to clients only when it actually changes
        if (repairingState != entityData.get(DATA_REPAIRING)) {
            entityData.set(DATA_REPAIRING, repairingState);
        }

        // ── Status label ─────────────────────────────────────────────────────
        String newStatus;
        if (inCombat) {
            newStatus = "Attacking: " + target.getType().getDescription().getString();
        } else if (repairingState) {
            newStatus = "Repairing";
        } else if (activeBellPos != null) {
            newStatus = "Bell Guarding";
        } else {
            newStatus = "Patrolling";
        }
        if (!newStatus.equals(getStatus())) setStatus(newStatus);

        // ── Bell Guardian behaviour ───────────────────────────────────────────
        if (isBellGolem()) tickBellGuardian(inCombat);
    }

    // ── Particles (client only) ───────────────────────────────────────────────

    /**
     * Soul-fire particles scaled to the iron golem's 1.4 × 2.7 bounding box.
     * Mirrors the courier's implementation exactly.
     */
    private void tickSoulFireParticles() {
        var    rng    = this.getRandom();
        double hw     = this.getBbWidth() * 0.5;   // 0.7
        double h      = this.getBbHeight();         // 2.7
        boolean moving = !this.getNavigation().isDone();
        int     period = moving ? 2 : 3;

        if (this.tickCount % period == 0) {
            this.level().addParticle(
                    ParticleTypes.SOUL_FIRE_FLAME,
                    this.getX() + (rng.nextDouble() - 0.5) * hw * 2,
                    this.getY() + rng.nextDouble() * h * 0.45,
                    this.getZ() + (rng.nextDouble() - 0.5) * hw * 2,
                    (rng.nextDouble() - 0.5) * 0.02,
                    rng.nextDouble() * 0.04 + 0.02,
                    (rng.nextDouble() - 0.5) * 0.02);
        }

        if (this.tickCount % 40 == 0) {
            this.level().addParticle(
                    ParticleTypes.SOUL,
                    this.getX() + (rng.nextDouble() - 0.5) * hw * 2,
                    this.getY() + rng.nextDouble() * 0.3,
                    this.getZ() + (rng.nextDouble() - 0.5) * hw * 2,
                    0.0, 0.08, 0.0);
        }
    }

    // ── Heart link accessors ──────────────────────────────────────────────────

    public void linkToHeart(BlockPos pos) { this.linkedHeartPos = pos; }

    @Nullable
    public BlockPos getLinkedHeartPos()   { return linkedHeartPos; }

    @Override
    public boolean isPersistenceRequired() { return true; }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (linkedHeartPos != null) {
            tag.putInt("HeartX", linkedHeartPos.getX());
            tag.putInt("HeartY", linkedHeartPos.getY());
            tag.putInt("HeartZ", linkedHeartPos.getZ());
        }
        tag.putBoolean("IsBellGolem", isBellGolem());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("HeartX") && tag.contains("HeartY") && tag.contains("HeartZ")) {
            linkedHeartPos = new BlockPos(
                    tag.getInt("HeartX"), tag.getInt("HeartY"), tag.getInt("HeartZ"));
        }
        if (tag.contains("IsBellGolem")) {
            setBellGolem(tag.getBoolean("IsBellGolem"));
        }
    }

    // ── Bell Guardian behaviour (runs from tick, not a Goal) ─────────────────

    private static final int BELL_RING_INTERVAL = 400;  // 20 s between rings
    private static final int BELL_ARRIVE_DIST_SQ = 16;  // 4 blocks to count as "at bell"

    /**
     * Called every server tick when this golem is a Bell Guardian.
     * Finds the nearest bell block, walks to it when any golem in the village is
     * in combat, rings it on arrival and every 20 s, then fights within 10 blocks.
     * Runs AFTER super.tick() so our moveTo() call comes last and is not overwritten
     * by WaterAvoidingRandomStrollGoal within the same tick.
     */
    private void tickBellGuardian(boolean selfInCombat) {
        boolean combatDetected = selfInCombat || anyGolemInCombat();

        if (!combatDetected) {
            // Combat is over — wait 30 s after last ring then return to normal patrol
            if (activeBellPos != null && (this.tickCount - lastBellRingTick) > 600) {
                activeBellPos = null;
                lastBellRingTick = Integer.MIN_VALUE / 2;
                if (linkedHeartPos != null) {
                    BlockEntity be = this.level().getBlockEntity(linkedHeartPos);
                    if (be instanceof VillageHeartBlockEntity heart) {
                        this.restrictTo(linkedHeartPos, heart.getRadius());
                    }
                }
            }
            return;
        }

        // Combat detected — find the bell if not already known
        if (activeBellPos == null) {
            activeBellPos = findNearestBell();
            if (activeBellPos == null) return;
        }

        double distSq = this.distanceToSqr(
                activeBellPos.getX() + 0.5, activeBellPos.getY(), activeBellPos.getZ() + 0.5);

        if (distSq > BELL_ARRIVE_DIST_SQ) {
            // Navigate toward bell — only when not personally fighting and not already on the way.
            // We call moveTo() here (after super.tick()) so it overwrites any stroll path.
            if (!selfInCombat && !isNavigatingTo(activeBellPos)) {
                this.getNavigation().moveTo(
                        activeBellPos.getX() + 0.5, activeBellPos.getY(),
                        activeBellPos.getZ() + 0.5, 1.0);
            }
        } else {
            // At the bell: restrict combat zone and ring periodically
            this.restrictTo(activeBellPos, 10);
            if ((this.tickCount - lastBellRingTick) >= BELL_RING_INTERVAL) {
                ringBell();
                lastBellRingTick = this.tickCount;
            }
        }
    }

    /** Returns true if the current navigation path is already heading to {@code target}. */
    private boolean isNavigatingTo(BlockPos target) {
        net.minecraft.world.level.pathfinder.Path path = this.getNavigation().getPath();
        if (path == null) return false;
        BlockPos dest = path.getTarget();
        return dest != null && dest.closerThan(target, 2.0);
    }

    private void ringBell() {
        if (activeBellPos == null) return;
        BlockState bs = this.level().getBlockState(activeBellPos);
        if (bs.is(Blocks.BELL) && bs.getBlock() instanceof BellBlock bb) {
            bb.attemptToRing(this, this.level(), activeBellPos, null);
        }
    }

    @Nullable
    private BlockPos findNearestBell() {
        if (!(this.level() instanceof ServerLevel)) return null;
        BlockEntity hbe = this.level().getBlockEntity(linkedHeartPos);
        if (!(hbe instanceof VillageHeartBlockEntity heart)) return null;
        int r = Math.min(heart.getRadius(), 64);
        BlockPos origin = this.blockPosition();
        return BlockPos.betweenClosedStream(origin.offset(-r, -8, -r), origin.offset(r, 8, r))
                .filter(p -> this.level().getBlockState(p).is(Blocks.BELL))
                .min(Comparator.comparingDouble(p -> p.distSqr(origin)))
                .map(BlockPos::immutable)
                .orElse(null);
    }

    /** Spatial scan: true if any SoulIronGolemEntity within the village radius has the
     *  "Attacking" status — more reliable than checking getTarget() across entity refs. */
    private boolean anyGolemInCombat() {
        if (!(this.level() instanceof ServerLevel sl) || linkedHeartPos == null) return false;
        BlockEntity be = sl.getBlockEntity(linkedHeartPos);
        int r = be instanceof VillageHeartBlockEntity heart ? heart.getRadius() : 64;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                linkedHeartPos.getX() - r, linkedHeartPos.getY() - 32, linkedHeartPos.getZ() - r,
                linkedHeartPos.getX() + r, linkedHeartPos.getY() + 32, linkedHeartPos.getZ() + r);
        return !sl.getEntitiesOfClass(SoulIronGolemEntity.class, box,
                g -> g.isAlive() && g.getStatus().startsWith("Attacking")).isEmpty();
    }

    // ── Inner goal: Repairing ─────────────────────────────────────────────────

    /**
     * Active while the golem is in repair mode AND has no combat target.
     *
     * <ul>
     *   <li>Stops all navigation so the golem stands still.</li>
     *   <li>Heals 2 HP every 2 s (40 ticks).</li>
     *   <li>Automatically suspended the moment a target is acquired
     *       (higher-priority {@link MeleeAttackGoal} takes over).</li>
     *   <li>Resumes after combat if HP is still below 50 %.</li>
     * </ul>
     */
    private class RepairGoal extends Goal {

        RepairGoal() {
            // Claim MOVE so wandering/stroll goals don't fight us; LOOK so the
            // golem's gaze is controlled by the slump animation rather than logic.
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return repairingState && SoulIronGolemEntity.this.getTarget() == null;
        }

        @Override
        public boolean canContinueToUse() {
            // Stop the moment a target is acquired — attack goals will take over.
            return repairingState && SoulIronGolemEntity.this.getTarget() == null;
        }

        @Override
        public void start() {
            SoulIronGolemEntity.this.getNavigation().stop();
        }

        @Override
        public void tick() {
            // Keep perfectly still — healing only comes from iron deliveries
            // by soul copper golems, or from a player crouching with an iron ingot.
            SoulIronGolemEntity.this.getNavigation().stop();
        }
    }
}
