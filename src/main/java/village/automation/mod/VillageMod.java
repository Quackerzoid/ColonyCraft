package village.automation.mod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import java.util.Optional;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import village.automation.mod.block.AnimalPenBlock;
import village.automation.mod.block.BrewingBlock;
import village.automation.mod.block.CookingBlock;
import village.automation.mod.block.EnchantingBlock;
import village.automation.mod.block.FarmBlock;
import village.automation.mod.block.FishingBlock;
import village.automation.mod.block.LumbermillBlock;
import village.automation.mod.block.MineBlock;
import village.automation.mod.block.SmelterBlock;
import village.automation.mod.block.SmithingBlock;
import village.automation.mod.block.VillageHeartBlock;
import village.automation.mod.blockentity.AnimalPenBlockEntity;
import village.automation.mod.blockentity.BrewingBlockEntity;
import village.automation.mod.blockentity.CookingBlockEntity;
import village.automation.mod.blockentity.EnchantingBlockEntity;
import village.automation.mod.blockentity.FarmBlockEntity;
import village.automation.mod.blockentity.FishingBlockEntity;
import village.automation.mod.blockentity.LumbermillBlockEntity;
import village.automation.mod.blockentity.MineBlockEntity;
import village.automation.mod.blockentity.SmelterBlockEntity;
import village.automation.mod.blockentity.SmithingBlockEntity;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.entity.CourierEntity;
import village.automation.mod.entity.VillagerWorkerEntity;
import village.automation.mod.item.VillageWandItem;
import village.automation.mod.loot.VillagerSoulLootModifier;
import village.automation.mod.menu.AnimalPenBlockMenu;
import village.automation.mod.menu.BrewingBlockMenu;
import village.automation.mod.menu.CookingBlockMenu;
import village.automation.mod.menu.CourierMenu;
import village.automation.mod.menu.EnchantingBlockMenu;
import village.automation.mod.menu.FarmBlockMenu;
import village.automation.mod.menu.FishingBlockMenu;
import village.automation.mod.menu.LumbermillBlockMenu;
import village.automation.mod.menu.MineBlockMenu;
import village.automation.mod.menu.SmelterBlockMenu;
import village.automation.mod.menu.SmithingBlockMenu;
import village.automation.mod.menu.VillageHeartMenu;
import village.automation.mod.menu.VillagerWorkerMenu;
import village.automation.mod.network.SetVillageNamePacket;
import village.automation.mod.network.SyncRequestsPacket;

@Mod(VillageMod.MODID)
public class VillageMod {
    public static final String MODID = "colonycraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    // ── Deferred Registers ───────────────────────────────────────────────────
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> GLM_SERIALIZERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, MODID);

    // ── Example content (from MDK) ───────────────────────────────────────────
    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", new Item.Properties().food(new FoodProperties.Builder()
            .alwaysEdible().nutrition(1).saturationModifier(2f).build()));

    // ── Village Heart block ──────────────────────────────────────────────────
    public static final DeferredBlock<VillageHeartBlock> VILLAGE_HEART = BLOCKS.register("village_heart",
            () -> new VillageHeartBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.5f)
                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> VILLAGE_HEART_ITEM = ITEMS.registerSimpleBlockItem("village_heart", VILLAGE_HEART);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VillageHeartBlockEntity>> VILLAGE_HEART_BE =
            BLOCK_ENTITY_TYPES.register("village_heart",
                    () -> BlockEntityType.Builder.of(VillageHeartBlockEntity::new, VILLAGE_HEART.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<VillageHeartMenu>> VILLAGE_HEART_MENU =
            MENU_TYPES.register("village_heart_menu",
                    () -> IMenuTypeExtension.create(VillageHeartMenu::new));

    // ── Farm Block ───────────────────────────────────────────────────────────
    public static final DeferredBlock<FarmBlock> FARM_BLOCK = BLOCKS.register("farm_block",
            () -> new FarmBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DIRT)
                    .strength(2.0f)
                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> FARM_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("farm_block", FARM_BLOCK);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FarmBlockEntity>> FARM_BLOCK_BE =
            BLOCK_ENTITY_TYPES.register("farm_block",
                    () -> BlockEntityType.Builder.of(FarmBlockEntity::new, FARM_BLOCK.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<FarmBlockMenu>> FARM_BLOCK_MENU =
            MENU_TYPES.register("farm_block_menu",
                    () -> IMenuTypeExtension.create(FarmBlockMenu::new));

    // ── Mine Block ───────────────────────────────────────────────────────────
    public static final DeferredBlock<MineBlock> MINE_BLOCK = BLOCKS.register("mine_block",
            () -> new MineBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE).strength(2.5f).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> MINE_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("mine_block", MINE_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MineBlockEntity>> MINE_BLOCK_BE =
            BLOCK_ENTITY_TYPES.register("mine_block",
                    () -> BlockEntityType.Builder.of(MineBlockEntity::new, MINE_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<MineBlockMenu>> MINE_BLOCK_MENU =
            MENU_TYPES.register("mine_block_menu",
                    () -> IMenuTypeExtension.create(MineBlockMenu::new));

    // ── Lumbermill Block ─────────────────────────────────────────────────────
    public static final DeferredBlock<LumbermillBlock> LUMBERMILL = BLOCKS.register("lumbermill",
            () -> new LumbermillBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD).strength(2.5f).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> LUMBERMILL_ITEM =
            ITEMS.registerSimpleBlockItem("lumbermill", LUMBERMILL);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LumbermillBlockEntity>> LUMBERMILL_BE =
            BLOCK_ENTITY_TYPES.register("lumbermill",
                    () -> BlockEntityType.Builder.of(LumbermillBlockEntity::new, LUMBERMILL.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<LumbermillBlockMenu>> LUMBERMILL_MENU =
            MENU_TYPES.register("lumbermill_menu",
                    () -> IMenuTypeExtension.create(LumbermillBlockMenu::new));

    // ── Fishing Block ────────────────────────────────────────────────────────
    public static final DeferredBlock<FishingBlock> FISHING_BLOCK = BLOCKS.register("fishing_block",
            () -> new FishingBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WATER).strength(2.5f).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> FISHING_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("fishing_block", FISHING_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FishingBlockEntity>> FISHING_BLOCK_BE =
            BLOCK_ENTITY_TYPES.register("fishing_block",
                    () -> BlockEntityType.Builder.of(FishingBlockEntity::new, FISHING_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<FishingBlockMenu>> FISHING_BLOCK_MENU =
            MENU_TYPES.register("fishing_block_menu",
                    () -> IMenuTypeExtension.create(FishingBlockMenu::new));

    // ── Animal Pen ───────────────────────────────────────────────────────────
    public static final DeferredBlock<AnimalPenBlock> ANIMAL_PEN = BLOCKS.register("animal_pen",
            () -> new AnimalPenBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD).strength(2.5f).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> ANIMAL_PEN_ITEM =
            ITEMS.registerSimpleBlockItem("animal_pen", ANIMAL_PEN);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AnimalPenBlockEntity>> ANIMAL_PEN_BE =
            BLOCK_ENTITY_TYPES.register("animal_pen",
                    () -> BlockEntityType.Builder.of(AnimalPenBlockEntity::new, ANIMAL_PEN.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<AnimalPenBlockMenu>> ANIMAL_PEN_MENU =
            MENU_TYPES.register("animal_pen_menu",
                    () -> IMenuTypeExtension.create(AnimalPenBlockMenu::new));

    // ── Cooking Block ────────────────────────────────────────────────────────
    public static final DeferredBlock<CookingBlock> COOKING_BLOCK = BLOCKS.register("cooking_block",
            () -> new CookingBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.FIRE).strength(2.5f).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> COOKING_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("cooking_block", COOKING_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CookingBlockEntity>> COOKING_BLOCK_BE =
            BLOCK_ENTITY_TYPES.register("cooking_block",
                    () -> BlockEntityType.Builder.of(CookingBlockEntity::new, COOKING_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<CookingBlockMenu>> COOKING_BLOCK_MENU =
            MENU_TYPES.register("cooking_block_menu",
                    () -> IMenuTypeExtension.create(CookingBlockMenu::new));

    // ── Smithing Block ───────────────────────────────────────────────────────
    public static final DeferredBlock<SmithingBlock> SMITHING_BLOCK = BLOCKS.register("smithing_block",
            () -> new SmithingBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL).strength(2.5f).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> SMITHING_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("smithing_block", SMITHING_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SmithingBlockEntity>> SMITHING_BLOCK_BE =
            BLOCK_ENTITY_TYPES.register("smithing_block",
                    () -> BlockEntityType.Builder.of(SmithingBlockEntity::new, SMITHING_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<SmithingBlockMenu>> SMITHING_BLOCK_MENU =
            MENU_TYPES.register("smithing_block_menu",
                    () -> IMenuTypeExtension.create(SmithingBlockMenu::new));

    // ── Enchanting Block ─────────────────────────────────────────────────────
    public static final DeferredBlock<EnchantingBlock> ENCHANTING_BLOCK = BLOCKS.register("enchanting_block",
            () -> new EnchantingBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE).strength(2.5f).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> ENCHANTING_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("enchanting_block", ENCHANTING_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EnchantingBlockEntity>> ENCHANTING_BLOCK_BE =
            BLOCK_ENTITY_TYPES.register("enchanting_block",
                    () -> BlockEntityType.Builder.of(EnchantingBlockEntity::new, ENCHANTING_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<EnchantingBlockMenu>> ENCHANTING_BLOCK_MENU =
            MENU_TYPES.register("enchanting_block_menu",
                    () -> IMenuTypeExtension.create(EnchantingBlockMenu::new));

    // ── Brewing Block ────────────────────────────────────────────────────────
    public static final DeferredBlock<BrewingBlock> BREWING_BLOCK = BLOCKS.register("brewing_block",
            () -> new BrewingBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE).strength(2.5f).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> BREWING_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("brewing_block", BREWING_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BrewingBlockEntity>> BREWING_BLOCK_BE =
            BLOCK_ENTITY_TYPES.register("brewing_block",
                    () -> BlockEntityType.Builder.of(BrewingBlockEntity::new, BREWING_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<BrewingBlockMenu>> BREWING_BLOCK_MENU =
            MENU_TYPES.register("brewing_block_menu",
                    () -> IMenuTypeExtension.create(BrewingBlockMenu::new));

    // ── Smelter Block ────────────────────────────────────────────────────────
    public static final DeferredBlock<SmelterBlock> SMELTER_BLOCK = BLOCKS.register("smelter_block",
            () -> new SmelterBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.0f)
                    .requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> SMELTER_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("smelter_block", SMELTER_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SmelterBlockEntity>> SMELTER_BLOCK_BE =
            BLOCK_ENTITY_TYPES.register("smelter_block",
                    () -> BlockEntityType.Builder.of(SmelterBlockEntity::new, SMELTER_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<SmelterBlockMenu>> SMELTER_BLOCK_MENU =
            MENU_TYPES.register("smelter_block_menu",
                    () -> IMenuTypeExtension.create(SmelterBlockMenu::new));

    // ── Bundle of Wheat ──────────────────────────────────────────────────────
    public static final DeferredItem<Item> BUNDLE_OF_WHEAT =
            ITEMS.registerSimpleItem("bundle_of_wheat", new Item.Properties().stacksTo(16));

    // ── Villager Soul ─────────────────────────────────────────────────────────
    // 25% drop from vanilla Villagers via the VillagerSoulLootModifier GLM
    public static final DeferredItem<Item> VILLAGER_SOUL =
            ITEMS.registerSimpleItem("villager_soul", new Item.Properties().stacksTo(64));

    // ── Village Wand ─────────────────────────────────────────────────────────
    public static final DeferredItem<VillageWandItem> VILLAGE_WAND =
            ITEMS.register("village_wand",
                    () -> new VillageWandItem(new Item.Properties().stacksTo(1)));

    // ── Village Upgrades (tiered) ─────────────────────────────────────────────
    // Tier I  →  8 workers   — 4× Emerald Block + Villager Soul (centre)
    // Tier II → 16 workers   — 4× Diamond Block + Villager Soul (centre)
    // Tier III→ 32 workers   — 4× Netherite Ingot + Villager Soul (centre)
    public static final DeferredItem<Item> VILLAGE_UPGRADE =
            ITEMS.registerSimpleItem("village_upgrade", new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> VILLAGE_UPGRADE_II =
            ITEMS.registerSimpleItem("village_upgrade_ii", new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> VILLAGE_UPGRADE_III =
            ITEMS.registerSimpleItem("village_upgrade_iii", new Item.Properties().stacksTo(1));

    // ── Global Loot Modifier ──────────────────────────────────────────────────
    public static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<VillagerSoulLootModifier>> VILLAGER_SOUL_GLM =
            GLM_SERIALIZERS.register("villager_soul_drop", () -> VillagerSoulLootModifier.CODEC);

    // ── Soul Pumpkin ─────────────────────────────────────────────────────────
    public static final DeferredBlock<Block> SOUL_PUMPKIN =
            BLOCKS.register("soul_pumpkin",
                    () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.CARVED_PUMPKIN)));
    public static final DeferredItem<BlockItem> SOUL_PUMPKIN_ITEM =
            ITEMS.registerSimpleBlockItem("soul_pumpkin", SOUL_PUMPKIN);

    // ── Courier entity ───────────────────────────────────────────────────────
    public static final DeferredHolder<EntityType<?>, EntityType<CourierEntity>> COURIER =
            ENTITY_TYPES.register("courier",
                    () -> EntityType.Builder.<CourierEntity>of(CourierEntity::new, MobCategory.MISC)
                            .sized(0.6f, 1.4f)
                            .clientTrackingRange(10)
                            .build(MODID + ":courier"));

    // ── Courier menu ─────────────────────────────────────────────────────────
    public static final DeferredHolder<MenuType<?>, MenuType<CourierMenu>> COURIER_MENU =
            MENU_TYPES.register("courier_menu",
                    () -> IMenuTypeExtension.create(CourierMenu::new));

    // ── Courier model layer ──────────────────────────────────────────────────
    public static final ModelLayerLocation COURIER_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(MODID, "courier"), "main");

    // ── Villager Worker entity ───────────────────────────────────────────────
    public static final DeferredHolder<EntityType<?>, EntityType<VillagerWorkerEntity>> VILLAGER_WORKER =
            ENTITY_TYPES.register("villager_worker",
                    () -> EntityType.Builder.<VillagerWorkerEntity>of(VillagerWorkerEntity::new, MobCategory.MISC)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(10)
                            .build(MODID + ":villager_worker"));

    // Brown base (#563C33) with blue highlight (#60A3D9)
    public static final DeferredItem<SpawnEggItem> VILLAGER_WORKER_SPAWN_EGG =
            ITEMS.register("villager_worker_spawn_egg",
                    () -> new SpawnEggItem(VILLAGER_WORKER.get(), 0x563C33, 0x60A3D9, new Item.Properties()));

    public static final DeferredHolder<MenuType<?>, MenuType<VillagerWorkerMenu>> VILLAGER_WORKER_MENU =
            MENU_TYPES.register("villager_worker_menu",
                    () -> IMenuTypeExtension.create(VillagerWorkerMenu::new));

    // ── Creative Tab ─────────────────────────────────────────────────────────
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> VILLAGE_MOD_TAB = CREATIVE_MODE_TABS.register("village_mod_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.colonycraft"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> VILLAGE_HEART_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(VILLAGE_HEART_ITEM.get());
                        output.accept(FARM_BLOCK_ITEM.get());
                        output.accept(MINE_BLOCK_ITEM.get());
                        output.accept(LUMBERMILL_ITEM.get());
                        output.accept(FISHING_BLOCK_ITEM.get());
                        output.accept(ANIMAL_PEN_ITEM.get());
                        output.accept(COOKING_BLOCK_ITEM.get());
                        output.accept(SMITHING_BLOCK_ITEM.get());
                        output.accept(SMELTER_BLOCK_ITEM.get());
                        output.accept(ENCHANTING_BLOCK_ITEM.get());
                        output.accept(BREWING_BLOCK_ITEM.get());
                        output.accept(BUNDLE_OF_WHEAT.get());
                        output.accept(VILLAGE_WAND.get());
                        output.accept(VILLAGER_SOUL.get());
                        output.accept(VILLAGE_UPGRADE.get());
                        output.accept(VILLAGE_UPGRADE_II.get());
                        output.accept(VILLAGE_UPGRADE_III.get());
                        output.accept(SOUL_PUMPKIN_ITEM.get());
                        output.accept(VILLAGER_WORKER_SPAWN_EGG.get());
                        output.accept(EXAMPLE_ITEM.get());
                        output.accept(EXAMPLE_BLOCK_ITEM.get());
                    }).build());

    public VillageMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        GLM_SERIALIZERS.register(modEventBus);

        modEventBus.addListener(VillageMod::onEntityAttributes);
        modEventBus.addListener(VillageMod::onRegisterPayloads);
        modEventBus.addListener(VillageMod::registerCapabilities);

        NeoForge.EVENT_BUS.register(this);

        modEventBus.addListener(this::addCreative);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MODID).versioned("1.0.0");
        registrar.playToServer(
                SetVillageNamePacket.TYPE,
                SetVillageNamePacket.STREAM_CODEC,
                SetVillageNamePacket::handle);
        registrar.playToClient(
                SyncRequestsPacket.TYPE,
                SyncRequestsPacket.STREAM_CODEC,
                SyncRequestsPacket::handle);
    }

    /**
     * Registers {@link net.neoforged.neoforge.capabilities.Capabilities.ItemHandler#BLOCK}
     * on profession block entities so that hoppers (and other automation) can
     * interact with their inventories.
     *
     * <p><b>Pattern for adding a new profession block:</b>
     * <pre>{@code
     * event.registerBlockEntity(
     *     Capabilities.ItemHandler.BLOCK,
     *     YOUR_BLOCK_BE.get(),
     *     (be, side) -> side == Direction.DOWN ? be.getYourOutputHandler() : null
     * );
     * }</pre>
     */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Farm Block — hoppers below pull from the 9-slot crop output inventory
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                FARM_BLOCK_BE.get(),
                (be, side) -> side == net.minecraft.core.Direction.DOWN
                        ? be.getOutputHandler()
                        : null
        );
        // Mine Block — hoppers below pull from the 9-slot mined-item output inventory
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                MINE_BLOCK_BE.get(),
                (be, side) -> side == net.minecraft.core.Direction.DOWN
                        ? be.getOutputHandler()
                        : null
        );
        // Lumbermill — hoppers below pull from the 9-slot wood-output inventory
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                LUMBERMILL_BE.get(),
                (be, side) -> side == net.minecraft.core.Direction.DOWN
                        ? be.getOutputHandler()
                        : null
        );
        // Fishing Block — hoppers below pull from the 9-slot fish-output inventory
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                FISHING_BLOCK_BE.get(),
                (be, side) -> side == net.minecraft.core.Direction.DOWN
                        ? be.getOutputHandler()
                        : null
        );
    }

    private static void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(VILLAGER_WORKER.get(), VillagerWorkerEntity.createAttributes().build());
        event.put(COURIER.get(), CourierEntity.createAttributes().build());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(EXAMPLE_BLOCK_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(VILLAGER_WORKER_SPAWN_EGG);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("HELLO from server starting");
    }

    /**
     * Prevents a Village Heart from being placed if the target position falls
     * inside any existing heart's territory.  Cancels the event (so the item
     * is not consumed) and shows a red action-bar message to the player.
     */
    @SubscribeEvent
    public void onEntityPlaceBlock(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
        BlockPos pos = event.getPos();

        // Prevent a Village Heart being placed inside another heart's territory
        if (event.getState().is(VILLAGE_HEART.get())) {
            VillageHeartBlockEntity.findClaimingHeart(serverLevel, pos, pos).ifPresent(existingHeart -> {
                event.setCanceled(true);
                if (event.getEntity() instanceof Player player) {
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component
                                    .literal("Too close to an existing Village Heart!")
                                    .withStyle(ChatFormatting.RED),
                            true);
                }
            });
            return;
        }

        // Soul pumpkin placed on top of a copper block → spawn courier
        if (event.getState().is(SOUL_PUMPKIN.get())) {
            BlockPos copperPos = pos.below();
            if (serverLevel.getBlockState(copperPos).is(Blocks.COPPER_BLOCK)) {
                Optional<BlockPos> heartPosOpt =
                        VillageHeartBlockEntity.findClaimingHeart(serverLevel, copperPos, null);
                if (heartPosOpt.isEmpty()) {
                    event.setCanceled(true);
                    if (event.getEntity() instanceof Player player) {
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component
                                        .literal("Must be within a Village Heart's territory!")
                                        .withStyle(ChatFormatting.RED),
                                true);
                    }
                    return;
                }
                serverLevel.removeBlock(pos, false);
                serverLevel.removeBlock(copperPos, false);
                CourierEntity courier = new CourierEntity(COURIER.get(), serverLevel);
                courier.moveTo(copperPos.getX() + 0.5, copperPos.getY() + 0.5,
                        copperPos.getZ() + 0.5, serverLevel.getRandom().nextFloat() * 360f, 0f);
                courier.finalizeSpawn(serverLevel, serverLevel.getCurrentDifficultyAt(copperPos),
                        MobSpawnType.MOB_SUMMONED, null);
                BlockPos heartPos = heartPosOpt.get();
                courier.setLinkedHeartPos(heartPos);
                serverLevel.addFreshEntity(courier);
                net.minecraft.world.level.block.entity.BlockEntity heartBe =
                        serverLevel.getBlockEntity(heartPos);
                if (heartBe instanceof VillageHeartBlockEntity heartBE) {
                    heartBE.registerCourier(courier.getUUID());
                }
                event.setCanceled(true);
            }
        }
    }
}
