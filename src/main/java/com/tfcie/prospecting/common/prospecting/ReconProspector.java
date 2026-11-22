package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.OreTaxonomy;
import com.tfcie.prospecting.api.ToolParameters;
import com.tfcie.prospecting.api.enums.OreGroup;
import com.tfcie.prospecting.api.enums.Presence;
import com.tfcie.prospecting.config.ProspectingConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 踏勘镐的读数编排：前置条件 → 浅带扫描 → 假阴性 → 干扰判读 → 收敛/饱和状态机 → 读数。
 *
 * <h2>设计文档 v1.3 的纪律</h2>
 * <ul>
 *   <li>只输出<b>迹象档位 + 矿族 + 疑似矿名</b>，<b>永不输出坐标或精确深度</b>
 *       （踏勘镐属推断类工具）；</li>
 *   <li>置信度<b>封顶 medium</b>，永不 high；</li>
 *   <li>假阴性<b>不计数也不中断</b> —— 触发时状态原样保留、不调用状态机；</li>
 *   <li>干扰源限定为「敲岩目视」才会有的三类：异地漂砾、相似矿物混淆、覆盖层污染。</li>
 * </ul>
 */
public final class ReconProspector {

    /** 前置条件未满足时的消息键（需要凿击裸露岩面）。 */
    public static final String REJECT_NEED_ROCK = "message.tfcie_prospecting.recon_pick.need_rock";

    /** 读数结果：成功给出读数，或以上述消息键拒绝。 */
    public record Outcome(@Nullable ReconReading reading, @Nullable String rejectionKey) {

        public static Outcome of(ReconReading reading) {
            return new Outcome(reading, null);
        }

        public static Outcome reject(String key) {
            return new Outcome(null, key);
        }

        public boolean accepted() {
            return reading != null;
        }
    }

    /** 行为参数（材质参数 + 全局配置的合成视图）。 */
    public record Config(int radius,
                         int shallowDepth,
                         int convergeSamples,
                         int saturationAfter,
                         boolean exposeOreName,
                         boolean requiresExposedRock,
                         double falseNegativeChance,
                         int strongThreshold,
                         double boulderFalsePositiveChance,
                         double oreConfusionChance,
                         double overburdenContaminationChance) {

        /**
         * 由材质参数（{@link ToolParameters}，可被 NBT 覆盖）与全局配置合成。
         * 探测几何与假阴性属「可被材质影响」的参数，故取自材质参数；
         * 收敛/饱和阈值与干扰概率属全局行为，故取自配置。
         */
        public static Config from(ToolParameters params) {
            return new Config(
                    params.radius(),
                    params.shallowDepth(),
                    ProspectingConfig.ReconPick.convergeSamples.get(),
                    ProspectingConfig.ReconPick.saturationAfter.get(),
                    ProspectingConfig.ReconPick.exposeOreName.get(),
                    ProspectingConfig.ReconPick.requiresExposedRock.get(),
                    params.falseNegativeChance(),
                    ProspectingConfig.ReconPick.strongThreshold.get(),
                    ProspectingConfig.ReconPick.boulderFalsePositive.get(),
                    ProspectingConfig.ReconPick.oreConfusion.get(),
                    ProspectingConfig.ReconPick.overburdenContamination.get());
        }
    }

    /** 单次采样的判读结果（内部）。 */
    private record Judgment(@Nullable String oreId, OreGroup group, Interference interference, int hitBlocks) {}

    /**
     * 执行一次踏勘采样。
     *
     * @param level  世界（服务端）
     * @param stack  手中的踏勘镐（采样会话存于其 NBT）
     * @param origin 探测原点 —— 玩家脚下
     * @param chipped 被凿击的方块位置（前置条件检查对象）
     * @param random 随机源（服务端）
     */
    public static Outcome sample(Level level, ItemStack stack, BlockPos origin, BlockPos chipped,
                                 Config cfg, RandomSource random) {
        if (cfg.requiresExposedRock() && !ReconScanner.isExposedRock(level, chipped)) {
            return Outcome.reject(REJECT_NEED_ROCK);
        }

        final ReconScanner.ScanResult scan = ReconScanner.scan(level, origin, cfg.radius(), cfg.shallowDepth());
        final ChunkPos chunk = new ChunkPos(origin);
        final ReconSession session = ReconSession.forChunk(stack, chunk.x, chunk.z);

        // 已饱和：仅重复既有结论，无新增信息（换区块才可继续）
        if (session.saturated()) {
            return Outcome.of(repeat(session, scan, cfg));
        }

        // 假阴性：确有矿却未检出 —— 不计数、不中断，状态原样保留
        if (scan.hasOre() && random.nextDouble() < cfg.falseNegativeChance()) {
            return Outcome.of(new ReconReading(
                    Presence.NONE, OreGroup.UNKNOWN, null,
                    session.confidence(), session.converged(), false, false, Interference.NONE));
        }

        final Judgment judgment = judge(level, origin, scan, cfg, random);

        final ReconSession next = session.accept(
                judgment.oreId(), judgment.group(), cfg.convergeSamples(), cfg.saturationAfter());
        ReconSession.write(stack, next);

        return Outcome.of(build(next, judgment, cfg, false));
    }

    /** 判读与干扰。{@code hitBlocks} 用于推导迹象档位。 */
    private static Judgment judge(Level level, BlockPos origin, ReconScanner.ScanResult scan,
                                  Config cfg, RandomSource random) {
        if (scan.hasOre()) {
            final String oreId = scan.dominantOreId();
            // 相似矿物混淆：物性表中声明了 confused_with 的矿种会被误报为其目标矿种
            // （1.20 的原生依据：黄铁矿 / 愚人金 → 误判为自然金）
            final String confusedWith = OreTaxonomy.confusedWith(oreId);
            if (confusedWith != null && random.nextDouble() < cfg.oreConfusionChance()) {
                return new Judgment(confusedWith, OreTaxonomy.groupOf(confusedWith),
                        Interference.ORE_CONFUSION, scan.totalOreBlocks());
            }
            // 覆盖层污染：矿体之上为松散覆盖层，迹象可能被搬运/稀释
            final boolean overburden = ReconScanner.isLooseOverburden(level, origin.below())
                    && random.nextDouble() < cfg.overburdenContaminationChance();
            return new Judgment(oreId, OreTaxonomy.groupOf(oreId),
                    overburden ? Interference.OVERBURDEN : Interference.NONE,
                    scan.totalOreBlocks());
        }

        // 异地漂砾：只敲到被搬运来的地表矿屑，却误报为本地有矿
        if (scan.looseOnly() && random.nextDouble() < cfg.boulderFalsePositiveChance()) {
            final String looseId = scan.dominantLooseOreId();
            return new Judgment(looseId, OreTaxonomy.groupOf(looseId),
                    Interference.BOULDER, scan.totalLooseBlocks());
        }

        // 真·无矿：仍是有效采样（矿族未知），计入收敛/饱和
        return new Judgment(null, OreGroup.UNKNOWN, Interference.NONE, 0);
    }

    private static ReconReading build(ReconSession session, Judgment judgment, Config cfg, boolean repeated) {
        final Presence presence = ReconSession.presenceOf(judgment.hitBlocks(), cfg.strongThreshold());
        final String suspected = (session.converged() && cfg.exposeOreName()) ? session.suspectedOreId() : null;
        return new ReconReading(
                presence,
                judgment.group(),
                suspected,
                session.confidence(),
                session.converged(),
                session.saturated(),
                repeated,
                judgment.interference());
    }

    /** 饱和后的重复读数：仅复述既有结论。 */
    private static ReconReading repeat(ReconSession session, ReconScanner.ScanResult scan, Config cfg) {
        final Presence presence = ReconSession.presenceOf(scan.totalOreBlocks(), cfg.strongThreshold());
        final String suspected = (session.converged() && cfg.exposeOreName()) ? session.suspectedOreId() : null;
        final OreGroup group = session.group() == null ? OreGroup.UNKNOWN : session.group();
        return new ReconReading(presence, group, suspected, session.confidence(),
                session.converged(), true, true, Interference.NONE);
    }

    private ReconProspector() {}
}
