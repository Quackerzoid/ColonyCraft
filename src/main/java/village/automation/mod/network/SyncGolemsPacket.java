package village.automation.mod.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import village.automation.mod.VillageMod;
import village.automation.mod.menu.VillageHeartMenu;

import java.util.ArrayList;
import java.util.List;

/** Sent server→client to update the open Village Heart menu's golem list. */
public record SyncGolemsPacket(BlockPos heartPos, List<GolemInfo> golems)
        implements CustomPacketPayload {

    /** Lightweight snapshot of a single golem's display state. */
    public record GolemInfo(String name, String status) {}

    public static final CustomPacketPayload.Type<SyncGolemsPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "sync_golems"));

    public static final StreamCodec<FriendlyByteBuf, SyncGolemsPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SyncGolemsPacket decode(FriendlyByteBuf buf) {
                    BlockPos pos   = buf.readBlockPos();
                    int      count = buf.readVarInt();
                    List<GolemInfo> list = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        list.add(new GolemInfo(buf.readUtf(), buf.readUtf()));
                    }
                    return new SyncGolemsPacket(pos, list);
                }

                @Override
                public void encode(FriendlyByteBuf buf, SyncGolemsPacket pkt) {
                    buf.writeBlockPos(pkt.heartPos());
                    buf.writeVarInt(pkt.golems().size());
                    for (GolemInfo g : pkt.golems()) {
                        buf.writeUtf(g.name());
                        buf.writeUtf(g.status());
                    }
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler — pushes the golem list into the currently open menu. */
    public static void handle(SyncGolemsPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player().containerMenu instanceof VillageHeartMenu menu
                    && menu.getHeartPos().equals(pkt.heartPos())) {
                menu.updateGolems(pkt.golems());
            }
        });
    }
}
