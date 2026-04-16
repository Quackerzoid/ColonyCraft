package village.automation.mod.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import village.automation.mod.ItemRequest;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.VillageHeartBlockEntity;
import village.automation.mod.network.SyncRequestsPacket;

import java.util.ArrayList;
import java.util.List;

public class VillageHeartMenu extends AbstractContainerMenu {

    private final VillageHeartBlockEntity blockEntity;
    private final ContainerData containerData;
    private final Player player;

    // Village name — set from buf on the client, from the block entity on the server
    private String villageName;

    // Client-side request list, populated via SyncRequestsPacket
    private List<ItemRequest> requests = new ArrayList<>();
    // Last request-list version we synced — -1 forces an initial send
    private int lastSyncedRequestsVersion = -1;

    // ── Slot indices ──────────────────────────────────────────────────────────
    // 0         : Village Upgrade I   ( 8, 17) — locked once placed
    // 1         : Village Upgrade II  (26, 17) — locked once placed; requires slot 0 filled
    // 2         : Village Upgrade III (44, 17) — locked once placed; requires slot 1 filled
    // 3         : Bundle of Wheat input (80, 25)
    // 4  – 30   : Player main inventory
    // 31 – 39   : Player hotbar

    // ── Server-side constructor ───────────────────────────────────────────────
    public VillageHeartMenu(int containerId, Inventory inventory, VillageHeartBlockEntity blockEntity) {
        super(VillageMod.VILLAGE_HEART_MENU.get(), containerId);
        this.blockEntity   = blockEntity;
        this.containerData = blockEntity.data;
        this.villageName   = blockEntity.getVillageName();
        this.player        = inventory.player;

        addUpgradeSlots(blockEntity);
        addInputSlot(blockEntity);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
        this.addDataSlots(this.containerData);
    }

    // ── Client-side constructor ───────────────────────────────────────────────
    public VillageHeartMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(VillageMod.VILLAGE_HEART_MENU.get(), containerId);

        BlockPos pos = buf.readBlockPos();
        this.villageName = buf.readUtf();

        BlockEntity be = inventory.player.level().getBlockEntity(pos);
        if (!(be instanceof VillageHeartBlockEntity vh))
            throw new IllegalArgumentException("No VillageHeartBlockEntity at " + pos);

        this.blockEntity   = vh;
        this.containerData = vh.data;
        this.player        = inventory.player;

        addUpgradeSlots(vh);
        addInputSlot(vh);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
        this.addDataSlots(this.containerData);
    }

    // ── Slot setup ────────────────────────────────────────────────────────────

    private void addUpgradeSlots(VillageHeartBlockEntity be) {
        // Tier I — always insertable; locked once placed
        this.addSlot(new Slot(be.getUpgradeContainer(), 0, 52, 5) {
            @Override public boolean mayPlace(ItemStack stack) {
                return stack.is(VillageMod.VILLAGE_UPGRADE.get());
            }
            @Override public boolean mayPickup(Player player) { return false; }
            @Override public int getMaxStackSize() { return 1; }
        });
        // Tier II — only placeable after Tier I is filled; locked once placed
        this.addSlot(new Slot(be.getUpgradeContainer(), 1, 52, 25) {
            @Override public boolean mayPlace(ItemStack stack) {
                return stack.is(VillageMod.VILLAGE_UPGRADE_II.get())
                        && !be.getUpgradeContainer().getItem(0).isEmpty();
            }
            @Override public boolean mayPickup(Player player) { return false; }
            @Override public int getMaxStackSize() { return 1; }
        });
        // Tier III — only placeable after Tier II is filled; locked once placed
        this.addSlot(new Slot(be.getUpgradeContainer(), 2, 52, 45) {
            @Override public boolean mayPlace(ItemStack stack) {
                return stack.is(VillageMod.VILLAGE_UPGRADE_III.get())
                        && !be.getUpgradeContainer().getItem(1).isEmpty();
            }
            @Override public boolean mayPickup(Player player) { return false; }
            @Override public int getMaxStackSize() { return 1; }
        });
    }

    private void addInputSlot(VillageHeartBlockEntity be) {
        this.addSlot(new Slot(be.getInputContainer(), 0, 8, 8) {
            @Override public boolean mayPlace(ItemStack stack) {
                return stack.is(VillageMod.BUNDLE_OF_WHEAT.get());
            }
        });
    }

    private void addPlayerInventory(Inventory playerInventory) {
        // Slots 4 – 30 (9 × 3 main inventory rows)
        for (int row = 0; row < 3; ++row)
            for (int col = 0; col < 9; ++col)
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
    }

    private void addPlayerHotbar(Inventory playerInventory) {
        // Slots 31 – 39 (hotbar)
        for (int col = 0; col < 9; ++col)
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int  getStoredWheat()  { return this.containerData.get(0); }
    public int  getWorkerCount()  { return this.containerData.get(1); }
    public int  getWorkerCap()    { return this.containerData.get(2); }
    public int  getRadius()       { return this.containerData.get(3); }
    public String getVillageName() { return this.villageName; }
    /** Called client-side after the player confirms a name so the screen updates immediately. */
    public void setVillageName(String name) { this.villageName = name; }
    public BlockPos getHeartPos() { return this.blockEntity.getBlockPos(); }

    /** Returns the client-side cached request list (populated via {@link SyncRequestsPacket}). */
    public List<ItemRequest> getRequests() { return requests; }

    /** Called by {@link SyncRequestsPacket} on the client to update the displayed list. */
    public void updateRequests(List<ItemRequest> incoming) {
        this.requests = new ArrayList<>(incoming);
    }

    // ── Request sync ──────────────────────────────────────────────────────────

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        // Server-side only: send a sync packet whenever the request list changes
        if (player instanceof ServerPlayer serverPlayer
                && lastSyncedRequestsVersion != blockEntity.getRequestsVersion()) {
            lastSyncedRequestsVersion = blockEntity.getRequestsVersion();
            PacketDistributor.sendToPlayer(serverPlayer,
                    new SyncRequestsPacket(blockEntity.getBlockPos(),
                            blockEntity.getPendingRequests()));
        }
    }

    // ── Validity ──────────────────────────────────────────────────────────────

    @Override
    public boolean stillValid(Player player) {
        return AbstractContainerMenu.stillValid(
                ContainerLevelAccess.create(this.blockEntity.getLevel(), this.blockEntity.getBlockPos()),
                player, VillageMod.VILLAGE_HEART.get());
    }

    // ── Shift-click ───────────────────────────────────────────────────────────

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack     = slot.getItem();
        ItemStack remainder = stack.copy();

        // Slots 0-2 are upgrade slots — permanently locked, shift-click does nothing
        if (index <= 2) return ItemStack.EMPTY;

        // Slot 3 (wheat input) — shift-click sends to player inv
        if (index == 3) {
            if (!this.moveItemStackTo(stack, 4, 40, false)) return ItemStack.EMPTY;
        } else {
            // From player inv: route to the appropriate block slot
            if (stack.is(VillageMod.VILLAGE_UPGRADE.get())) {
                if (!this.moveItemStackTo(stack, 0, 1, false))
                    if (!shufflePlayerSlots(stack, index)) return ItemStack.EMPTY;
            } else if (stack.is(VillageMod.VILLAGE_UPGRADE_II.get())) {
                if (!this.moveItemStackTo(stack, 1, 2, false))
                    if (!shufflePlayerSlots(stack, index)) return ItemStack.EMPTY;
            } else if (stack.is(VillageMod.VILLAGE_UPGRADE_III.get())) {
                if (!this.moveItemStackTo(stack, 2, 3, false))
                    if (!shufflePlayerSlots(stack, index)) return ItemStack.EMPTY;
            } else if (stack.is(VillageMod.BUNDLE_OF_WHEAT.get())) {
                if (!this.moveItemStackTo(stack, 3, 4, false))
                    if (!shufflePlayerSlots(stack, index)) return ItemStack.EMPTY;
            } else {
                if (!shufflePlayerSlots(stack, index)) return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return remainder;
    }

    private boolean shufflePlayerSlots(ItemStack stack, int fromIndex) {
        // Hotbar (31-39) → main inv (4-31); main inv (4-30) → hotbar (31-40)
        return fromIndex >= 31
                ? this.moveItemStackTo(stack, 4, 31, false)
                : this.moveItemStackTo(stack, 31, 40, false);
    }

    public VillageHeartBlockEntity getBlockEntity() { return blockEntity; }
}
