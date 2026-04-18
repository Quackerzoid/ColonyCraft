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
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import village.automation.mod.entity.VillagerSoulEntity;

public class VillagerSoulModel<T extends VillagerSoulEntity> extends EntityModel<T> {

    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart leftWing;
    private final ModelPart rightWing;

    public VillagerSoulModel(ModelPart root) {
        super(RenderType::entityTranslucentCull);
        this.head      = root.getChild("head");
        this.body      = root.getChild("body");
        this.rightArm  = root.getChild("right_arm");
        this.leftArm   = root.getChild("left_arm");
        this.leftWing  = root.getChild("left_wing");
        this.rightWing = root.getChild("right_wing");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("head",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-2.5f, -5.0f, -2.5f, 5, 5, 5, CubeDeformation.NONE)
                        .texOffs(23, 6).addBox(-0.5f, -2.0f, -4.0f, 1, 3, 2, CubeDeformation.NONE),
                PartPose.offset(0.0f, 18.0f, 0.0f));

        root.addOrReplaceChild("body",
                CubeListBuilder.create()
                        .texOffs(0, 10).addBox(-1.5f, 0.0f, -1.0f, 3, 4, 2, CubeDeformation.NONE)
                        .texOffs(0, 16).addBox(-1.5f, 0.0f, -1.0f, 3, 5, 2, new CubeDeformation(-0.2f)),
                PartPose.offset(0.0f, 18.0f, 0.0f));

        root.addOrReplaceChild("right_arm",
                CubeListBuilder.create()
                        .texOffs(23, 0).addBox(-0.75f, -0.5f, -1.0f, 1, 4, 2, CubeDeformation.NONE),
                PartPose.offset(-1.75f, 18.5f, 0.0f));

        root.addOrReplaceChild("left_arm",
                CubeListBuilder.create()
                        .texOffs(23, 6).addBox(-0.25f, -0.5f, -1.0f, 1, 4, 2, CubeDeformation.NONE),
                PartPose.offset(1.75f, 18.5f, 0.0f));

        root.addOrReplaceChild("left_wing",
                CubeListBuilder.create(),
                PartPose.offset(0.5f, 18.0f, 1.0f));

        root.addOrReplaceChild("right_wing",
                CubeListBuilder.create(),
                PartPose.offset(-0.5f, 18.0f, 1.0f));

        return LayerDefinition.create(mesh, 32, 32);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        head.yRot = netHeadYaw  * Mth.DEG_TO_RAD;
        head.xRot = headPitch   * Mth.DEG_TO_RAD;

        float bob = Mth.sin(ageInTicks * 0.1f) * 0.05f;
        body.y = 18.0f + bob * 16.0f;
        head.y = 18.0f + bob * 16.0f;
        rightArm.y = 18.5f + bob * 16.0f;
        leftArm.y  = 18.5f + bob * 16.0f;

        rightArm.xRot = Mth.cos(limbSwing * 0.6662f + Mth.PI) * 0.5f * limbSwingAmount;
        leftArm.xRot  = Mth.cos(limbSwing * 0.6662f)           * 0.5f * limbSwingAmount;
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer consumer,
                               int packedLight, int packedOverlay, int color) {
        // Force 50% alpha while preserving RGB channels
        int halfAlphaColor = (color & 0x00FFFFFF) | 0x80000000;
        head.render(poseStack, consumer, packedLight, packedOverlay, halfAlphaColor);
        body.render(poseStack, consumer, packedLight, packedOverlay, halfAlphaColor);
        rightArm.render(poseStack, consumer, packedLight, packedOverlay, halfAlphaColor);
        leftArm.render(poseStack, consumer, packedLight, packedOverlay, halfAlphaColor);
        leftWing.render(poseStack, consumer, packedLight, packedOverlay, halfAlphaColor);
        rightWing.render(poseStack, consumer, packedLight, packedOverlay, halfAlphaColor);
    }
}
