package village.automation.mod.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
import village.automation.mod.entity.CourierEntity;
import village.automation.mod.network.SyncGolemsPacket;
import village.automation.mod.network.SyncRequestsPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class VillageHeartMenu extends AbstractContainerMenu {

    private final VillageHeartBlockEntity blockEntity;
    private final ContainerData containerData;
    private final Player player;

    private String villageName;

    private List<ItemRequest> requests = new ArrayList<>();
    private int lastSyncedRequestsVersion = -1;

    private List<SyncGolemsPacket.GolemInfo> golems = new ArrayList<>();
    private int golemSyncCooldown = 0;

    // ── Slot layout (image-relative coords) ──────────────────────────────────
    //  0        : Upgrade input    x=88,  y=32   (consumed by server tick)
    //  1        : Wheat input      x=88,  y=74
    //  2 – 28   : Player inventory x=87+col*18,  y=114+row*18
    // 29 – 37   : Hotbar           x=87+col*18,  y=170

    // ── Server constructor ────────────────────────────────────────────────────
    public VillageHeartMenu(int containerId, Inventory inventory, VillageHeartBlockEntity blockEntity) {
        super(VillageMod.VILLAGE_HEART_MENU.get(), containerId);
        this.blockEntity   = blockEntity;
        this.containerData = blockEntity.data;
        this.villageName   = blockEntity.getVillageName();
        this.player        = inventory.player;

        addBlockSlots(blockEntity);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
        this.addDataSlots(this.containerData);
    }

    // ── Client constructor ────────────────────────────────────────────────────
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

        addBlockSlots(vh);
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
        this.addDataSlots(this.containerData);
    }

    // ── Slot setup ────────────────────────────────────────────────────────────

    private void addBlockSlots(VillageHeartBlockEntity be) {
        // Slot 0 — upgrade (left-aligned in upgrade section, consumed on server tick)
        this.addSlot(new Slot(be.getUpgradeInputSlot(), 0, 88, 32) {
            @Override public boolean mayPlace(ItemStack stack) {
                int t = getAppliedUpgrades();
                if (t >= 3) return false;
                return (t == 0 && stack.is(VillageMod.VILLAGE_UPGRADE.get()))
                    || (t == 1 && stack.is(VillageMod.VILLAGE_UPGRADE_II.get()))
                    || (t == 2 && stack.is(VillageMod.VILLAGE_UPGRADE_III.get()));
            }
            @Override public int getMaxStackSize() { return 1; }
        });

        // Slot 1 — wheat input
        this.addSlot(new Slot(be.getInputContainer(), 0, 88, 74) {
            @Override public boolean mayPlace(ItemStack stack) {
                return stack.is(VillageMod.BUNDLE_OF_WHEAT.get());
            }
        });
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; ++row)
            for (int col = 0; col < 9; ++col)
                this.addSlot(new Slot(inv, col + row * 9 + 9, 87 + col * 18, 114 + row * 18));
    }

    private void addPlayerHotbar(Inventory inv) {
        for (int col = 0; col < 9; ++col)
            this.addSlot(new Slot(inv, col, 87 + col * 18, 170));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int    getStoredWheat()  { return containerData.get(0); }
    public int    getWorkerCount()  { return containerData.get(1); }
    public int    getWorkerCap()    { return containerData.get(2); }
    public int    getRadius()       { return containerData.get(3); }
    public String getVillageName()  { return villageName; }
    public void   setVillageName(String n) { villageName = n; }
    public BlockPos getHeartPos()   { return blockEntity.getBlockPos(); }

    /** Returns the number of upgrade tiers applied (0–3), synced directly from the server. */
    public int getAppliedUpgrades() {
        return containerData.get(4);
    }

    public List<ItemRequest>              getRequests() { return requests; }
    public void updateRequests(List<ItemRequest> l)     { requests = new ArrayList<>(l); }

    public List<SyncGolemsPacket.GolemInfo> getGolems() { return golems; }
    public void updateGolems(List<SyncGolemsPacket.GolemInfo> l) { golems = new ArrayList<>(l); }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(player instanceof ServerPlayer sp)) return;

        if (lastSyncedRequestsVersion != blockEntity.getRequestsVersion()) {
            lastSyncedRequestsVersion = blockEntity.getRequestsVersion();
            PacketDistributor.sendToPlayer(sp,
                    new SyncRequestsPacket(blockEntity.getBlockPos(), blockEntity.getPendingRequests()));
        }

        if (golemSyncCooldown-- <= 0) {
            golemSyncCooldown = 20;
            if (player.level() instanceof ServerLevel sl)
                PacketDistributor.sendToPlayer(sp,
                        new SyncGolemsPacket(blockEntity.getBlockPos(), buildGolemInfos(sl)));
        }
    }

    private List<SyncGolemsPacket.GolemInfo> buildGolemInfos(ServerLevel level) {
        List<SyncGolemsPacket.GolemInfo> out = new ArrayList<>();
        for (UUID uuid : blockEntity.getCourierUUIDs()) {
            Entity e = level.getEntity(uuid);
            if (!(e instanceof CourierEntity c) || !c.isAlive()) continue;
            String name = c.hasCustomName() && c.getCustomName() != null
                    ? c.getCustomName().getString() : "Courier";
            out.add(new SyncGolemsPacket.GolemInfo(name, "Active"));
        }
        for (UUID uuid : blockEntity.getGolemUUIDs()) {
            Entity e = level.getEntity(uuid);
            if (!(e instanceof village.automation.mod.entity.SoulIronGolemEntity g) || !g.isAlive()) continue;
            String name = g.isBellGolem() ? "Bell Guardian" : "Soul Iron Golem";
            out.add(new SyncGolemsPacket.GolemInfo(name, g.getStatus()));
        }
        return out;
    }

    // ── Validity ──────────────────────────────────────────────────────────────

    @Override
    public boolean stillValid(Player player) {
        return AbstractContainerMenu.stillValid(
                ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos()),
                player, VillageMod.VILLAGE_HEART.get());
    }

    // ── Shift-click ───────────────────────────────────────────────────────────

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy  = stack.copy();

        if (index == 0) {
            // Upgrade slot — never shift-movable (item is consumed, not returned)
            return ItemStack.EMPTY;
        } else if (index == 1) {
            // Wheat slot → player inventory
            if (!moveItemStackTo(stack, 2, 38, false)) return ItemStack.EMPTY;
        } else {
            // From player inventory — route by type
            if (stack.is(VillageMod.VILLAGE_UPGRADE.get())
                    || stack.is(VillageMod.VILLAGE_UPGRADE_II.get())
                    || stack.is(VillageMod.VILLAGE_UPGRADE_III.get())) {
                if (!moveItemStackTo(stack, 0, 1, false)
                        && !shufflePlayer(stack, index)) return ItemStack.EMPTY;
            } else if (stack.is(VillageMod.BUNDLE_OF_WHEAT.get())) {
                if (!moveItemStackTo(stack, 1, 2, false)
                        && !shufflePlayer(stack, index)) return ItemStack.EMPTY;
            } else {
                if (!shufflePlayer(stack, index)) return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }

    private boolean shufflePlayer(ItemStack stack, int from) {
        // hotbar (29-37) ↔ main inv (2-28)
        return from >= 29
                ? moveItemStackTo(stack, 2, 29, false)
                : moveItemStackTo(stack, 29, 38, false);
    }

    public VillageHeartBlockEntity getBlockEntity() { return blockEntity; }
}
