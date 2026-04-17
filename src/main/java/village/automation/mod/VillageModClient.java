package village.automation.mod;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import village.automation.mod.client.renderer.CourierModel;
import village.automation.mod.client.renderer.CourierRenderer;
import village.automation.mod.client.renderer.SoulIronGolemRenderer;
import village.automation.mod.screen.SoulIronGolemScreen;
import village.automation.mod.client.renderer.VillagerWorkerRenderer;
import village.automation.mod.screen.AnimalPenBlockScreen;
import village.automation.mod.screen.BeekeeperBlockScreen;
import village.automation.mod.screen.CourierScreen;
import village.automation.mod.screen.SmelterBlockScreen;
import village.automation.mod.screen.BrewingBlockScreen;
import village.automation.mod.screen.CookingBlockScreen;
import village.automation.mod.screen.EnchantingBlockScreen;
import village.automation.mod.screen.FarmBlockScreen;
import village.automation.mod.screen.FishingBlockScreen;
import village.automation.mod.screen.LumbermillBlockScreen;
import village.automation.mod.screen.MineBlockScreen;
import village.automation.mod.screen.SmithingBlockScreen;
import village.automation.mod.screen.VillageHeartScreen;
import village.automation.mod.screen.VillagerWorkerScreen;

@Mod(value = VillageMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = VillageMod.MODID, value = Dist.CLIENT)
public class VillageModClient {

    public VillageModClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        VillageMod.LOGGER.info("HELLO FROM CLIENT SETUP");
        VillageMod.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(VillageMod.VILLAGE_HEART_MENU.get(),    VillageHeartScreen::new);
        event.register(VillageMod.VILLAGER_WORKER_MENU.get(),  VillagerWorkerScreen::new);
        event.register(VillageMod.COURIER_MENU.get(),          CourierScreen::new);
        event.register(VillageMod.FARM_BLOCK_MENU.get(),       FarmBlockScreen::new);
        event.register(VillageMod.MINE_BLOCK_MENU.get(),       MineBlockScreen::new);
        event.register(VillageMod.LUMBERMILL_MENU.get(),       LumbermillBlockScreen::new);
        event.register(VillageMod.FISHING_BLOCK_MENU.get(),    FishingBlockScreen::new);
        event.register(VillageMod.ANIMAL_PEN_MENU.get(),       AnimalPenBlockScreen::new);
        event.register(VillageMod.BEEKEEPER_MENU.get(),        BeekeeperBlockScreen::new);
        event.register(VillageMod.COOKING_BLOCK_MENU.get(),    CookingBlockScreen::new);
        event.register(VillageMod.SMITHING_BLOCK_MENU.get(),   SmithingBlockScreen::new);
        event.register(VillageMod.SMELTER_BLOCK_MENU.get(),    SmelterBlockScreen::new);
        event.register(VillageMod.ENCHANTING_BLOCK_MENU.get(), EnchantingBlockScreen::new);
        event.register(VillageMod.BREWING_BLOCK_MENU.get(),    BrewingBlockScreen::new);
        event.register(VillageMod.SOUL_IRON_GOLEM_MENU.get(), SoulIronGolemScreen::new);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(VillageMod.VILLAGER_WORKER.get(), VillagerWorkerRenderer::new);
        event.registerEntityRenderer(VillageMod.COURIER.get(), CourierRenderer::new);
        event.registerEntityRenderer(VillageMod.SOUL_IRON_GOLEM.get(), SoulIronGolemRenderer::new);
        // Village Bee reuses the vanilla BeeRenderer (same model + textures as a normal bee)
        event.registerEntityRenderer(VillageMod.VILLAGE_BEE.get(),
                ctx -> new net.minecraft.client.renderer.entity.BeeRenderer(ctx));
    }

    @SubscribeEvent
    static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(VillageMod.COURIER_LAYER, CourierModel::createBodyLayer);
    }
}
