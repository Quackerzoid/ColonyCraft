package village.automation.mod.client.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import village.automation.mod.entity.SmithVillagerEntity;

/**
 * Renders the blacksmith (toolsmith) profession overlay on top of the
 * base villager skin — plains biome tint + toolsmith apron/hat.
 */
public class SmithProfessionLayer
        extends RenderLayer<SmithVillagerEntity, VillagerModel<SmithVillagerEntity>> {

    private static final ResourceLocation TYPE_PLAINS =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/type/plains.png");

    private static final ResourceLocation PROF_ARMORER =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/profession/armorer.png");

    public SmithProfessionLayer(
            RenderLayerParent<SmithVillagerEntity, VillagerModel<SmithVillagerEntity>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       SmithVillagerEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        overlay(poseStack, bufferSource, packedLight, entity, TYPE_PLAINS);
        overlay(poseStack, bufferSource, packedLight, entity, PROF_ARMORER);
    }

    private void overlay(PoseStack poseStack, MultiBufferSource bufferSource,
                         int packedLight, SmithVillagerEntity entity,
                         ResourceLocation texture) {
        this.getParentModel().renderToBuffer(
                poseStack,
                bufferSource.getBuffer(RenderType.entityTranslucent(texture)),
                packedLight,
                LivingEntityRenderer.getOverlayCoords(entity, 0.0f));
    }
}
