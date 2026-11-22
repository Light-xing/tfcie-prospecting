package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.ProspectingResult;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/**
 * 一次工具使用的输出：智能体侧的 {@link ProspectingResult}（纯文本 + 结构化 data）
 * 与玩家侧的 {@link Component}（可翻译，由客户端按玩家语言渲染）。
 *
 * <p>两者出自同一次判读，避免「玩家看到的话」与「智能体读到的数据」各自演化而漂移。
 */
public record ProspectOutcome(ProspectingResult result, @Nullable Component display) {

    public static ProspectOutcome of(ProspectingResult result) {
        return new ProspectOutcome(result, null);
    }

    public static ProspectOutcome of(ProspectingResult result, @Nullable Component display) {
        return new ProspectOutcome(result, display);
    }
}
