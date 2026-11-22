package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/**
 * 钻探终止原因。遇水不终止（水层记为含水层），故 water 不在此列；
 * 仅岩浆 / 基岩 / 不可钻方块 / 达到设定深度触发终止。
 */
public enum TerminatedBy implements WireEnum {
    MAX_DEPTH("max_depth", "达到设定深度"),
    BEDROCK("bedrock", "基岩"),
    LAVA("lava", "岩浆"),
    OBSTRUCTION("obstruction", "不可钻障碍");

    private final String wire;
    private final String zhName;

    TerminatedBy(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }
}
