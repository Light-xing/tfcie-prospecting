package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 科技阶段。 */
public enum Tier implements WireEnum {
    TFC_COPPER("tfc_copper", "铜器时代"),
    TFC_BRONZE("tfc_bronze", "青铜时代"),
    TFC_IRON("tfc_iron", "铁器时代"),
    IE_ELECTRIC("ie_electric", "电气时代");

    private final String wire;
    private final String zhName;

    Tier(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }
}
