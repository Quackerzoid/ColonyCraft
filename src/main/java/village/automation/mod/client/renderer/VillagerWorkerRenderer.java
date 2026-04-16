package village.automation.mod.client.renderer;

import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.CrossedArmsItemLayer;
import net.minecraft.resources.ResourceLocation;
import village.automation.mod.client.renderer.layer.WorkerProfessionLayer;
import village.automation.mod.entity.VillagerWorkerEntity;

public class VillagerWorkerRenderer
        extends MobRenderer<VillagerWorkerEntity, VillagerModel<VillagerWorkerEntity>> {

    /** Base villager skin shared by all workers regardless of job. */
    private static final ResourceLocation BASE_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/villager.png");

    public VillagerWorkerRenderer(EntityRendererProvider.Context context) {
        super(context, new VillagerModel<>(context.bakeLayer(ModelLayers.VILLAGER)), 0.5f);

        // Renders the tool from the MAINHAND equipment slot with the arms-crossed
        // villager pose — exactly how vanilla villagers hold their items.
        this.addLayer(new CrossedArmsItemLayer<>(this, context.getItemInHandRenderer()));

        // Overlays the correct profession texture (farmer hat/apron, etc.)
        this.addLayer(new WorkerProfessionLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(VillagerWorkerEntity entity) {
        return BASE_TEXTURE;
    }
}
