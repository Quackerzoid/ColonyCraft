package village.automation.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.goal.CourierGoal;

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
    }

    public boolean isUsingChest() { return entityData.get(DATA_USING_CHEST); }
    public void setUsingChest(boolean v) { entityData.set(DATA_USING_CHEST, v); }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.6)
                .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new CourierGoal(this));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 6.0f));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
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
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("HeartX")) {
            linkedHeartPos = new BlockPos(tag.getInt("HeartX"), tag.getInt("HeartY"), tag.getInt("HeartZ"));
            this.restrictTo(linkedHeartPos, 96);
        }
    }
}
