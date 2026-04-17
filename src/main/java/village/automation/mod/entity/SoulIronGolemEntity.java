package village.automation.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;

import javax.annotation.Nullable;

public class SoulIronGolemEntity extends IronGolem {

    @Nullable
    private BlockPos linkedHeartPos;

    public SoulIronGolemEntity(EntityType<? extends IronGolem> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return IronGolem.createAttributes();
    }

    @Override
    protected void registerGoals() {
        // Do not call super — we intentionally skip the vanilla player-targeting goal.
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, true));
        this.goalSelector.addGoal(3, new MoveTowardsTargetGoal(this, 0.9, 32.0f));
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.6, 0.0f));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6.0f));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // Attack enemies, but never target creepers (explosion would grief the village)
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                this, Mob.class, 5, false, false,
                entity -> entity instanceof Enemy && !(entity instanceof Creeper)));
    }

    // ── Heart link ────────────────────────────────────────────────────────────

    public void linkToHeart(BlockPos pos) {
        this.linkedHeartPos = pos;
    }

    @Nullable
    public BlockPos getLinkedHeartPos() {
        return linkedHeartPos;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide && linkedHeartPos != null) {
            BlockEntity be = this.level().getBlockEntity(linkedHeartPos);
            if (be instanceof VillageHeartBlockEntity heart) {
                // Keep the golem confined to the heart's territory
                this.restrictTo(linkedHeartPos, heart.getRadius());
            } else {
                // Heart was removed — detach so the golem can roam freely
                linkedHeartPos = null;
            }
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (linkedHeartPos != null) {
            tag.putInt("HeartX", linkedHeartPos.getX());
            tag.putInt("HeartY", linkedHeartPos.getY());
            tag.putInt("HeartZ", linkedHeartPos.getZ());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("HeartX") && tag.contains("HeartY") && tag.contains("HeartZ")) {
            linkedHeartPos = new BlockPos(
                    tag.getInt("HeartX"),
                    tag.getInt("HeartY"),
                    tag.getInt("HeartZ"));
        }
    }
}
