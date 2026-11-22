package com.tfcie.prospecting.api;

/**
 * 可被材质覆盖的参数标识 —— 材质变体的参数白名单单元。
 *
 * <p>格雷科技式：同一物品由不同材质制作，仅白名单内的参数数值不同；
 * 白名单之外（如某工具本就不该被材质影响的参数）不参与 NBT 覆盖。
 */
public enum ToolParam {
    MATERIAL("material"),
    MAX_DURABILITY("max_durability"),
    RADIUS("radius"),
    SHALLOW_DEPTH("shallow_depth"),
    FALSE_NEGATIVE_CHANCE("false_negative_chance"),
    CONFIDENCE_CAP("confidence_cap"),
    ENERGY_COST("energy_cost"),
    ENERGY_CAPACITY("energy_capacity"),
    MAX_DEPTH("max_depth");

    private final String key;

    ToolParam(String key) {
        this.key = key;
    }

    /** NBT / 配置中的键名（lowercase snake_case）。 */
    public String key() {
        return key;
    }
}
