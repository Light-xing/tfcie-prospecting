package com.tfcie.prospecting.common.prospecting;

import com.tfcie.prospecting.api.ProspectingResult;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Map;

/**
 * 一次读数的展示装配器：同时维护<b>两条出口</b>，并保证二者内容一致。
 *
 * <h2>它解决什么</h2>
 * 六件工具的读数反馈都要同时产出：
 * <ul>
 *   <li><b>可本地化的 Component</b> —— 玩家看到的聊天栏文本；</li>
 *   <li><b>纯文本</b> —— 装进 {@link ProspectingResult#message()}，供给智能体与命令通道。</li>
 * </ul>
 * 各工具原先都手写这份「同一段注记既拼 Component 又拼 StringBuilder」的逻辑，
 * 六处各写三到五遍。任何一处漏拼，玩家与智能体读到的内容就会不一致 —— 这是最容易
 * 悄悄出错的地方，因此收敛到本类里只写一遍。
 *
 * <h2>用法</h2>
 * <pre>
 * final Readout readout = Readout.of(KEY_PREFIX, "result", a, b, c);
 * if (r.interference()) {
 *     readout.note("interference");     // 同时进入两条出口
 * }
 * return ProspectOutcome.of(readout.result(ID, r.toData()), readout.display());
 * </pre>
 */
public final class Readout {

    private final String keyPrefix;
    private final MutableComponent display;
    private final StringBuilder plain;

    private Readout(String keyPrefix, Component base) {
        this.keyPrefix = keyPrefix;
        this.display = base.copy();
        this.plain = new StringBuilder(base.getString());
    }

    /** 以 {@code keyPrefix + baseKey} 为底文起头。 */
    public static Readout of(String keyPrefix, String baseKey, Object... baseArgs) {
        return new Readout(keyPrefix, Component.translatable(keyPrefix + baseKey, baseArgs));
    }

    /** 以既有 Component 为底文起头（供多分支拼底文的工具使用）。 */
    public static Readout ofComponent(String keyPrefix, Component base) {
        return new Readout(keyPrefix, base);
    }

    /**
     * 追加一条注记，<b>同时写进 Component 与纯文本</b>。
     *
     * @param noteKey 相对 {@code keyPrefix} 的键名
     */
    public Readout note(String noteKey, Object... args) {
        final Component note = Component.translatable(keyPrefix + noteKey, args);
        display.append(note);
        plain.append(note.getString());
        return this;
    }

    /** 条件成立时才追加，语义等同 {@code if (condition) note(...)}。 */
    public Readout noteIf(boolean condition, String noteKey, Object... args) {
        return condition ? note(noteKey, args) : this;
    }

    /** 玩家可见文本（未本地化前的原始 Component，由客户端按语言解析）。 */
    public Component display() {
        return display;
    }

    /** 纯文本出口，供 {@link ProspectingResult#message()} 与智能体使用。 */
    public String plain() {
        return plain.toString();
    }

    /** 装配成功结果：纯文本出口 + 结构化数据。 */
    public ProspectingResult result(String toolId, Map<String, Object> data) {
        return ProspectingResult.ok(toolId, plain.toString(), data);
    }
}
