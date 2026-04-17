package village.automation.mod.client.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.entity.VillagerWorkerEntity;

/**
 * Renders biome-type and profession overlays on top of the base villager skin.
 *
 * <p>Vanilla villager rendering stacks three passes:
 * <ol>
 *   <li>Base skin   – {@code textures/entity/villager/villager.png} (drawn by the renderer)
 *   <li>Biome type  – mod's {@code textures/entity/worker_type/<biome>.png}
 *   <li>Profession  – mod's {@code textures/entity/<profession>.png}
 * </ol>
 * Each pass uses {@code RenderType.entityTranslucent} so transparent pixels
 * in the overlay texture show the layer beneath.
 *
 * <p><b>Adding a new job:</b> add a {@code case} to the switch that calls
 * {@link #overlay} once per texture you want stacked, in bottom-to-top order.
 */
public class WorkerProfessionLayer
        extends RenderLayer<VillagerWorkerEntity, VillagerModel<VillagerWorkerEntity>> {

    // ── Biome-type overlays (mod assets) ─────────────────────────────────────
    private static final ResourceLocation TYPE_PLAINS =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/worker_type/plains.png");
    private static final ResourceLocation TYPE_SAVANNA =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/worker_type/savanna.png");
    private static final ResourceLocation TYPE_TAIGA =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/worker_type/taiga.png");
    private static final ResourceLocation TYPE_JUNGLE =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/worker_type/jungle.png");
    private static final ResourceLocation TYPE_DESERT =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/worker_type/desert.png");
    private static final ResourceLocation TYPE_SWAMP =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/worker_type/swamp.png");
    private static final ResourceLocation TYPE_SNOW =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/worker_type/snow.png");

    // ── Profession overlays (mod assets) ─────────────────────────────────────
    private static final ResourceLocation PROF_FARMER =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/farmer.png");
    private static final ResourceLocation PROF_MASON =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/mason.png");
    private static final ResourceLocation PROF_FLETCHER =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/fletcher.png");
    private static final ResourceLocation PROF_FISHERMAN =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/fisherman.png");
    private static final ResourceLocation PROF_SHEPHERD =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/shepherd.png");
    private static final ResourceLocation PROF_CHEF =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/worker_chef.png");
    private static final ResourceLocation PROF_SMITH =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/worker_smith.png");
    private static final ResourceLocation PROF_LIBRARIAN =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/librarian.png");
    private static final ResourceLocation PROF_CLERIC =
            ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "textures/entity/cleric.png");

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

        // VillagerModel implements HatableModel; hatVisible() controls the head,
        // hat, and hat-rim parts together. Assert true so headgear from any
        // profession texture is always rendered, regardless of prior layer state.
        this.getParentModel().hatVisible(true);

        switch (job) {
            case FARMER -> {
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_SAVANNA);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_FARMER);
            }
            case MINER -> {
                // Taiga mason — stone-worker look
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_TAIGA);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_MASON);
            }
            case LUMBERJACK -> {
                // Jungle fletcher — woodworking biome + arrow/wood profession
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_JUNGLE);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_FLETCHER);
            }
            case FISHERMAN -> {
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_PLAINS);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_FISHERMAN);
            }
            case SHEPHERD -> {
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_SAVANNA);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_SHEPHERD);
            }
            case CHEF -> {
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_PLAINS);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_CHEF);
            }
            case BLACKSMITH -> {
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_PLAINS);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_SMITH);
            }
            case ENCHANTER -> {
                // Desert librarian — scholar/magic aesthetic
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_DESERT);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_LIBRARIAN);
            }
            case POTION_BREWER -> {
                // Swamp cleric — alchemy and mysticism
                overlay(poseStack, bufferSource, packedLight, entity, TYPE_SWAMP);
                overlay(poseStack, bufferSource, packedLight, entity, PROF_CLERIC);
            }
            default -> { /* no overlay defined yet */ }
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Renders one overlay texture using the parent model's current pose.
     * Uses {@code entityCutoutNoCull} (the same render type as vanilla's
     * VillagerProfessionLayer) so transparent pixels correctly show through
     * to the layers beneath.
     */
    private void overlay(PoseStack poseStack, MultiBufferSource bufferSource,
                         int packedLight, VillagerWorkerEntity entity,
                         ResourceLocation texture) {
        renderColoredCutoutModel(this.getParentModel(), texture, poseStack, bufferSource, packedLight, entity, -1);
    }
}
