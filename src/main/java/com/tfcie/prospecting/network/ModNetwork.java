package com.tfcie.prospecting.network;

import com.tfcie.prospecting.TfcieProspecting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 网络通道（本项目首个网络层）。
 *
 * <p>此前刻意避开了网络：钻探样本的数据就装在 ItemStack 的 NBT 里，客户端本来就有。
 * 但探查地图的数据量决定了必须走包——一次 9×9 区块的探查可能产生上万个点，
 * 塞进物品 NBT 既臃肿又无法离线留存。
 */
public final class ModNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TfcieProspecting.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private ModNetwork() {}

    /** 在模组构造期注册全部消息。 */
    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(SurveyMapS2CPacket.class, id++)
                .encoder(SurveyMapS2CPacket::encode)
                .decoder(SurveyMapS2CPacket::decode)
                .consumerMainThread(SurveyMapS2CPacket::handle)
                .add();
        CHANNEL.messageBuilder(TerrainMapS2CPacket.class, id++)
                .encoder(TerrainMapS2CPacket::encode)
                .decoder(TerrainMapS2CPacket::decode)
                .consumerMainThread(TerrainMapS2CPacket::handle)
                .add();
    }

    /** 向单个玩家发送。 */
    public static void sendTo(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
