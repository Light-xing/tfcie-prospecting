package com.tfcie.prospecting.api;

/**
 * 材质参数容器（格雷科技式）。
 *
 * <p>同一件物品由不同材质制作，仅参数数值不同（耐久上限 / 勘探范围 /
 * 置信度 / 假阴性等）；每一件制作出的工具独立存储参数于 {@code ItemStack}
 * NBT。本次（阶段 1）只定义容器，NBT 读写与「按工具参数白名单读取」见阶段 2。
 *
 * <p>读取顺序（阶段 2 落实）：{@code ItemStack NBT → 注册表默认 → 硬编码兜底}。
 */
public final class ToolParameters {

    private String material = "default";
    private int maxDurability = 256;
    private int radius = 8;
    private int shallowDepth = 24;
    private double falseNegativeChance = 0.2;
    private String confidenceCap = "medium";
    private int energyCost = 0;
    private int energyCapacity = 0;
    private int maxDepth = 64;

    public ToolParameters() {}

    public String material() { return material; }

    public ToolParameters material(String material) {
        this.material = material;
        return this;
    }

    public int maxDurability() { return maxDurability; }

    public ToolParameters maxDurability(int maxDurability) {
        this.maxDurability = maxDurability;
        return this;
    }

    public int radius() { return radius; }

    public ToolParameters radius(int radius) {
        this.radius = radius;
        return this;
    }

    public int shallowDepth() { return shallowDepth; }

    public ToolParameters shallowDepth(int shallowDepth) {
        this.shallowDepth = shallowDepth;
        return this;
    }

    public double falseNegativeChance() { return falseNegativeChance; }

    public ToolParameters falseNegativeChance(double falseNegativeChance) {
        this.falseNegativeChance = falseNegativeChance;
        return this;
    }

    public String confidenceCap() { return confidenceCap; }

    public ToolParameters confidenceCap(String confidenceCap) {
        this.confidenceCap = confidenceCap;
        return this;
    }

    public int energyCost() { return energyCost; }

    public ToolParameters energyCost(int energyCost) {
        this.energyCost = energyCost;
        return this;
    }

    /**
     * 能量缓冲上限；{@code <= 0} 表示该工具既不储能也不耗能。
     *
     * <p>与 {@link #energyCost} 分开，是因为二者可以独立存在：耗能工具必须有容量，
     * 但也可以有「只当电池用、自己不耗能」的配件。容量属于材质参数，
     * 因此不写进物品 NBT（见 {@code ToolEnergy} 的说明）。
     */
    public int energyCapacity() { return energyCapacity; }

    public ToolParameters energyCapacity(int energyCapacity) {
        this.energyCapacity = energyCapacity;
        return this;
    }

    public int maxDepth() { return maxDepth; }

    public ToolParameters maxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
        return this;
    }

    /** 深拷贝。NBT 读取时以默认参数为基底，避免污染默认实例。 */
    public ToolParameters copy() {
        return new ToolParameters()
                .material(material)
                .maxDurability(maxDurability)
                .radius(radius)
                .shallowDepth(shallowDepth)
                .falseNegativeChance(falseNegativeChance)
                .confidenceCap(confidenceCap)
                .energyCost(energyCost)
                .energyCapacity(energyCapacity)
                .maxDepth(maxDepth);
    }
}
