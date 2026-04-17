package village.automation.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.goal.CourierGoal;
import village.automation.mod.entity.goal.OpenNearbyDoorsGoal;
import village.automation.mod.menu.CourierMenu;

import javax.annotation.Nullable;

/**
 * Courier entity — a small golem that ferries materials from village chests to
 * the smith and delivers finished tools to requesting workers.
 *
 * <p>Appearance: rendered using a compact custom model ({@code CourierModel})
 * with a copper-tinted texture — the conceptual "copper golem" stand-in.
 */
public class CourierEntity extends PathfinderMob {

    private static final EntityDataAccessor<Boolean> DATA_USING_CHEST =
            SynchedEntityData.defineId(CourierEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<ItemStack> DATA_DISPLAY_ITEM =
            SynchedEntityData.defineId(CourierEntity.class, EntityDataSerializers.ITEM_STACK);

    private static final EntityDataAccessor<String> DATA_TASK =
            SynchedEntityData.defineId(CourierEntity.class, EntityDataSerializers.STRING);

    /** True once the courier has been upgraded with a Soul Eye. Synced so the renderer can swap the texture. */
    private static final EntityDataAccessor<Boolean> DATA_ENDER =
            SynchedEntityData.defineId(CourierEntity.class, EntityDataSerializers.BOOLEAN);

    /** Remaining ticks until the next ender teleport, synced to clients for the GUI progress bar. */
    private static final EntityDataAccessor<Integer> DATA_ENDER_COOLDOWN =
            SynchedEntityData.defineId(CourierEntity.class, EntityDataSerializers.INT);

    /** Ticks until the next ender teleport. Server-side authoritative value. */
    private int enderTeleportCooldown = ENDER_TELEPORT_INTERVAL;

    @Nullable
    private BlockPos linkedHeartPos = null;

    /** Small carried-item inventory used during fetch/deliver trips. */
    private final SimpleContainer carriedInventory = new SimpleContainer(9);

    // ── Constructor / attributes ──────────────────────────────────────────────

    public CourierEntity(EntityType<? extends CourierEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_USING_CHEST, false);
        builder.define(DATA_DISPLAY_ITEM, ItemStack.EMPTY);
        builder.define(DATA_TASK, "Idle");
        builder.define(DATA_ENDER, false);
        builder.define(DATA_ENDER_COOLDOWN, ENDER_TELEPORT_INTERVAL);
    }

    public boolean isUsingChest() { return entityData.get(DATA_USING_CHEST); }
    public void setUsingChest(boolean v) { entityData.set(DATA_USING_CHEST, v); }

    /** The first carried item, synced to the client for rendering. */
    public ItemStack getDisplayItem() { return entityData.get(DATA_DISPLAY_ITEM); }

    /** Human-readable description of the courier's current task, synced for the GUI. */
    public String getCurrentTask() { return entityData.get(DATA_TASK); }
    public void setCurrentTask(String task) { entityData.set(DATA_TASK, task); }

    /** True when the courier has been upgraded into the Ender Soul Copper Golem variant. */
    public boolean isEnderVariant() { return entityData.get(DATA_ENDER); }
    public void setEnderVariant(boolean v) { entityData.set(DATA_ENDER, v); }

    /** Remaining ticks until the next ender teleport (client-readable). */
    public int getEnderTeleportCooldown() { return entityData.get(DATA_ENDER_COOLDOWN); }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return isEnderVariant()
                ? net.minecraft.network.chat.Component.translatable("entity.colonycraft.courier_ender")
                : super.getDisplayName();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.24)   // 40 % of original 0.6
                .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return VillageNavigation.createOpenDoorsNavigation(this, level);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(0, new OpenNearbyDoorsGoal(this, true));
        this.goalSelector.addGoal(1, new CourierGoal(this));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 6.0f));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Crouch + Soul Eye → upgrade to Ender Soul Copper Golem
        if (player.isShiftKeyDown()) {
            ItemStack held = player.getItemInHand(hand);
            if (held.is(VillageMod.SOUL_EYE.get())) {
                if (!this.level().isClientSide()) {
                    if (!isEnderVariant()) {
                        setEnderVariant(true);
                        if (!player.getAbilities().instabuild) held.shrink(1);
                        this.level().playSound(null,
                                this.getX(), this.getY(), this.getZ(),
                                SoundEvents.ENDERMAN_TELEPORT,
                                this.getSoundSource(), 1.0f, 1.0f);
                    }
                }
                return InteractionResult.sidedSuccess(this.level().isClientSide());
            }
        }

        // Normal right-click → open status UI
        if (!this.level().isClientSide() && player instanceof ServerPlayer sp) {
            sp.openMenu(
                    new SimpleMenuProvider(
                            (id, inv, p) -> new CourierMenu(id, inv, this),
                            this.getDisplayName()),
                    buf -> buf.writeInt(this.getId()));
            return InteractionResult.CONSUME;
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide());
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    /** Ticks between ender teleports (40 s). Public so the GUI can compute bar progress. */
    public static final int ENDER_TELEPORT_INTERVAL = 800;

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            // Particles are purely visual — only spawn on the client side.
            tickSoulFireParticles();
        } else {
            // Keep DATA_DISPLAY_ITEM in sync so the renderer can read it on the client.
            ItemStack display = ItemStack.EMPTY;
            for (int i = 0; i < carriedInventory.getContainerSize(); i++) {
                ItemStack s = carriedInventory.getItem(i);
                if (!s.isEmpty()) { display = s; break; }
            }
            if (!ItemStack.matches(entityData.get(DATA_DISPLAY_ITEM), display)) {
                entityData.set(DATA_DISPLAY_ITEM, display.copy());
            }

            // Ender variant: teleport to the current navigation destination every 40 s.
            if (isEnderVariant() && this.level() instanceof ServerLevel serverLevel) {
                if (--enderTeleportCooldown <= 0) {
                    Path path = this.getNavigation().getPath();
                    boolean hastarget = path != null && !this.getNavigation().isDone()
                            && path.getEndNode() != null;

                    if (hastarget) {
                        Node end = path.getEndNode();
                        // Portal burst at departure position
                        serverLevel.sendParticles(ParticleTypes.PORTAL,
                                this.getX(), this.getY() + 0.5, this.getZ(),
                                20, 0.3, 0.5, 0.3, 0.1);
                        this.teleportTo(end.x + 0.5, end.y, end.z + 0.5);
                        this.getNavigation().stop();
                        // Portal burst at arrival position
                        serverLevel.sendParticles(ParticleTypes.PORTAL,
                                this.getX(), this.getY() + 0.5, this.getZ(),
                                20, 0.3, 0.5, 0.3, 0.1);
                        serverLevel.playSound(null,
                                this.getX(), this.getY(), this.getZ(),
                                SoundEvents.ENDERMAN_TELEPORT,
                                this.getSoundSource(), 0.6f, 1.0f);
                    }

                    // Always reset so the cooldown bar keeps cycling
                    enderTeleportCooldown = ENDER_TELEPORT_INTERVAL;
                }

                // Keep synced data in step for the GUI progress bar
                if (entityData.get(DATA_ENDER_COOLDOWN) != enderTeleportCooldown) {
                    entityData.set(DATA_ENDER_COOLDOWN, enderTeleportCooldown);
                }
            }
        }
    }

    /**
     * Emits soul-fire particles around the courier's body every tick.
     *
     * <ul>
     *   <li>{@link ParticleTypes#SOUL_FIRE_FLAME} — 1 particle at rest,
     *       2 while moving — drift upward from the lower body.</li>
     *   <li>{@link ParticleTypes#SOUL} — 1 larger soul particle every
     *       20 ticks (once per second) near the feet.</li>
     * </ul>
     */
    private void tickSoulFireParticles() {
        var rng    = this.getRandom();
        double hw  = this.getBbWidth() * 0.5;   // half-width  = 0.3 for the courier
        double h   = this.getBbHeight();         // full height = 1.4

        // One flame every 3 ticks at rest; every 2 ticks while moving.
        boolean moving  = !this.getNavigation().isDone();
        int     period  = moving ? 2 : 3;

        if (this.tickCount % period == 0) {
            // Ender variant emits reverse-portal (purple/violet) wisps instead of soul fire.
            var flameType = isEnderVariant() ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.SOUL_FIRE_FLAME;
            this.level().addParticle(
                    flameType,
                    this.getX() + (rng.nextDouble() - 0.5) * hw * 2,
                    this.getY() + rng.nextDouble() * h * 0.45,
                    this.getZ() + (rng.nextDouble() - 0.5) * hw * 2,
                    (rng.nextDouble() - 0.5) * 0.02,
                    rng.nextDouble() * 0.04 + 0.02,
                    (rng.nextDouble() - 0.5) * 0.02);
        }

        // One large soul/portal particle every 2 seconds — rises from the feet.
        if (this.tickCount % 40 == 0) {
            var soulType = isEnderVariant() ? ParticleTypes.PORTAL : ParticleTypes.SOUL;
            this.level().addParticle(
                    soulType,
                    this.getX() + (rng.nextDouble() - 0.5) * hw * 2,
                    this.getY() + rng.nextDouble() * 0.2,
                    this.getZ() + (rng.nextDouble() - 0.5) * hw * 2,
                    0.0, 0.08, 0.0);
        }
    }

    // ── Heart link ────────────────────────────────────────────────────────────

    @Nullable
    public BlockPos getLinkedHeartPos() { return linkedHeartPos; }

    public void setLinkedHeartPos(BlockPos pos) {
        this.linkedHeartPos = pos;
        if (pos != null) this.restrictTo(pos, 96); // wide range: courier needs to roam
    }

    // ── Carried inventory ─────────────────────────────────────────────────────

    public SimpleContainer getCarriedInventory() { return carriedInventory; }

    public boolean isCarryingAnything() {
        // On the client the carried inventory is not synced — use the synced display item instead.
        if (this.level().isClientSide()) {
            return !getDisplayItem().isEmpty();
        }
        for (int i = 0; i < carriedInventory.getContainerSize(); i++) {
            if (!carriedInventory.getItem(i).isEmpty()) return true;
        }
        return false;
    }

    public void clearCarried() { carriedInventory.clearContent(); }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (linkedHeartPos != null) {
            tag.putInt("HeartX", linkedHeartPos.getX());
            tag.putInt("HeartY", linkedHeartPos.getY());
            tag.putInt("HeartZ", linkedHeartPos.getZ());
        }
        tag.putBoolean("IsEnder", isEnderVariant());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("HeartX")) {
            linkedHeartPos = new BlockPos(tag.getInt("HeartX"), tag.getInt("HeartY"), tag.getInt("HeartZ"));
            this.restrictTo(linkedHeartPos, 96);
        }
        if (tag.contains("IsEnder")) {
            setEnderVariant(tag.getBoolean("IsEnder"));
        }
    }
}
