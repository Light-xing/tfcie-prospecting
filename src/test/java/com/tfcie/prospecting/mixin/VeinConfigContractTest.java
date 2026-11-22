package com.tfcie.prospecting.mixin;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * mixin 目标点的离线契约测试。
 *
 * <p>{@link VeinConfigMixin} 按名字注入 TFC 的 {@code VeinConfig#indicator()}；
 * 名字和返回类型一旦在 TFC 升级里变了，mixin 会静默失配（或加载期炸）。
 * 这里不启动游戏，只拿编译类路径上的 TFC 产物读字节码，把「注入点还在」钉成回归测试。
 */
public class VeinConfigContractTest {

    private static final String TARGET_CLASS = "net/dries007/tfc/world/feature/vein/VeinConfig.class";
    private static final String INDICATOR_METHOD = "indicator";
    private static final String INDICATOR_DESC = "()Ljava/util/Optional;";

    @Test
    public void veinConfigStillExposesTheIndicatorAccessor() throws IOException {
        final ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TARGET_CLASS)) {
            assertNotNull("TFC 的 VeinConfig 不在编译类路径上，契约测试无法运行", in);
            new ClassReader(in).accept(node, 0);
        }

        boolean found = false;
        for (MethodNode method : node.methods) {
            if (INDICATOR_METHOD.equals(method.name) && INDICATOR_DESC.equals(method.desc)) {
                found = true;
                break;
            }
        }
        assertTrue("TFC 更新后 VeinConfig#indicator() 的签名变了，mixin 注入点需要同步修改", found);
    }

    @Test
    public void mixinConfigListsTheVeinConfigMixin() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("tfcie_prospecting.mixins.json")) {
            assertNotNull("mixin 配置没有打进资源，发行 jar 里 MixinConfigs 清单会指向空文件", in);
            final String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue("mixin 配置里没有列出 VeinConfigMixin", json.contains("\"VeinConfigMixin\""));
        }
    }
}
