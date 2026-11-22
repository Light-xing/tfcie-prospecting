package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.WireEnum;
import net.minecraft.network.chat.Component;

/**
 * 术语枚举的可翻译名称。
 *
 * <p>lang 键约定：{@code enum.tfcie_prospecting.<category>.<wire>}。
 * 用 {@link Component#translatable} 而非直接拼字符串，是为了让<b>客户端</b>按玩家语言
 * 渲染 —— 服务端不知道玩家用什么语言，把已翻译的字符串发过去就只能是一种语言。
 */
public final class EnumNames {

    public static Component of(String category, WireEnum value) {
        return Component.translatable(key(category, value));
    }

    public static String key(String category, WireEnum value) {
        return "enum.tfcie_prospecting." + category + "." + value.wire();
    }

    private EnumNames() {}
}
