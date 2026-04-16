package village.automation.mod.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import village.automation.mod.ItemRequest;
import village.automation.mod.VillageMod;
import village.automation.mod.entity.JobType;
import village.automation.mod.menu.VillageHeartMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Sent server→client to update the open Village Heart menu's request list. */
public record SyncRequestsPacket(BlockPos heartPos, List<ItemRequest> requests)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncRequestsPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "sync_requests"));

    public static final StreamCodec<FriendlyByteBuf, SyncRequestsPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SyncRequestsPacket decode(FriendlyByteBuf buf) {
                    BlockPos pos   = buf.readBlockPos();
                    int      count = buf.readVarInt();
                    List<ItemRequest> list = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        UUID    uuid = buf.readUUID();
                        String  name = buf.readUtf();
                        JobType job  = JobType.valueOf(buf.readUtf());
                        ResourceLocation rl = buf.readResourceLocation();
                        Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
                        long ts  = buf.readLong();
                        list.add(new ItemRequest(uuid, name, job, new ItemStack(item), ts));
                    }
                    return new SyncRequestsPacket(pos, list);
                }

                @Override
                public void encode(FriendlyByteBuf buf, SyncRequestsPacket pkt) {
                    buf.writeBlockPos(pkt.heartPos());
                    buf.writeVarInt(pkt.requests().size());
                    for (ItemRequest req : pkt.requests()) {
                        buf.writeUUID(req.getWorkerUUID());
                        buf.writeUtf(req.getWorkerName());
                        buf.writeUtf(req.getJobType().name());
                        buf.writeResourceLocation(
                                BuiltInRegistries.ITEM.getKey(req.getRequestedItem().getItem()));
                        buf.writeLong(req.getTimestamp());
                    }
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler — pushes the request list into the currently open menu. */
    public static void handle(SyncRequestsPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player().containerMenu instanceof VillageHeartMenu menu
                    && menu.getHeartPos().equals(pkt.heartPos())) {
                menu.updateRequests(pkt.requests());
            }
        });
    }
}
