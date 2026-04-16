package village.automation.mod.client.renderer;

import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import village.automation.mod.client.renderer.layer.SmithProfessionLayer;
import village.automation.mod.entity.SmithVillagerEntity;

/**
 * Renders the smith using the standard villager model with a toolsmith
 * profession overlay (plains biome tint + toolsmith apron/hat).
 */
public class SmithVillagerRenderer
        extends MobRenderer<SmithVillagerEntity, VillagerModel<SmithVillagerEntity>> {

    private static final ResourceLocation BASE_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/villager.png");

    public SmithVillagerRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new VillagerModel<>(ctx.bakeLayer(ModelLayers.VILLAGER)), 0.5f);
        this.addLayer(new SmithProfessionLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(SmithVillagerEntity entity) {
        return BASE_TEXTURE;
    }
}
