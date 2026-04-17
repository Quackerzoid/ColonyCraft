package village.automation.mod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;
import village.automation.mod.entity.CourierEntity;

/**
 * Faithful port of the 1.21.4 CopperGolemModel geometry to the 1.21.1 API.
 * UV layout matches copper_golem.png exactly (64×64).
 *
 * Hierarchy:
 *   root
 *     body  (offset 0,19,0)
 *       head      (offset 0,-6,0)
 *       right_arm (offset -4,-6,0)
 *       left_arm  (offset  4,-6,0)
 *     right_leg (offset 0,19,0)
 *     left_leg  (offset 0,19,0)
 */
public class CourierModel<T extends CourierEntity> extends EntityModel<T> {

    private final ModelPart head;
    // body and arms are package-accessible so CourierItemLayer can traverse into them.
    final ModelPart body;
    final ModelPart rightArm;
    final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    public CourierModel(ModelPart root) {
        ModelPart bodyPart = root.getChild("body");
        this.body     = bodyPart;
        this.head     = bodyPart.getChild("head");
        this.rightArm = bodyPart.getChild("right_arm");
        this.leftArm  = bodyPart.getChild("left_arm");
        this.rightLeg = root.getChild("right_leg");
        this.leftLeg  = root.getChild("left_leg");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh  = new MeshDefinition();
        PartDefinition root  = mesh.getRoot();

        // body — torso cube, pivot at (0,19,0)  [Y=24 = ground; body sits above legs]
        PartDefinition body = root.addOrReplaceChild("body",
                CubeListBuilder.create()
                        .texOffs(0, 15)
                        .addBox(-4f, -6f, -3f, 8, 6, 6, CubeDeformation.NONE),
                PartPose.offset(0f, 19f, 0f));

        // head — child of body, multiple cubes: main head + nose + antenna stem + antenna cap
        body.addOrReplaceChild("head",
                CubeListBuilder.create()
                        .texOffs(0, 0)
                        .addBox(-4f, -5f, -5f, 8, 5, 10, new CubeDeformation(0.015f))
                        .texOffs(56, 0)
                        .addBox(-1f, -2f, -6f, 2, 3, 2, CubeDeformation.NONE)
                        .texOffs(37, 8)
                        .addBox(-1f, -9f, -1f, 2, 4, 2, new CubeDeformation(-0.015f))
                        .texOffs(37, 0)
                        .addBox(-2f, -13f, -2f, 4, 4, 4, new CubeDeformation(-0.015f)),
                PartPose.offset(0f, -6f, 0f));

        // right arm — child of body
        body.addOrReplaceChild("right_arm",
                CubeListBuilder.create()
                        .texOffs(36, 16)
                        .addBox(-3f, -1f, -2f, 3, 10, 4, CubeDeformation.NONE),
                PartPose.offset(-4f, -6f, 0f));

        // left arm — child of body
        body.addOrReplaceChild("left_arm",
                CubeListBuilder.create()
                        .texOffs(50, 16)
                        .addBox(0f, -1f, -2f, 3, 10, 4, CubeDeformation.NONE),
                PartPose.offset(4f, -6f, 0f));

        // right leg — child of root; bottom at Y=24 = ground level
        root.addOrReplaceChild("right_leg",
                CubeListBuilder.create()
                        .texOffs(0, 27)
                        .addBox(-4f, 0f, -2f, 4, 5, 4, CubeDeformation.NONE),
                PartPose.offset(0f, 19f, 0f));

        // left leg — child of root; bottom at Y=24 = ground level
        root.addOrReplaceChild("left_leg",
                CubeListBuilder.create()
                        .texOffs(16, 27)
                        .addBox(0f, 0f, -2f, 4, 5, 4, CubeDeformation.NONE),
                PartPose.offset(0f, 19f, 0f));

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        head.yRot = netHeadYaw  * Mth.DEG_TO_RAD;
        head.xRot = headPitch   * Mth.DEG_TO_RAD;

        rightLeg.xRot =  Mth.cos(limbSwing * 0.6662f)          * 1.4f * limbSwingAmount;
        leftLeg.xRot  =  Mth.cos(limbSwing * 0.6662f + Mth.PI) * 1.4f * limbSwingAmount;

        if (entity.isUsingChest()) {
            // Arms raised while accessing a chest
            rightArm.xRot = -(Mth.PI / 2f);
            leftArm.xRot  = -(Mth.PI / 2f);
            rightArm.zRot = 0f;
            leftArm.zRot  = 0f;
        } else if (entity.isCarryingAnything()) {
            // Cradling pose: arms swung forward and angled slightly inward
            float bob     = Mth.cos(limbSwing * 0.6662f) * 0.04f * limbSwingAmount;
            rightArm.xRot = -(Mth.PI * 0.38f) + bob;
            rightArm.zRot =  Mth.PI / 10f;
            leftArm.xRot  = -(Mth.PI * 0.38f) - bob;
            leftArm.zRot  = -(Mth.PI / 10f);
        } else {
            rightArm.xRot = Mth.cos(limbSwing * 0.6662f + Mth.PI) * 0.7f * limbSwingAmount;
            leftArm.xRot  = Mth.cos(limbSwing * 0.6662f)           * 0.7f * limbSwingAmount;
            rightArm.zRot = 0f;
            leftArm.zRot  = 0f;
        }
    }

    /**
     * Positions the PoseStack at the midpoint between both cupped arms so that
     * {@link CourierItemLayer} can place a carried item there.
     *
     * <p>In the cradling pose ({@code xRot ≈ -68°}) the two arm wrists converge at
     * roughly {@code (0, -2.5 px, -8 px)} in body-local space.  We enter body space,
     * translate to that convergence point, then add a gentle forward tilt so the item
     * faces the viewer naturally — matching the iconic copper-golem hold.
     */
    public void translateToHeldItemPosition(PoseStack poseStack) {
        body.translateAndRotate(poseStack);
        // Centre between the cupped hands: x=0, slightly below shoulder, well in front.
        poseStack.translate(0.0, -2.0 / 16.0, -8.0 / 16.0);
        // Tilt the item ~20° so it tilts toward the viewer (arms angle forward at ~68°).
        poseStack.mulPose(Axis.XP.rotationDegrees(-20.0f));
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer consumer,
                               int packedLight, int packedOverlay, int color) {
        body.render(poseStack, consumer, packedLight, packedOverlay, color);
        rightLeg.render(poseStack, consumer, packedLight, packedOverlay, color);
        leftLeg.render(poseStack, consumer, packedLight, packedOverlay, color);
    }
}
