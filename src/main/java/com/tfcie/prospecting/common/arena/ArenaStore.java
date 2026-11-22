package com.tfcie.prospecting.common.arena;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tfcie.prospecting.TfcieProspecting;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 靶场配置的<b>磁盘仓储</b>：内置四图释放、实时读盘、真值输出目录。
 *
 * <h2>为什么配置放磁盘而不是 jar 里</h2>
 * 四张图是「可热改参数」的运维资产：调一次埋深、挪一个矿核，不该重新编译打包。
 * 所以 jar 内只保留一份出厂默认（{@code data/tfcie_prospecting/arenas/}），首次
 * {@code /prospect arena list} 或 {@code build} 时释放到
 * {@code <游戏目录>/config/tfcie_prospecting/arenas/}；<b>已存在的文件绝不覆盖</b>，
 * 用户的手动调参永远优先。每次 {@code build} 都重新读盘 —— 改完 JSON 立刻生效，不必重启。
 */
public final class ArenaStore {

    /** jar 内置默认配置的 classpath 目录。 */
    public static final String BUILTIN_RESOURCE_DIR = "/data/tfcie_prospecting/arenas/";

    /** 内置四张标准靶场的名字（顺序即释放顺序）。 */
    public static final List<String> BUILTIN_NAMES = List.of("map1", "map2", "map3", "map4");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String JSON_SUFFIX = ".json";

    /** 可热改配置目录：{@code <游戏目录>/config/tfcie_prospecting/arenas/}。 */
    public static Path arenaDir() {
        return FMLPaths.CONFIGDIR.get().resolve(TfcieProspecting.MOD_ID).resolve("arenas");
    }

    /** 真值输出目录：{@code <游戏目录>/tfcie_prospecting/arenas_output/}。 */
    public static Path outputDir() {
        return FMLPaths.GAMEDIR.get().resolve(TfcieProspecting.MOD_ID).resolve("arenas_output");
    }

    /** 某张图的真值文件路径。 */
    public static Path groundTruthPath(String name) {
        return outputDir().resolve(name + "_ground_truth.json");
    }

    /**
     * 释放 jar 内置的 map1~map4 到配置目录。
     *
     * <p>只补缺失的：已存在同名文件一律跳过（保留用户调参），因此可以安全地反复调用。
     *
     * @return 本次真正新释放出来的配置名（已存在的不在其中）
     */
    public static List<String> releaseDefaults() {
        final Path dir = arenaDir();
        final List<String> released = new ArrayList<>();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建配置目录：" + dir, e);
        }
        for (String name : BUILTIN_NAMES) {
            final Path dest = dir.resolve(name + JSON_SUFFIX);
            if (Files.exists(dest)) {
                continue;
            }
            final String builtin = readBuiltinResource(name);
            if (builtin == null) {
                TfcieProspecting.LOGGER.warn("[arena] jar 内缺少内置配置：{}{}", BUILTIN_RESOURCE_DIR, name);
                continue;
            }
            try {
                Files.writeString(dest, builtin, StandardCharsets.UTF_8);
                released.add(name);
            } catch (IOException e) {
                throw new UncheckedIOException("无法释放内置配置到 " + dest, e);
            }
        }
        if (!released.isEmpty()) {
            TfcieProspecting.LOGGER.info("[arena] 已释放内置靶场配置：{} -> {}", released, dir);
        }
        return released;
    }

    /** 配置目录下全部可用配置名（去掉 {@code .json}，按名字排序）。目录不存在时先释放默认。 */
    public static List<String> listNames() {
        final Path dir = arenaDir();
        if (!Files.isDirectory(dir)) {
            releaseDefaults();
        }
        final List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(JSON_SUFFIX))
                    .map(p -> p.getFileName().toString()
                            .substring(0, p.getFileName().toString().length() - JSON_SUFFIX.length()))
                    .sorted(Comparator.naturalOrder())
                    .forEach(names::add);
        } catch (IOException e) {
            throw new UncheckedIOException("无法列出配置目录：" + dir, e);
        }
        return names;
    }

    /** 该名字是否对应一个已释放的配置文件。 */
    public static boolean exists(String name) {
        return name != null && !name.isBlank()
                && Files.isRegularFile(arenaDir().resolve(name + JSON_SUFFIX));
    }

    /**
     * 实时读盘并解析一张图。若配置目录里还没有它，先尝试释放内置默认。
     *
     * @throws IllegalArgumentException 文件不存在、读盘失败或 JSON 结构非法
     */
    public static ArenaConfig load(String name) {
        if (!exists(name)) {
            releaseDefaults();
        }
        final Path file = arenaDir().resolve(name + JSON_SUFFIX);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("找不到靶场配置：" + name + "（期望路径 " + file + "）");
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return ArenaConfig.parse(reader, name);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取靶场配置失败：" + file, e);
        }
    }

    /** 读已导出的真值文件；缺失或损坏返回 {@code null}（starter 传送用，不该因此让命令失败）。 */
    @Nullable
    public static JsonObject loadGroundTruth(String name) {
        final Path file = groundTruthPath(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            final String json = Files.readString(file, StandardCharsets.UTF_8);
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            TfcieProspecting.LOGGER.warn("[arena] 真值文件损坏，忽略：{}", file, e);
            return null;
        }
    }

    /** 无 BOM UTF-8 写 JSON 文件（父目录自动创建）。 */
    public static void writeJson(Path file, JsonObject json) {
        try {
            final Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 JSON 失败：" + file, e);
        }
    }

    @Nullable
    private static String readBuiltinResource(String name) {
        final String path = BUILTIN_RESOURCE_DIR + name + JSON_SUFFIX;
        try (InputStream in = ArenaStore.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TfcieProspecting.LOGGER.warn("[arena] 读取内置配置失败：{}", path, e);
            return null;
        }
    }

    private ArenaStore() {}
}
