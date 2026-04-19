package village.automation.mod.client.renderer;

import net.minecraft.client.model.IronGolemModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import village.automation.mod.entity.SoulIronGolemEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Extends the vanilla {@link IronGolemModel} to add a "repair" animation.
 *
 * <p>When {@link SoulIronGolemEntity#isRepairing()} is {@code true} the model
 * smoothly eases into a slumped-forward pose:
 * <ul>
 *   <li>Body tilts ~32° forward</li>
 *   <li>Head droops an additional ~25° relative to the tilted body</li>
 *   <li>Both arms hang forward and downward</li>
 * </ul>
 * The transition takes roughly 0.5 s in either direction.
 *
 * <p>Progress is tracked per entity-ID so multiple golems in the world each
 * animate independently from the single shared model instance.
 */
public class SoulIronGolemModel<T extends SoulIronGolemEntity> extends IronGolemModel<T> {

    // Target rotations (radians) for the fully-repaired slump pose
    private static final float BODY_TILT      =  0.38f;   //  ~22° — forward lean without toppling
    private static final float HEAD_DROOP     =  1.10f;   //  ~63° — pronounced downward head droop
    /**
     * Negative value rotates the arms backward, so that when the body is
     * tilted forward the arms hang straight down toward the floor rather than
     * swinging up/forward.  Roughly −BODY_TILT keeps them vertical in world
     * space; a bit extra lets them dangle naturally.
     */
    private static final float ARM_HANG       = -0.35f;

    /** Ease-in speed per frame (≈0.10 → smooth ~30-frame entry). */
    private static final float EASE_RATE      = 0.10f;

    // Re-grab the parts from root so we can modify them after super.setupAnim()
    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart rightArm;
    private final ModelPart leftArm;

    /** Per-entity-ID animation progress in [0, 1]. */
    private final Map<Integer, Float> progressMap = new HashMap<>();

    public SoulIronGolemModel(ModelPart root) {
        super(root);
        this.head     = root.getChild("head");
        this.body     = root.getChild("body");
        this.rightArm = root.getChild("right_arm");
        this.leftArm  = root.getChild("left_arm");
    }

    public ModelPart getHead() { return head; }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // Let vanilla handle walk/attack/hurt animations first.
        // IMPORTANT: do NOT pre-zero arm xRot here — vanilla uses direct (=)
        // assignment for arm xRot on every frame (attack branch and idle branch),
        // so it always produces the correct value.  Pre-zeroing it before super
        // interferes with NeoForge's animation accumulation and breaks attack poses.
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        int id = entity.getId();

        if (!entity.isRepairing()) {
            progressMap.remove(id);
            // Always explicitly reset parts that vanilla never writes.
            // The model is a shared instance — a repairing golem sets body.xRot
            // to the tilt value, and the next non-repairing golem rendered in the
            // same frame would inherit that stale value if we don't clear it here.
            body.xRot     = 0f;
            rightArm.zRot = 0f;
            leftArm.zRot  = 0f;
            return;
        }

        // Ease-in toward the slump pose while repairing
        float progress = progressMap.getOrDefault(id, 0f);
        progress = progress + (1f - progress) * EASE_RATE;
        progressMap.put(id, progress);

        if (progress < 0.001f) return;   // nothing to blend yet

        // Capture what vanilla just set so we can lerp from it
        float bodyX  = body.xRot;
        float headX  = head.xRot;
        float rArmX  = rightArm.xRot;
        float lArmX  = leftArm.xRot;

        body.xRot     = Mth.lerp(progress, bodyX,  BODY_TILT);
        head.xRot     = Mth.lerp(progress, headX,  HEAD_DROOP);
        rightArm.xRot = Mth.lerp(progress, rArmX,  ARM_HANG);
        leftArm.xRot  = Mth.lerp(progress, lArmX,  ARM_HANG);

        // Freeze limb swing while slumped so legs don't clip through the tilted body
        if (progress > 0.5f) {
            float blend = (progress - 0.5f) * 2f;   // 0→1 over the second half of the ease
            body.yRot    = Mth.lerp(blend, body.yRot,    0f);
            head.yRot    = Mth.lerp(blend, head.yRot,    0f);
            rightArm.zRot = Mth.lerp(blend, rightArm.zRot, 0f);
            leftArm.zRot  = Mth.lerp(blend, leftArm.zRot,  0f);
        }
    }
}
