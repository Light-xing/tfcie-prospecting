package com.tfcie.prospecting.network;

import com.tfcie.prospecting.client.ClientGuiHooks;
import com.tfcie.prospecting.common.survey.OreColors;
import com.tfcie.prospecting.common.survey.SurveyMap;
import com.tfcie.prospecting.common.survey.SurveyPoint;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 探查地图下发（服务端 → 客户端）。
 *
 * <h2>线格式：键调色板 + 紧凑点表</h2>
 * 每个点若各自带一次字符串键，上万点会膨胀到上百 KB。这里把<b>去重后的键</b>作为调色板
 * 先发一次，点表里只存 {@code 1 字节索引}：
 *
 * <pre>
 * int    originX          地图左上角方块 X
 * int    originZ
 * int    chunks           边长（区块）
 * int    probeX           探测点 X（玩家右键的那个方块）
 * int    probeZ
 * String toolId           发起探查的工具（用于 GUI 标题）
 * varint keyCount
 *   String key × keyCount 键调色板
 * int    pointCount
 *   byte relX / byte relZ / byte keyIndex   × pointCount
 * </pre>
 *
 * 坐标用相对地图左上角的偏移。默认 9×9 区块 → 0~143，1 字节（0~255）足够；
 * 这也把 `chunk_radius` 的上限（8 → 15×15 区块 = 240）限制在了字节范围内。
 * <b>颜色不入网</b>：客户端按 {@link OreColors} 由键推出同一套固定色，避免两端口径漂移。
 */
public record SurveyMapS2CPacket(int originX, int originZ, int chunks, int probeX, int probeZ,
                                 String toolId, List<SurveyPoint> points) {

    /** 解码。<b>读取顺序必须与 {@link #encode} 完全一致。</b> */
    public static SurveyMapS2CPacket decode(FriendlyByteBuf buffer) {
        final int originX = buffer.readInt();
        final int originZ = buffer.readInt();
        final int chunks = buffer.readInt();
        final int probeX = buffer.readInt();
        final int probeZ = buffer.readInt();
        final String toolId = buffer.readUtf();

        final int keyCount = buffer.readVarInt();
        final String[] palette = new String[keyCount];
        for (int i = 0; i < keyCount; i++) {
            palette[i] = buffer.readUtf();
        }

        final int pointCount = buffer.readInt();
        final List<SurveyPoint> points = new ArrayList<>(pointCount);
        for (int i = 0; i < pointCount; i++) {
            final int relX = buffer.readUnsignedByte();
            final int relZ = buffer.readUnsignedByte();
            final int keyIndex = buffer.readUnsignedByte();
            final String key = keyIndex < palette.length ? palette[keyIndex] : "";
            points.add(new SurveyPoint(originX + relX, originZ + relZ, key, OreColors.of(key)));
        }
        return new SurveyMapS2CPacket(originX, originZ, chunks, probeX, probeZ, toolId, points);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(originX);
        buffer.writeInt(originZ);
        buffer.writeInt(chunks);
        buffer.writeInt(probeX);
        buffer.writeInt(probeZ);
        buffer.writeUtf(toolId);

        final Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        for (SurveyPoint point : points) {
            paletteIndex.putIfAbsent(point.key(), paletteIndex.size());
        }
        buffer.writeVarInt(paletteIndex.size());
        for (String key : paletteIndex.keySet()) {
            buffer.writeUtf(key);
        }

        buffer.writeInt(points.size());
        for (SurveyPoint point : points) {
            buffer.writeByte(point.x() - originX);
            buffer.writeByte(point.z() - originZ);
            buffer.writeByte(paletteIndex.getOrDefault(point.key(), 0));
        }
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        final NetworkEvent.Context context = contextSupplier.get();
        // consumerMainThread 已在主线程回调，可直接开界面
        ClientGuiHooks.openSurveyMap(toMap(), toolId);
        context.setPacketHandled(true);
    }

    /** 还原为客户端侧的地图对象（与发送端同构）。 */
    public SurveyMap toMap() {
        final SurveyMap map = new SurveyMap(originX, originZ, chunks, probeX, probeZ);
        for (SurveyPoint point : points) {
            map.add(point);
        }
        return map;
    }
}
