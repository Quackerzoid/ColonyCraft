package village.automation.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import village.automation.mod.ItemRequest;
import village.automation.mod.VillageMod;

import javax.annotation.Nullable;

public class SmithVillagerEntity extends AbstractVillager {

    // ── Crafting states ───────────────────────────────────────────────────────
    public static final int STATE_IDLE     = 0;
    public static final int STATE_AWAITING = 1;  // waiting for courier to bring materials
    public static final int STATE_CRAFTING = 2;  // materials present, timer running
    public static final int STATE_READY    = 3;  // crafted item waiting for courier pickup

    // ── State ─────────────────────────────────────────────────────────────────
    private BlockPos  linkedHeartPos = null;
    private int       craftingState  = STATE_IDLE;
    private int       craftingTimer  = 0;
    @Nullable private ItemRequest  currentRequest = null;
    @Nullable private SmithRecipe  currentRecipe  = null;

    // ── Inventories ───────────────────────────────────────────────────────────
    /** Materials deposited here by the courier before crafting begins. */
    private final SimpleContainer inputContainer  = new SimpleContainer(9);
    /** Finished item placed here for the courier to pick up. */
    private final SimpleContainer outputContainer = new SimpleContainer(1);

    // ── Constructor / attributes ──────────────────────────────────────────────

    public SmithVillagerEntity(EntityType<? extends SmithVillagerEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return AbstractVillager.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.4);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 6.0f));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    // ── Crafting state API (used by SmithCraftGoal) ───────────────────────────

    public int       getCraftingState()   { return craftingState;  }
    public int       getCraftingTimer()   { return craftingTimer;  }
    @Nullable
    public ItemRequest  getCurrentRequest()  { return currentRequest; }
    @Nullable
    public SmithRecipe  getCurrentRecipe()   { return currentRecipe;  }

    public void setCraftingState(int state)            { this.craftingState   = state;   }
    public void setCraftingTimer(int timer)            { this.craftingTimer   = timer;   }
    public void setCurrentRequest(ItemRequest req)     { this.currentRequest  = req;     }
    public void setCurrentRecipe(SmithRecipe recipe)   { this.currentRecipe   = recipe;  }

    public void tickCraftingTimer() { if (craftingTimer > 0) craftingTimer--; }

    public SimpleContainer getInputContainer()  { return inputContainer;  }
    public SimpleContainer getOutputContainer() { return outputContainer; }

    // ── Heart link ────────────────────────────────────────────────────────────

    @Nullable
    public BlockPos getLinkedHeartPos() { return linkedHeartPos; }

    public void setLinkedHeartPos(BlockPos pos) {
        this.linkedHeartPos = pos;
        // Stay close to the heart
        if (pos != null) this.restrictTo(pos, 6);
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && linkedHeartPos != null) {
            // Keep territory restriction refreshed
            this.restrictTo(linkedHeartPos, 6);
        }
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        if (linkedHeartPos != null) {
            tag.putInt("HeartX", linkedHeartPos.getX());
            tag.putInt("HeartY", linkedHeartPos.getY());
            tag.putInt("HeartZ", linkedHeartPos.getZ());
        }
        tag.putInt("CraftingState", craftingState);
        tag.putInt("CraftingTimer", craftingTimer);

        if (currentRequest != null) tag.put("CurrentRequest", currentRequest.save());
        if (currentRecipe  != null) tag.putString("CurrentRecipeResult",
                net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(currentRecipe.result).toString());

        // Input container
        ListTag inputTag = new ListTag();
        for (int i = 0; i < inputContainer.getContainerSize(); i++) {
            ItemStack s = inputContainer.getItem(i);
            if (!s.isEmpty()) {
                CompoundTag st = new CompoundTag();
                st.putByte("Slot", (byte) i);
                st.put("Item", s.save(this.registryAccess()));
                inputTag.add(st);
            }
        }
        tag.put("SmithInput", inputTag);

        // Output container
        ItemStack out = outputContainer.getItem(0);
        if (!out.isEmpty()) tag.put("SmithOutput", out.save(this.registryAccess()));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        if (tag.contains("HeartX")) {
            linkedHeartPos = new BlockPos(tag.getInt("HeartX"), tag.getInt("HeartY"), tag.getInt("HeartZ"));
            this.restrictTo(linkedHeartPos, 6);
        }
        craftingState = tag.getInt("CraftingState");
        craftingTimer = tag.getInt("CraftingTimer");

        if (tag.contains("CurrentRequest")) {
            try { currentRequest = ItemRequest.load(tag.getCompound("CurrentRequest")); }
            catch (Exception ignored) { currentRequest = null; }
        }
        if (tag.contains("CurrentRecipeResult")) {
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraft.resources.ResourceLocation.parse(tag.getString("CurrentRecipeResult"));
            net.minecraft.world.item.Item item =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
            currentRecipe = SmithRecipe.findFor(item).orElse(null);
        }

        // Input container
        inputContainer.clearContent();
        ListTag inputTag = tag.getList("SmithInput", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < inputTag.size(); i++) {
            CompoundTag st = inputTag.getCompound(i);
            int slot = st.getByte("Slot") & 255;
            if (slot < inputContainer.getContainerSize()) {
                inputContainer.setItem(slot,
                        ItemStack.parseOptional(this.registryAccess(), st.getCompound("Item")));
            }
        }

        // Output container
        outputContainer.clearContent();
        if (tag.contains("SmithOutput")) {
            outputContainer.setItem(0,
                    ItemStack.parseOptional(this.registryAccess(), tag.getCompound("SmithOutput")));
        }
    }

    // ── Required AbstractVillager / AgeableMob stubs ─────────────────────────

    @Nullable @Override
    public AbstractVillager getBreedOffspring(ServerLevel level, AgeableMob other) { return null; }
    @Override public MerchantOffers getOffers()                    { return new MerchantOffers(); }
    @Override public void overrideOffers(MerchantOffers offers)    {}
    @Override public void notifyTrade(MerchantOffer offer)         {}
    @Override public void notifyTradeUpdated(ItemStack stack)      {}
    @Override public int  getVillagerXp()                          { return 0; }
    @Override public boolean showProgressBar()                     { return false; }
    @Override protected void rewardTradeXp(MerchantOffer offer)    {}
    @Override protected void updateTrades()                        {}
}
