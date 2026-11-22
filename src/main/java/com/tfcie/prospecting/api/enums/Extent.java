package com.tfcie.prospecting.api.enums;

import com.tfcie.prospecting.api.WireEnum;

/** 延展规模档（磁力探针的横向 / 垂向延展）。 */
public enum Extent implements WireEnum {
    SMALL("small", "小"),
    MEDIUM("medium", "中"),
    LARGE("large", "大");

    private final String wire;
    private final String zhName;

    Extent(String wire, String zhName) {
        this.wire = wire;
        this.zhName = zhName;
    }

    @Override public String wire() { return wire; }
    @Override public String zhName() { return zhName; }

    /** 由命中分布的跨度（格）分档。 */
    public static Extent ofSpan(double span, double smallMax, double largeMin) {
        if (span < smallMax) {
            return SMALL;
        }
        return span < largeMin ? MEDIUM : LARGE;
    }
}
