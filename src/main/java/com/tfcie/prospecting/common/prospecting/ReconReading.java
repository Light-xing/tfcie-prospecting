package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.enums.Confidence;
import com.tfcie.prospecting.api.enums.OreGroup;
import com.tfcie.prospecting.api.enums.Presence;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次踏勘采样的读数输出（纯数据），同时驱动玩家消息与智能体 JSON。
 *
 * @param presence      迹象三档（矿量，不反映距离与方位）
 * @param group         矿族大类
 * @param suspectedOreId 收敛后的疑似矿名 id（未收敛为 null）
 * @param confidence    置信度（上限 medium，永不 high）
 * @param converged     本次是否达成收敛
 * @param saturated     本次采样时是否已饱和（无新增信息）
 * @param repeated      本次是否为饱和后的重复结论
 * @param interference  本次读数受到的干扰来源
 */
public record ReconReading(Presence presence,
                           OreGroup group,
                           @Nullable String suspectedOreId,
                           Confidence confidence,
                           boolean converged,
                           boolean saturated,
                           boolean repeated,
                           Interference interference) {

    /** 供 {@code ProspectingResult} 使用的 data 映射（枚举按 wire 名序列化）。 */
    public Map<String, Object> toData() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("presence", presence);
        d.put("ore_group", group);
        if (suspectedOreId != null) {
            d.put("ore_name_suspected", suspectedOreId);
        }
        d.put("confidence", confidence);
        d.put("converged", converged);
        d.put("saturated", saturated);
        d.put("repeated", repeated);
        d.put("interference", interference.wire());
        return d;
    }
}
