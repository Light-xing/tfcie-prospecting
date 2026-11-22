package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 物品类别。 */
public enum Kind implements WireEnum {
    TOOL("tool", "可用工具"),
    LEGACY_TOOL("legacy_tool", "旧工具"),
    DATA_ITEM("data_item", "数据物品");

    private final String wire;
    private final String zhName;

    Kind(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }
}
