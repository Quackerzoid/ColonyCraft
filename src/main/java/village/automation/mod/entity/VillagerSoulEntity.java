package village.automation.mod.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.VillageHeartBlockEntity;

import javax.annotation.Nullable;

public class VillagerSoulEntity extends PathfinderMob {

    @Nullable
    private CompoundTag storedVillagerData = null;

    public VillagerSoulEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.moveControl = new FlyingMoveControl(this, 20, true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 10.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FLYING_SPEED, 0.25);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new WaterAvoidingRandomFlyingGoal(this, 1.0));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 6.0f));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation nav = new FlyingPathNavigation(this, level);
        nav.setCanOpenDoors(false);
        nav.setCanFloat(true);
        nav.setCanPassDoors(true);
        return nav;
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (this.isControlledByLocalInstance()) {
            if (this.isInWater()) {
                this.moveRelative(0.02f, travelVector);
                this.move(MoverType.SELF, this.getDeltaMovement());
                this.setDeltaMovement(this.getDeltaMovement().scale(0.8));
            } else if (this.isInLava()) {
                this.moveRelative(0.02f, travelVector);
                this.move(MoverType.SELF, this.getDeltaMovement());
                this.setDeltaMovement(this.getDeltaMovement().scale(0.5));
            } else {
                this.moveRelative(this.getSpeed(), travelVector);
                this.move(MoverType.SELF, this.getDeltaMovement());
                this.setDeltaMovement(this.getDeltaMovement().scale(0.91f));
            }
        }
        this.calculateEntityAnimation(false);
    }

    // ── Soul data API ─────────────────────────────────────────────────────────

    public void setStoredVillagerData(CompoundTag data) {
        this.storedVillagerData = data.copy();
    }

    public boolean hasStoredVillager() {
        return storedVillagerData != null && !storedVillagerData.isEmpty();
    }

    // ── Resurrection on right-click with Totem of Undying ─────────────────────

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack heldItem = player.getItemInHand(hand);
        if (heldItem.is(Items.TOTEM_OF_UNDYING) && hasStoredVillager()) {
            if (!this.level().isClientSide() && this.level() instanceof ServerLevel serverLevel) {
                if (!player.getAbilities().instabuild) {
                    heldItem.shrink(1);
                }

                VillagerWorkerEntity newVillager = VillageMod.VILLAGER_WORKER.get().create(serverLevel);
                if (newVillager != null) {
                    newVillager.readAdditionalSaveData(storedVillagerData);

                    // Capture the restored name before clearing job state.
                    // readAdditionalSaveData sets baseName but never calls refreshDisplayName(),
                    // so we save it and re-apply it explicitly below.
                    String restoredName = newVillager.getBaseName();

                    // Make unemployed so the Village Heart can re-assign them.
                    // The workplace block entity had its assigned UUID cleared at death,
                    // so the worker must be UNEMPLOYED for the heart to reconnect them.
                    // preferredJob (restored from NBT) lets the heart prefer their old profession.
                    newVillager.makeUnemployed();

                    // Guarantee the name is visible regardless of how CustomName deserialized.
                    if (!restoredName.isEmpty()) {
                        newVillager.setBaseName(restoredName);
                    }

                    newVillager.setPos(this.getX(), this.getY(), this.getZ());
                    newVillager.setHealth(newVillager.getMaxHealth());
                    newVillager.setCustomNameVisible(true);
                    serverLevel.addFreshEntity(newVillager);

                    // Re-register with the nearest Village Heart whose territory
                    // contains the soul's position. countLiveWorkers() removes dead
                    // UUIDs each tick, so the original entry is already gone.
                    VillageHeartBlockEntity.findClaimingHeart(serverLevel, this.blockPosition(), null)
                            .ifPresent(heartPos -> {
                                BlockEntity be = serverLevel.getBlockEntity(heartPos);
                                if (be instanceof VillageHeartBlockEntity heart) {
                                    heart.registerWorker(newVillager.getUUID());
                                }
                            });

                    serverLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                            this.getX(), this.getY() + 1.0, this.getZ(),
                            30, 0.5, 0.5, 0.5, 0.5);
                    this.playSound(SoundEvents.TOTEM_USE, 1.0f, 1.0f);
                }

                this.discard();
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide());
        }
        return super.mobInteract(player, hand);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (storedVillagerData != null) {
            tag.put("StoredVillager", storedVillagerData);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("StoredVillager")) {
            storedVillagerData = tag.getCompound("StoredVillager");
        }
    }
}
