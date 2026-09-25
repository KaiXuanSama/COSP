package com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.chat.ChatOutboundStage;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.messages.MessagesOutboundStage;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.responses.ResponsesOutboundStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OutboundRequestAssembler} 的装配契约 —— 出站头与地址的产出、顺序、协议差异。
 *
 * <h2>它守什么（阶段 4 刀 3 B 起）</h2>
 * 出站头装配自刀 3 B 从三个执行器的 {@code buildWebClient} 收归发送前块。本类钉住那次搬迁的
 * 三条不变式，它们都是从旧 {@code buildWebClient} 逐字保留下来的：
 * <ul>
 *   <li><strong>地址按协议选列</strong>：Chat 读 base_url、Messages 读 anthropic_base_url、
 *       Responses 读 responses_base_url，且都经 normalizeBaseUrl 归一（去尾斜杠）；</li>
 *   <li><strong>三层头装配照旧</strong>：鉴权头按供应商配置装、下游头透传、规则最终决定；</li>
 *   <li><strong>协议头在规则之后 set-if-absent</strong>：{@code anthropic-version} 只有 Messages 有，
 *       且供应商请求头规则能覆盖它 —— 这条顺序错了会让「规则拥有最终决定权」失效。</li>
 * </ul>
 *
 * <p>头名与配置的完整对应由 {@code ProviderRequestHeaderServiceTests} 穷举，本类只验
 * 装配器把那条链接上了、且协议特有的两件事（选列、补版本头）落在正确位置。
 */
class OutboundRequestAssemblerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboundRequestAssembler assembler() {
        return new OutboundRequestAssembler(
                new ProviderRequestHeaderService(objectMapper),
                new OutboundRequestStageRegistry(List.of(
                        new ChatOutboundStage(), new MessagesOutboundStage(), new ResponsesOutboundStage())),
                objectMapper);
    }

    private RequestPipelineContext ctxFor(ProviderRuntimeConfiguration provider, WireProtocol upstream) {
        RequestPipelineContext ctx = RequestPipelineContext.of(
                new LinkedHashMap<>(Map.of("model", "m")), "m", upstream, upstream,
                provider, HttpHeaders.EMPTY, "req", null, false);
        return ctx;
    }

    private ProviderRuntimeConfiguration provider(String baseUrl, String anthropicBaseUrl,
                                                  String responsesBaseUrl, String headerRulesJson) {
        return new ProviderRuntimeConfiguration("relay", baseUrl, "actual-key", List.of(),
                headerRulesJson, "{\"version\":2,\"groups\":[]}",
                ProviderRuntimeConfiguration.DEFAULT_SUPPORTED_PROTOCOLS_JSON,
                anthropicBaseUrl, responsesBaseUrl, false,
                com.kaixuan.copilot_ollama_proxy.application.runtime.AuthHeaderSetting.DEFAULT_AUTH_HEADER_JSON);
    }

    @Test
    @DisplayName("Chat：读 base_url、装默认 Bearer、无 anthropic-version")
    void chatResolvesBaseUrlAndBearer() {
        RequestPipelineContext ctx = ctxFor(
                provider("https://base.example/v1/", "", "", "[]"), WireProtocol.CHAT);

        assembler().assemble(ctx);

        assertThat(ctx.outboundBaseUrl())
                .as("Chat 读 base_url，尾斜杠被归一化")
                .isEqualTo("https://base.example/v1");
        assertThat(ctx.outboundHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .as("默认鉴权头装配：Bearer + 供应商 key")
                .isEqualTo("Bearer actual-key");
        assertThat(ctx.outboundHeaders().containsKey("anthropic-version"))
                .as("Chat 线路不补版本头")
                .isFalse();
    }

    @Test
    @DisplayName("Messages：读 anthropic_base_url、补 anthropic-version")
    void messagesResolvesAnthropicUrlAndVersionHeader() {
        RequestPipelineContext ctx = ctxFor(
                provider("https://base.example", "https://anthropic.example/v1", "", "[]"),
                WireProtocol.MESSAGES);

        assembler().assemble(ctx);

        assertThat(ctx.outboundBaseUrl())
                .as("Messages 读 anthropic_base_url，不回退 base_url")
                .isEqualTo("https://anthropic.example/v1");
        assertThat(ctx.outboundHeaders().getFirst("anthropic-version"))
                .as("Anthropic 必需的版本头由出站支线补上")
                .isEqualTo("2023-06-01");
    }

    @Test
    @DisplayName("Messages：anthropic_base_url 为空回退 base_url")
    void messagesFallsBackToBaseUrlWhenAnthropicUrlBlank() {
        RequestPipelineContext ctx = ctxFor(
                provider("https://base.example/v1", "", "", "[]"), WireProtocol.MESSAGES);

        assembler().assemble(ctx);

        assertThat(ctx.outboundBaseUrl()).isEqualTo("https://base.example/v1");
    }

    @Test
    @DisplayName("Responses：读 responses_base_url、无协议头")
    void responsesResolvesResponsesUrl() {
        RequestPipelineContext ctx = ctxFor(
                provider("https://base.example", "", "https://responses.example", "[]"),
                WireProtocol.RESPONSES);

        assembler().assemble(ctx);

        assertThat(ctx.outboundBaseUrl()).isEqualTo("https://responses.example");
        assertThat(ctx.outboundHeaders().containsKey("anthropic-version")).isFalse();
    }

    /**
     * 协议头在请求头规则<strong>之后</strong> set-if-absent —— 规则能覆盖 {@code anthropic-version}。
     *
     * <p>这是从旧 {@code buildWebClient} 逐字保留的顺序：某些中转站要求特定版本，
     * 用户用请求头规则写死一个值，装配器补的默认值不能把它顶掉。
     */
    @Test
    @DisplayName("请求头规则可覆盖 anthropic-version（协议头在规则之后 set-if-absent）")
    void ruleCanOverrideAnthropicVersion() {
        String rules = "[{\"key\":\"anthropic-version\",\"value\":\"2099-01-01\"}]";
        RequestPipelineContext ctx = ctxFor(
                provider("https://base.example", "https://anthropic.example", "", rules),
                WireProtocol.MESSAGES);

        assembler().assemble(ctx);

        assertThat(ctx.outboundHeaders().getFirst("anthropic-version"))
                .as("规则先写了值，出站支线的 set-if-absent 不该覆盖它")
                .isEqualTo("2099-01-01");
    }
}
