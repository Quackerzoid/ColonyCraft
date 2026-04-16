package village.automation.mod.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import village.automation.mod.VillageMod;
import village.automation.mod.blockentity.VillageHeartBlockEntity;

public record SetVillageNamePacket(BlockPos heartPos, String name) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetVillageNamePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(VillageMod.MODID, "set_village_name"));

    public static final StreamCodec<FriendlyByteBuf, SetVillageNamePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SetVillageNamePacket decode(FriendlyByteBuf buf) {
                    return new SetVillageNamePacket(buf.readBlockPos(), buf.readUtf());
                }
                @Override
                public void encode(FriendlyByteBuf buf, SetVillageNamePacket pkt) {
                    buf.writeBlockPos(pkt.heartPos());
                    buf.writeUtf(pkt.name());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server-side handler — updates the block entity's village name. */
    public static void handle(SetVillageNamePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player().level().getBlockEntity(pkt.heartPos())
                    instanceof VillageHeartBlockEntity be) {
                be.setVillageName(pkt.name());
            }
        });
    }
}
