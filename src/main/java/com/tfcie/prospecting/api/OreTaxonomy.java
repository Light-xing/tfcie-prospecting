package com.tfcie.prospecting.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tfcie.prospecting.api.enums.DensityClass;
import com.tfcie.prospecting.api.enums.Magnetism;
import com.tfcie.prospecting.api.enums.OreGroup;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TFC 矿种表：矿族分类 + <b>物性表</b>（物探工具的响应依据）。
 *
 * <h2>数据驱动，不硬编码</h2>
 * 本类<b>不内置任何矿种</b>，全部数据来自数据文件
 * {@code data/<namespace>/prospecting/ore_properties/*.json}：
 * <ul>
 *   <li><b>内置默认</b>：jar 内自带一份 {@code tfcie_prospecting:default.json}
 *       （即 {@link #BASELINE_RESOURCE}），类初始化时从 classpath 读取，
 *       保证在任何重载发生前也有可用的基线，并让纯逻辑单测无需游戏运行时；</li>
 *   <li><b>数据包覆盖</b>：服务端重载时由
 *       {@code OrePropertyReloadListener} 读取资源栈中的全部同类文件，
 *       在基线之上逐项合并 —— 整合包可以覆盖单个矿种，或用
 *       {@code "replace": true} 整表替换。</li>
 * </ul>
 *
 * <h2>文件格式</h2>
 * <pre>
 * {
 *   "replace": false,
 *   "ores": {
 *     "native_copper": {
 *       "group": "metallic",      // metallic | non_metallic | gem | unknown
 *       "zh_cn": "自然铜",
 *       "conductive": true,       // 感应探棒响应
 *       "magnetism": "none",      // none | weak | strong（磁力探针响应）
 *       "density": "high",        // low | normal | high（密度探锤响应）
 *       "confused_with": "native_gold"   // 可选：相似矿物混淆的目标
 *     }
 *   }
 * }
 * </pre>
 *
 * <h2>标定要点（已实测 TFC 1.20.x 数据包）</h2>
 * <ul>
 *   <li>宝石为 <b>8 种</b>：Diamond / Emerald / Lapis Lazuli / Amethyst / Opal /
 *       Ruby / Sapphire / Topaz。Ruby、Sapphire、Topaz 在 1.20.x 确实存在
 *       （Sapphire 由温泉、Topaz 由火山裂隙生成，二者都在
 *       {@code configured_feature/vein/} 之外；Ruby 无世界生成，仅经淘洗 / 溜槽获得）；</li>
 *   <li>孔雀石是碳酸铜 → 不导电；硅镁镍矿是风化硅酸盐 → 非导电 / 非磁性 / 非常密度
 *       （物探盲区，只能靠钻探发现）；</li>
 *   <li>铁矿三形态磁性分化：磁铁矿强磁、赤铁矿弱磁、褐铁矿无磁；</li>
 *   <li>黄铁矿以 {@code fake_native_gold}（愚人金）矿脉生成，是踏勘镐「相似矿物混淆」
 *       假阳性的官方原生依据。</li>
 * </ul>
 */
public final class OreTaxonomy {

    /** jar 内置基线数据的位置。 */
    public static final String BASELINE_RESOURCE =
            "/data/tfcie_prospecting/prospecting/ore_properties/default.json";

    /** 数据包中存放物性表的目录。 */
    public static final String DIRECTORY = "prospecting/ore_properties";

    private static final Gson GSON = new Gson();

    /**
     * 单个矿种条目。
     *
     * @param conductive  是否导电（感应探棒响应）
     * @param magnetism   磁性（磁力探针响应）
     * @param density     密度档（密度探锤响应）
     * @param confusedWith 相似矿物混淆的目标矿种 id（可为 null）；踏勘镐据此制造假阳性
     */
    public record Entry(String id,
                        OreGroup group,
                        String zhName,
                        boolean conductive,
                        Magnetism magnetism,
                        DensityClass density,
                        @Nullable String confusedWith) {

        /** 是否构成「相似矿物混淆」干扰源。 */
        public boolean confusing() {
            return confusedWith != null;
        }
    }

    /** 类初始化即读入内置基线，保证重载前也可用、且纯逻辑单测无需游戏运行时。 */
    private static final Map<String, Entry> BASELINE = loadBaseline();

    private static volatile Map<String, Entry> current = BASELINE;

    // ---------------- 查询 ----------------

    /** 按 lowercase snake_case id 查询（如 {@code "native_copper"}）。未收录返回 {@code null}。 */
    @Nullable
    public static Entry byId(String id) {
        return id == null ? null : current.get(id);
    }

    /** 按 TFC 枚举名查询（如 {@code "NATIVE_COPPER"}）。 */
    @Nullable
    public static Entry byTfcEnumName(String enumName) {
        return enumName == null ? null : byId(enumName.toLowerCase(Locale.ROOT));
    }

    /** 矿族；未收录返回 {@link OreGroup#UNKNOWN}。 */
    public static OreGroup groupOf(String id) {
        Entry e = byId(id);
        return e == null ? OreGroup.UNKNOWN : e.group();
    }

    /** 中文显示名；未收录返回原 id。 */
    public static String displayName(String id) {
        Entry e = byId(id);
        return e == null ? id : e.zhName();
    }

    /** 是否导电（感应探棒响应）。未收录视为不导电。 */
    public static boolean isConductive(String id) {
        Entry e = byId(id);
        return e != null && e.conductive();
    }

    /** 磁性（磁力探针响应）。未收录视为无磁性。 */
    public static Magnetism magnetismOf(String id) {
        Entry e = byId(id);
        return e == null ? Magnetism.NONE : e.magnetism();
    }

    /** 密度档（密度探锤响应）。未收录视为常密度。 */
    public static DensityClass densityOf(String id) {
        Entry e = byId(id);
        return e == null ? DensityClass.NORMAL : e.density();
    }

    /** 相似矿物混淆目标；无则返回 {@code null}。 */
    @Nullable
    public static String confusedWith(String id) {
        Entry e = byId(id);
        return e == null ? null : e.confusedWith();
    }

    /** 全部矿种（不可变快照，按数据文件顺序）。 */
    public static Map<String, Entry> all() {
        return current;
    }

    /** 全部矿种 id（不可变快照，按数据文件顺序）；供按方块名推断矿种时遍历已知 id。 */
    public static Set<String> ids() {
        return current.keySet();
    }

    /** 该 id 是否为已收录矿种；用于按名字匹配时的存在性判定。 */
    public static boolean contains(String id) {
        return id != null && current.containsKey(id);
    }

    public static int size() {
        return current.size();
    }

    /** 内置基线（不可变）。 */
    public static Map<String, Entry> baseline() {
        return BASELINE;
    }

    // ---------------- 数据装载 ----------------

    /**
     * 用数据包内容替换当前表。<b>仅应由 {@code OrePropertyReloadListener} 在服务端重载时调用。</b>
     */
    public static void replace(Map<String, Entry> entries) {
        current = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /**
     * 从一个 JSON 文件对象解析并合并到 {@code target}。
     *
     * @param replaceFirst 该文件声明 {@code "replace": true} 时，先清空再合并
     */
    public static Map<String, Entry> merge(Map<String, Entry> target, JsonObject root, boolean replaceFirst) {
        final Map<String, Entry> out = replaceFirst ? new LinkedHashMap<>() : new LinkedHashMap<>(target);
        final JsonElement oresElement = root.get("ores");
        if (oresElement == null || !oresElement.isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : oresElement.getAsJsonObject().entrySet()) {
            final String id = e.getKey();
            if (id.startsWith("_")) {
                continue;   // 注释键
            }
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            out.put(id, parseEntry(id, e.getValue().getAsJsonObject()));
        }
        return out;
    }

    private static Entry parseEntry(String id, JsonObject o) {
        final OreGroup group = readEnum(o, "group", OreGroup.class, OreGroup.UNKNOWN);
        final Magnetism magnetism = readEnum(o, "magnetism", Magnetism.class, Magnetism.NONE);
        final DensityClass density = readEnum(o, "density", DensityClass.class, DensityClass.NORMAL);
        final boolean conductive = !o.has("conductive") || o.get("conductive").getAsBoolean();
        final String zhName = o.has("zh_cn") ? o.get("zh_cn").getAsString() : id;
        final String confusedWith = o.has("confused_with") && !o.get("confused_with").isJsonNull()
                ? o.get("confused_with").getAsString()
                : null;
        return new Entry(id, group, zhName, conductive, magnetism, density, confusedWith);
    }

    private static <E extends Enum<E> & WireEnum> E readEnum(JsonObject o, String key, Class<E> type, E fallback) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return fallback;
        }
        return WireEnum.fromWire(type, o.get(key).getAsString(), fallback);
    }

    private static Map<String, Entry> loadBaseline() {
        try (InputStream in = OreTaxonomy.class.getResourceAsStream(BASELINE_RESOURCE)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            final JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return Collections.emptyMap();
            }
            return Collections.unmodifiableMap(merge(Map.of(), root.getAsJsonObject(), true));
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private OreTaxonomy() {}
}
