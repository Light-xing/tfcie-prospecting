package com.tfcie.prospecting.network;

import com.tfcie.prospecting.client.ClientGuiHooks;
import com.tfcie.prospecting.common.terrain.TerrainMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 地形图下发（服务端 → 客户端）。
 *
 * <h2>线格式</h2>
 * <pre>
 * int    originX / originZ   地图左上角方块坐标
 * int    chunks              边长（区块）
 * byte[] heights             长度 = size²×2，大端 short；哨兵 Short.MIN_VALUE 表示无数据
 * varint colorCount
 *   int color × colorCount   颜色调色板
 * byte[] colorIndex          长度 = size²，调色板索引
 * </pre>
 *
 * 高度与颜色索引都用<b>字节数组整体写</b>而不是逐元素 {@code writeShort} —— 五万多次逐元素写入
 * 在包构造期的开销不可忽略。整包约 173 KB（15×15 区块），会被原版网络层的压缩阈值自动压掉
 * （高度图与调色板索引的压缩率都很高）。
 */
public record TerrainMapS2CPacket(int originX, int originZ, int chunks, TerrainMap map) {

    /** 解码。<b>读取顺序必须与 {@link #encode} 完全一致。</b> */
    public static TerrainMapS2CPacket decode(FriendlyByteBuf buffer) {
        final int originX = buffer.readInt();
        final int originZ = buffer.readInt();
        final int chunks = buffer.readInt();
        final int size = chunks * 16;

        final byte[] rawHeights = buffer.readByteArray();
        final int[] palette;
        final int colorCount = buffer.readVarInt();
        palette = new int[colorCount];
        for (int i = 0; i < colorCount; i++) {
            palette[i] = buffer.readInt();
        }
        final byte[] colorIndex = buffer.readByteArray();

        final TerrainMap map = new TerrainMap(originX, originZ, chunks);
        for (int index = 0; index < size * size; index++) {
            final int height = (short) (((rawHeights[index * 2] & 0xFF) << 8) | (rawHeights[index * 2 + 1] & 0xFF));
            final int paletteIndex = colorIndex[index] & 0xFF;
            final int color = paletteIndex < palette.length ? palette[paletteIndex] : 0;
            map.setAt(index % size, index / size, height, color);
        }
        return new TerrainMapS2CPacket(originX, originZ, chunks, map);
    }

    public void encode(FriendlyByteBuf buffer) {
        final int size = chunks * 16;
        buffer.writeInt(originX);
        buffer.writeInt(originZ);
        buffer.writeInt(chunks);

        final byte[] rawHeights = new byte[size * size * 2];
        final int[] palette = new int[256];
        final java.util.Map<Integer, Integer> paletteIndex = new java.util.HashMap<>();
        for (int index = 0; index < size * size; index++) {
            final int height = map.heightAtIndex(index);
            rawHeights[index * 2] = (byte) (height >>> 8);
            rawHeights[index * 2 + 1] = (byte) height;
        }
        buffer.writeByteArray(rawHeights);

        final byte[] colorIndex = new byte[size * size];
        int paletteSize = 0;
        for (int index = 0; index < size * size; index++) {
            final int color = map.colorAtIndex(index);
            Integer slot = paletteIndex.get(color);
            if (slot == null) {
                if (paletteSize < 256) {
                    slot = paletteSize;
                    palette[paletteSize++] = color;
                    paletteIndex.put(color, slot);
                } else {
                    slot = 0;   // 超过 256 色的极端情况退化为第 0 色（地表色种类远达不到）
                }
            }
            colorIndex[index] = (byte) (int) slot;
        }
        buffer.writeVarInt(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            buffer.writeInt(palette[i]);
        }
        buffer.writeByteArray(colorIndex);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        final NetworkEvent.Context context = contextSupplier.get();
        // consumerMainThread 已在主线程回调，可直接开界面
        ClientGuiHooks.openTerrainMap(map);
        context.setPacketHandled(true);
    }
}
