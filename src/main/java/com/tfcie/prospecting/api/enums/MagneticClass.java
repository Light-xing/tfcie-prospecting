package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 磁性分类：仅磁/非磁判别，不分辨具体磁性矿种。 */
public enum MagneticClass implements WireEnum {
    MAGNETIC("magnetic", "磁性体"),
    NON_MAGNETIC("non_magnetic", "非磁性体"),
    NONE("none", "无异常");

    private final String wire;
    private final String zhName;

    MagneticClass(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }
}
