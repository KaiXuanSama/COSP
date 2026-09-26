package com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 出站装配支线的<strong>装配验证</strong>：三条线路的 {@link OutboundRequestStage} 真的被
 * Spring 收集并进了查表吗。
 *
 * <h2>为何必须单独验（与 {@code RequestBodyStageSpringWiringTests} 同一理由，但语义相反）</h2>
 * 出站支线的未命中语义是<strong>报错</strong>而非跳过：每条上游线路都必须能解析出地址才能发请求。
 * 但「漏了 {@code @Component}」的失败点被推迟到了<strong>运行期第一次请求</strong> ——
 * 那时 {@code OutboundRequestStageRegistry.require} 才抛 {@code IllegalStateException}。
 * 本类把这个检查前移到装配期：断言三条线路都收集到、查得到，漏一个当场红。
 *
 * <p>这与执行器表（{@code RequestPipelineSpringWiringTests.executorRegistryCoversAllThreeProtocols}）
 * 同一取向：未命中是「装配坏了」，故用「三协议都 require 得到」来守。
 */
@SpringBootTest(classes = CopilotOllamaProxyApplication.class)
class OutboundRequestStageSpringWiringTests {

    @Autowired
    private OutboundRequestStageRegistry registry;

    @Autowired
    private OutboundRequestAssembler assembler;

    @Autowired
    private List<OutboundRequestStage> stages;

    @Test
    @DisplayName("集合注入收齐三条出站支线（CHAT / MESSAGES / RESPONSES）")
    void collectionInjectionCollectsAllThreeStages() {
        assertThat(stages)
                .as("三条线路各一份出站支线（@Component 在扫描范围内）")
                .hasSize(3);
        assertThat(stages.stream().map(OutboundRequestStage::protocol))
                .containsExactlyInAnyOrder(WireProtocol.CHAT, WireProtocol.MESSAGES, WireProtocol.RESPONSES);
    }

    @Test
    @DisplayName("三协议都 require 得到 —— 漏一个会让那条线路一请求就报装配错误")
    void everyProtocolResolvesToItsStage() {
        for (WireProtocol protocol : WireProtocol.values()) {
            assertThatCode(() -> registry.require(protocol))
                    .as("协议 %s 必须有出站支线 —— 否则那条线路解析不出地址、发不出请求", protocol)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("每个协议映射到自己类型的出站支线实现")
    void eachProtocolMapsToItsOwnStage() {
        assertThat(registry.require(WireProtocol.CHAT))
                .isInstanceOf(com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.chat.ChatOutboundStage.class);
        assertThat(registry.require(WireProtocol.MESSAGES))
                .isInstanceOf(com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.messages.MessagesOutboundStage.class);
        assertThat(registry.require(WireProtocol.RESPONSES))
                .isInstanceOf(com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.responses.ResponsesOutboundStage.class);
    }

    @Test
    @DisplayName("装配器由容器提供")
    void assemblerIsWiredAsBean() {
        assertThat(assembler).as("出站装配器必须由容器提供").isNotNull();
    }

    @Test
    @DisplayName("三条支线的地址解析读各自的列（协议特有的「发去哪」）")
    void eachStageResolvesItsOwnBaseUrlColumn() {
        // 三列各配一个可区分的地址，验证每条支线读的是自己那列。
        ProviderRuntimeConfiguration provider = new ProviderRuntimeConfiguration(
                "relay", "https://base.example", "key", List.of(),
                "[]", "{\"version\":2,\"groups\":[]}",
                ProviderRuntimeConfiguration.DEFAULT_SUPPORTED_PROTOCOLS_JSON,
                "https://anthropic.example", "https://responses.example", false,
                com.kaixuan.copilot_ollama_proxy.application.runtime.AuthHeaderSetting.DEFAULT_AUTH_HEADER_JSON);

        assertThat(registry.require(WireProtocol.CHAT).resolveBaseUrl(provider))
                .as("Chat 读 base_url").isEqualTo("https://base.example");
        assertThat(registry.require(WireProtocol.MESSAGES).resolveBaseUrl(provider))
                .as("Messages 读 anthropic_base_url").isEqualTo("https://anthropic.example");
        assertThat(registry.require(WireProtocol.RESPONSES).resolveBaseUrl(provider))
                .as("Responses 读 responses_base_url").isEqualTo("https://responses.example");
    }

    @Test
    @DisplayName("同一协议两个实现 → 构造期抛 IllegalStateException")
    void duplicateProtocolThrows() {
        assertThatThrownBy(() -> new OutboundRequestStageRegistry(List.of(
                new com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.chat.ChatOutboundStage(),
                new com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.chat.ChatOutboundStage())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT")
                .hasMessageContaining("两个实现");
    }

    @Test
    @DisplayName("未命中即报错（与请求体支线的「跳过」相反）")
    void missingProtocolThrows() {
        // 空注册表：任何协议都查不到 —— require 必须抛，而不是返回 null / 跳过。
        OutboundRequestStageRegistry empty = new OutboundRequestStageRegistry(List.of());
        assertThatThrownBy(() -> empty.require(WireProtocol.CHAT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有出站装配实现");
    }
}
