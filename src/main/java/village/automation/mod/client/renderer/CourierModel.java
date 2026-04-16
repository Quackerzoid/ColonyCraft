package village.automation.mod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
    private final ModelPart body;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
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
        } else {
            rightArm.xRot = Mth.cos(limbSwing * 0.6662f + Mth.PI) * 0.7f * limbSwingAmount;
            leftArm.xRot  = Mth.cos(limbSwing * 0.6662f)           * 0.7f * limbSwingAmount;
        }
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer consumer,
                               int packedLight, int packedOverlay, int color) {
        body.render(poseStack, consumer, packedLight, packedOverlay, color);
        rightLeg.render(poseStack, consumer, packedLight, packedOverlay, color);
        leftLeg.render(poseStack, consumer, packedLight, packedOverlay, color);
    }
}
