package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 3.2 的<strong>装配验证</strong>：真实容器是否把两个翻译器收进了 {@link TranslatorRegistry}。
 *
 * <h2>为何单有纯单测不够</h2>
 * {@code TranslatorRegistryTests} 手工 {@code new} 注册表，验的是<strong>查表逻辑</strong>；
 * 它无法回答「容器里的两张表到底有没有东西」。而查表基建的前提是
 * 「{@code @Component} 必须在组件扫描范围内」—— 那是方向文档 §2.3.1 实测出来的第一条前提
 * （第一版探针把实现写成测试类嵌套类，拿到了空 List）。
 *
 * <p>这个失效模式在本步<strong>不响</strong>：注册表以空表正常启动，容器启动成功、
 * 既有测试全绿，直到第一个跨协议请求才暴露为
 * {@code ProtocolTranslationNotSupportedException}（去程未命中）——
 * 症状与「供应商没勾协议」几乎一样，排查会先怀疑数据库配置。
 * 因此这里断言的是「真实容器收齐了」，而不是「逻辑正确」。
 *
 * <h2>为何连实例同一性也要断言</h2>
 * 断言命中「某个 {@code ChatToMessagesRequestTranslator}」还不够 ——
 * 注册表完全可以自己 {@code new} 一个来应答，查表照样命中。
 * 因此与容器里的 Bean 做 {@code containsSame}：命中的必须<strong>就是</strong>那个 Bean，
 * 这才能证明「加一个 {@code @Component} 就自动接管」这条契约真的成立。
 */
@SpringBootTest(classes = CopilotOllamaProxyApplication.class)
class TranslatorRegistrySpringWiringTests {

    @Autowired
    private TranslatorRegistry translatorRegistry;

    @Autowired
    private ChatToMessagesRequestTranslator c2mRequestTranslator;

    @Autowired
    private MessagesToChatResponseTranslator m2cResponseTranslator;

    @Test
    @DisplayName("容器收齐两个翻译器：C2M 去程与回程都按 (CHAT, MESSAGES) 命中真实 Bean")
    void realContainerRegistersBothHalvesOfC2m() {
        assertThat(translatorRegistry)
                .as("注册表必须是容器 Bean —— 它有构造期固化的状态（两张表）")
                .isNotNull();

        assertThat(translatorRegistry.findRequestTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES))
                .as("去程未命中会让所有 C2M 请求报「翻译不支持」")
                .containsSame(c2mRequestTranslator);
        assertThat(translatorRegistry.findResponseTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES))
                .as("回程未命中会让 C2M 静默退化成透传上游原生事件")
                .containsSame(m2cResponseTranslator);
    }

    @Test
    @DisplayName("两张表相互独立：同一次查表不可能两边拿到同一个实例")
    void twoTablesAreIndependent() {
        RequestProtocolTranslator request = translatorRegistry
                .findRequestTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES).orElseThrow();
        ResponseProtocolTranslator response = translatorRegistry
                .findResponseTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES).orElseThrow();

        // 去程与回程分属两个类、方法形状不同（见 ProtocolTranslator 的说明），
        // 因此同一方向的两次查表必然拿到不同对象。若哪天合并成一张表，
        // 这里会红 —— 那时「去程有、回程无」这个中间态就表达不出来了。
        assertThat(request).isNotSameAs(response);
    }
}
