package com.tfcie.prospecting.common.prospecting;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tfcie.prospecting.api.OreTaxonomy;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 矿种物性表的数据包重载监听器。
 *
 * <p>读取资源栈中所有 {@code data/<namespace>/prospecting/ore_properties/*.json}，
 * 以 jar 内置基线（{@link OreTaxonomy#baseline()}）为底逐项合并，因此：
 * <ul>
 *   <li>整合包可在自己的命名空间下新增文件，<b>覆盖单个矿种</b>而不必碰代码；</li>
 *   <li>文件声明 {@code "replace": true} 时先清空再合并，可用于整表替换或删除条目；</li>
 *   <li>文件按 {@link ResourceLocation} 字典序遍历，保证合并结果确定。</li>
 * </ul>
 *
 * <p>仅在服务端重载（{@code AddReloadListenerEvent}）；物性表只在服务端参与读数计算。
 */
public final class OrePropertyReloadListener extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();

    public OrePropertyReloadListener() {
        super(GSON, OreTaxonomy.DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects,
                         ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        Map<String, OreTaxonomy.Entry> table = new LinkedHashMap<>(OreTaxonomy.baseline());

        final List<ResourceLocation> keys = new ArrayList<>(objects.keySet());
        keys.sort(Comparator.comparing(ResourceLocation::toString));

        for (ResourceLocation key : keys) {
            final JsonElement element = objects.get(key);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            final JsonObject root = element.getAsJsonObject();
            final boolean replace = root.has("replace")
                    && root.get("replace").isJsonPrimitive()
                    && root.get("replace").getAsBoolean();
            table = OreTaxonomy.merge(table, root, replace);
        }

        OreTaxonomy.replace(table);
        // 已知矿种集合变了，按方块名推断矿种的缓存必须作废（否则会残留旧表下的判定结果）
        ReconScanner.invalidateOreNameCache();
    }
}
