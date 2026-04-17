package village.automation.mod.client.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

/**
 * Renders biome-type and profession overlays on top of the base villager skin.
 *
 * <p>Vanilla villager rendering stacks three passes:
 * <ol>
 *   <li>Base skin   – {@code textures/entity/villager/villager.png} (drawn by the renderer)
 *   <li>Biome type  – {@code textures/entity/villager/type/<biome>.png}
 *   <li>Profession  – {@code textures/entity/villager/profession/<job>.png}
 * </ol>
 * Each pass uses {@code RenderType.entityTranslucent} so transparent pixels
 * in the overlay texture show the layer beneath.
 *
 * <p><b>Adding a new job:</b> add a {@code case} to the switch that calls
 * {@link #overlay} once per texture you want stacked, in bottom-to-top order.
 */
public class WorkerProfessionLayer
        extends RenderLayer<VillagerWorkerEntity, VillagerModel<VillagerWorkerEntity>> {

    // ── Biome-type textures ───────────────────────────────────────────────────
    private static final ResourceLocation TYPE_PLAINS =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/type/plains.png");

    private static final ResourceLocation TYPE_SAVANNA =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/type/savanna.png");

    private static final ResourceLocation TYPE_TAIGA =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/type/taiga.png");

    private static final ResourceLocation TYPE_JUNGLE =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/type/jungle.png");

    // ── Profession textures ───────────────────────────────────────────────────
    private static final ResourceLocation PROF_FARMER =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/profession/farmer.png");

    private static final ResourceLocation PROF_LEATHERWORKER =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/profession/leatherworker.png");

    private static final ResourceLocation PROF_SHEPHERD =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/profession/shepherd.png");

    private static final ResourceLocation PROF_TOOLSMITH =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/profession/toolsmith.png");

    public WorkerProfessionLayer(
            RenderLayerParent<VillagerWorkerEntity, VillagerModel<VillagerWorkerEntity>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       VillagerWorkerEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {

        JobType job = entity.getSyncedJob();
        if (job == JobType.UNEMPLOYED) return;

        switch (job) {
            case FARMER -> {
                // Savanna farmer — biome tint first, then profession outfit on top
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_SAVANNA);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_FARMER);
            }
            case MINER -> {
                // Taiga leatherworker — biome tint first, then profession outfit on top
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_TAIGA);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_LEATHERWORKER);
            }
            case LUMBERJACK -> {
                // Jungle shepherd — forest biome tint, shepherd apron/hat on top
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_JUNGLE);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_SHEPHERD);
            }
            case BLACKSMITH -> {
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_PLAINS);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_TOOLSMITH);
            }
            case CHEF -> {
                // Plains leatherworker — matching apron/hat for a kitchen worker
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_PLAINS);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_LEATHERWORKER);
            }
            default -> { /* no overlay yet */ }
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Renders one overlay texture using the parent model's current pose.
     * Transparent pixels in {@code texture} show whatever was drawn beneath.
     */
    private void overlay(PoseStack poseStack, MultiBufferSource bufferSource,
                         int packedLight, VillagerWorkerEntity entity,
                         ResourceLocation texture) {
        this.getParentModel().renderToBuffer(
                poseStack,
                bufferSource.getBuffer(RenderType.entityTranslucent(texture)),
                packedLight,
                LivingEntityRenderer.getOverlayCoords(entity, 0.0f));
    }
}
