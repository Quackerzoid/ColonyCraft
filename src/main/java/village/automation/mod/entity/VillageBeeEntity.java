package village.automation.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import village.automation.mod.blockentity.BeekeeperBlockEntity;
import village.automation.mod.entity.goal.VillageBeeGoal;

import javax.annotation.Nullable;

/**
 * A domesticated bee created when the beekeeper claims a wild {@link Bee}.
 *
 * <p>All vanilla bee AI is stripped out.  This entity runs only
 * {@link VillageBeeGoal}, which drives a four-phase pollination loop:
 * seek a nearby crop → hover & pollinate → fly home → rest and deposit pollen.
 *
 * <p>Two extra positions are persisted:
 * <ul>
 *   <li>{@link #homePos} — the {@link BeekeeperBlockEntity} that owns this bee.</li>
 *   <li>{@link #heartPos} — the village heart whose radius sets the search area.</li>
 * </ul>
 */
public class VillageBeeEntity extends Bee {

    @Nullable private BlockPos homePos;
    @Nullable private BlockPos heartPos;

    public VillageBeeEntity(EntityType<? extends VillageBeeEntity> type, Level level) {
        super(type, level);
    }

    // ── AI ────────────────────────────────────────────────────────────────────

    /**
     * Replaces all vanilla bee goals with our single pollination goal.
     *
     * <p>We <em>must</em> call {@code super.registerGoals()} first: the vanilla
     * {@code Bee} implementation assigns private fields (e.g. {@code beePollinateGoal},
     * {@code beeAttackGoal}) inline as it registers each goal.  Those fields are later
     * read by other {@code Bee} methods during ticking; skipping the super call leaves
     * them {@code null} and causes a {@link NullPointerException}.
     *
     * <p>After the super call we clear both selectors and register only our goals,
     * so none of the vanilla bee behaviours actually run.
     */
    @Override
    protected void registerGoals() {
        // Initialize Bee's private goal-reference fields (beePollinateGoal, beeAttackGoal, …)
        super.registerGoals();

        // Wipe everything super just registered
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();

        // Our lean goal set
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new VillageBeeGoal(this));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6.0f));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        // No target selector entries — village bees are peaceful
    }

    // ── Home / heart accessors ────────────────────────────────────────────────

    public void setHomePos(BlockPos pos) { this.homePos = pos.immutable(); }
    @Nullable public BlockPos getHomePos() { return homePos; }

    public void setHeartPos(BlockPos pos) { this.heartPos = pos.immutable(); }
    @Nullable public BlockPos getHeartPos() { return heartPos; }

    // ── Pollen deposit ────────────────────────────────────────────────────────

    /**
     * Called by {@link VillageBeeGoal} when the bee reaches the home block after
     * a pollination run.  Increments the block entity's pollen counter.
     */
    public void depositPollen(ServerLevel level) {
        if (homePos == null) return;
        var be = level.getBlockEntity(homePos);
        if (be instanceof BeekeeperBlockEntity bk) {
            bk.depositPollen(this.getUUID());
        }
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (homePos != null) {
            tag.putInt("VBHomeX", homePos.getX());
            tag.putInt("VBHomeY", homePos.getY());
            tag.putInt("VBHomeZ", homePos.getZ());
        }
        if (heartPos != null) {
            tag.putInt("VBHeartX", heartPos.getX());
            tag.putInt("VBHeartY", heartPos.getY());
            tag.putInt("VBHeartZ", heartPos.getZ());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("VBHomeX")) {
            homePos = new BlockPos(
                    tag.getInt("VBHomeX"), tag.getInt("VBHomeY"), tag.getInt("VBHomeZ"));
        }
        if (tag.contains("VBHeartX")) {
            heartPos = new BlockPos(
                    tag.getInt("VBHeartX"), tag.getInt("VBHeartY"), tag.getInt("VBHeartZ"));
        }
    }
}
