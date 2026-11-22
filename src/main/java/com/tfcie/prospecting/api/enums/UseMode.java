package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 工具使用方式。 */
public enum UseMode implements WireEnum {
    RIGHT_CLICK("right_click", "手持右键使用"),
    HOLD_CHARGE("hold_charge", "长按蓄力"),
    PLACE_ACTIVATE("place_activate", "放置/插地激活"),
    CONTACT("contact", "紧贴石质方块"),
    SUBMERGE("submerge", "浸水使用"),
    OPEN_GUI("open_gui", "右键打开界面");

    private final String wire;
    private final String zhName;

    UseMode(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }
}
