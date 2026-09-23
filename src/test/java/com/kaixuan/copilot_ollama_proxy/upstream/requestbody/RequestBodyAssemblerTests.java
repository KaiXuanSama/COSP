package com.kaixuan.copilot_ollama_proxy.upstream.requestbody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.testing.PipelineContexts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RequestBodyAssembler} 的行为验证 —— 阶段 4 刀 1 把请求体装配从三个执行器收归主干。
 *
 * <h2>这些用例的来历</h2>
 * 它们此前分散在 {@code AbstractUpstreamChatServiceTests}（走 {@code exposePrepareRequestBody}）
 * 与 {@code GenericOpenAiChatServiceRequestBodyRulesTests}（走 {@code customizeRequestBody} 钩子）。
 * 刀 1 之后请求体装配不再在执行器里，钩子也拆除了，因此这些验证随之搬到装配器 ——
 * 断言的<strong>意图</strong>一字未改：模型名解析、协议字段写入、思考深度四档、
 * 请求体规则、null 清洗顺序。只是被测的入口从执行器的私有方法变成了主干的装配器。
 *
 * <h2>为何用 CHAT 协议做载体</h2>
 * 装配序列里协议无关的部分（copy / resolveModel / writeProtocolFields / bodyRules / removeNull）
 * 三条线路逐字相同；协议特定的思考注入由 {@code ChatThinkingStage} 写
 * {@code reasoning_effort}。选 CHAT 是因为这些用例原本就在 Chat 执行器上，
 * 迁移后判据不变、可逐条对照。系统提示词抬升与 max_tokens 补齐（MESSAGES 特有）
 * 由 {@code GenericAnthropicChatServiceTests} 与 {@code RequestBodyStageSpringWiringTests} 覆盖。
 */
class RequestBodyAssemblerTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RequestBodyAssembler assembler = new RequestBodyAssembler(
            PipelineContexts.registryWithAllBodyStages(MAPPER),
            new RequestBodyRuleEngine(MAPPER));

    /**
     * 跑一遍装配，返回装配后的请求体。
     *
     * <p>用 {@link RequestPipelineContext#of} 直接构造完整形态的 ctx（bodyProtocol = CHAT），
     * 模型名走 {@code model} 参数 —— 与旧的 {@code exposePrepareRequestBody(request, stream, model, provider)}
     * 语义对齐（那里的 {@code model} 是路由解析出的模型名）。
     */
    private Map<String, Object> assemble(Map<String, Object> request, boolean stream,
                                         String model, ProviderRuntimeConfiguration provider) {
        RequestPipelineContext ctx = RequestPipelineContext.of(
                new LinkedHashMap<>(request), model, WireProtocol.CHAT, WireProtocol.CHAT,
                provider, HttpHeaders.EMPTY, "req-test", null, stream);
        assembler.assemble(ctx);
        return ctx.body();
    }

    // ==================== 模型名 / 协议字段 / null 清洗 ====================

    /**
     * 模型名解析 + 协议字段写入 + null 字段清洗。
     *
     * <p>来历：{@code prepareRequestBodyResolvesFallbackModelAndRunsCustomizationHook}。
     * 原用例还断言了 {@code customized=true}（子类钩子的产物）—— 钩子已拆除，那条断言随之删去，
     * 其余意图（模型名回退、stream 写入、下游 null 字段被清）保留。
     */
    @Test
    void resolvesModelWritesProtocolFieldsAndStripsNullFields() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", null);
        request.put("temperature", 0.7);
        request.put("tool_choice", null);

        Map<String, Object> prepared = assemble(request, true, "fallback-model", provider());

        assertThat(prepared).containsEntry("model", "fallback-model");
        assertThat(prepared).containsEntry("stream", true);
        assertThat(prepared).doesNotContainKey("tool_choice");
    }

    /**
     * 规则产生的 null 不会发给上游 —— null 清洗必须排在规则之后。
     *
     * <p>来历：{@code requestBodyRulesRunBeforeNullStrippingSoRuleAssignedNullNeverReachesUpstream}。
     * 原用例用 {@code NullAssigningService} 的钩子把 {@code temperature} 置 null 来模拟
     * 「规则产出 null」；现在用一条<strong>真实规则</strong>（set_value: null）达到同一效果，
     * 更贴近生产 —— 「设置字段值」留空即置 null 是既定语义。
     */
    @Test
    void ruleAssignedNullIsStrippedBecauseCleanupRunsAfterRules() {
        String rules = """
                {"version":2,"groups":[{"id":"g","name":"g","order":0,"enabled":true,
                 "protocols":["CHAT"],"templateKeys":["x"],"previewBody":{},
                 "rules":[{"id":"r","order":0,"field":"temperature","array":false,
                  "conditional":false,"conditionMode":"all","conditions":[],
                  "operations":[{"type":"set_value","value":null}]}]}]}""";

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "m");
        request.put("temperature", 0.7);

        Map<String, Object> prepared = assemble(request, false, "m", providerWithRules(rules));

        assertThat(prepared).doesNotContainKey("temperature");
        assertThat(prepared).containsEntry("model", "m");
    }

    // ==================== 请求体规则（来自 GenericOpenAiChatServiceRequestBodyRulesTests） ====================

    /**
     * 请求体规则按 CHAT 协议筛选后执行 —— set_value 覆盖既有字段。
     *
     * <p>来历：{@code customizeRequestBodyUsesNewRuleSetAndDoesNotExecuteLegacyBodyTransforms}。
     * 那里的 V1 规则集经引擎归一为「仅 CHAT」，此处直接写 V2 CHAT 组，语义一致。
     */
    @Test
    void bodyRulesDeclaredForChatAreApplied() {
        String rules = """
                {"version":2,"groups":[{"id":"g1","name":"g1","order":0,"enabled":true,
                 "protocols":["CHAT"],"templateKeys":["custom"],"previewBody":{},
                 "rules":[{"id":"r1","order":0,"field":"temperature","array":false,
                  "conditional":false,"conditionMode":"all","conditions":[],
                  "operations":[{"type":"set_value","value":0.2}]}]}]}""";

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "mimo-v2.5-pro");
        request.put("temperature", 0.1);

        Map<String, Object> prepared = assemble(request, false, "mimo-v2.5-pro", providerWithRules(rules));

        assertThat(prepared).containsEntry("temperature", 0.2);
    }

    /**
     * MiMo 图片工具消息规则：数组模式 + 嵌套 edit_object 改 role、删 tool_call_id。
     *
     * <p>来历：{@code customizeRequestBodyAppliesMimoImageToolMessageRuleFromNewTableConfiguration}。
     * 这条同时验证数组模式与条件匹配，是规则引擎接进装配器后仍生效的证据。
     */
    @Test
    void bodyRulesRewriteMatchingArrayElements() {
        String rules = """
                {"version":2,"groups":[{"id":"g1","name":"g1","order":0,"enabled":true,
                 "protocols":["CHAT"],"templateKeys":["custom"],"previewBody":{},
                 "rules":[{"id":"mimo-messages","order":0,"field":"messages","array":true,"conditional":true,
                  "conditionMode":"all","conditions":[
                    {"path":"./content[*]/image_url","operator":"exists","value":null},
                    {"path":"./role","operator":"equals","value":"tool"}
                  ],"operations":[{"type":"edit_object","rules":[
                    {"id":"mimo-role","order":0,"field":"role","array":false,"conditional":false,
                     "conditionMode":"all","conditions":[],"operations":[{"type":"set_value","value":"user"}]},
                    {"id":"mimo-tid","order":1,"field":"tool_call_id","array":false,"conditional":false,
                     "conditionMode":"all","conditions":[],"operations":[{"type":"delete"}]}
                  ]}]}]}]}""";

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "mimo-v2.5-pro");
        request.put("messages", List.of(new LinkedHashMap<>(Map.of(
                "role", "tool",
                "tool_call_id", "call-image",
                "content", List.of(Map.of("type", "image_url",
                        "image_url", Map.of("url", "data:image/png;base64,abc")))))));

        Map<String, Object> prepared = assemble(request, false, "mimo-v2.5-pro", providerWithRules(rules));

        @SuppressWarnings("unchecked")
        Map<String, Object> message = ((List<Map<String, Object>>) prepared.get("messages")).getFirst();
        assertThat(message).containsEntry("role", "user").doesNotContainKey("tool_call_id");
    }

    // ==================== 思考深度四档（经 ChatThinkingStage） ====================

    @Test
    void modelConfiguredMaxReasoningEffortIsWrittenForChat() {
        Map<String, Object> prepared = assemble(
                new LinkedHashMap<>(), true, "model-a", providerWithReasoningEffort("Max"));

        assertThat(prepared).containsEntry("reasoning_effort", "max");
    }

    @Test
    void explicitReasoningEffortTakesPrecedenceOverModelConfiguration() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reasoning_effort", "low");

        Map<String, Object> prepared = assemble(request, false, "model-a", providerWithReasoningEffort("Max"));

        assertThat(prepared).containsEntry("reasoning_effort", "low");
    }

    /** 覆写模式无视下游携带的档位 —— V2 引入注入模式的全部目的。 */
    @Test
    void overrideModeReplacesDownstreamReasoningEffort() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reasoning_effort", "low");

        Map<String, Object> prepared = assemble(request, false, "model-a",
                providerWithReasoningEffort("{\"reasoning_effort\":\"max\",\"overwrite_mode\":\"override\"}"));

        assertThat(prepared).containsEntry("reasoning_effort", "max");
    }

    /** 删除模式连下游自己带的也一并移除，让上游用它自己的默认。 */
    @Test
    void deleteModeStripsDownstreamReasoningEffort() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reasoning_effort", "low");

        Map<String, Object> prepared = assemble(request, false, "model-a",
                providerWithReasoningEffort("{\"reasoning_effort\":\"max\",\"overwrite_mode\":\"delete\"}"));

        assertThat(prepared).doesNotContainKey("reasoning_effort");
    }

    /** 兜底模式与 V2 之前一致：下游没带才注入配置值。这也是升级后的默认。 */
    @Test
    void fallbackModeInjectsConfiguredEffortOnlyWhenDownstreamOmitted() {
        String config = "{\"reasoning_effort\":\"high\",\"overwrite_mode\":\"fallback\"}";

        Map<String, Object> injected = assemble(
                new LinkedHashMap<>(), false, "model-a", providerWithReasoningEffort(config));
        assertThat(injected).containsEntry("reasoning_effort", "high");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reasoning_effort", "low");
        Map<String, Object> kept = assemble(request, false, "model-a", providerWithReasoningEffort(config));
        assertThat(kept).containsEntry("reasoning_effort", "low");
    }

    /** 透传模式一个字段都不碰：没带不补、带了不改。 */
    @Test
    void passthroughModeLeavesReasoningEffortEntirelyToDownstream() {
        String config = "{\"reasoning_effort\":\"high\",\"overwrite_mode\":\"passthrough\"}";

        Map<String, Object> omitted = assemble(
                new LinkedHashMap<>(), false, "model-a", providerWithReasoningEffort(config));
        assertThat(omitted).doesNotContainKey("reasoning_effort");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reasoning_effort", "low");
        Map<String, Object> kept = assemble(request, false, "model-a", providerWithReasoningEffort(config));
        assertThat(kept).containsEntry("reasoning_effort", "low");
    }

    /** 遗留的 {@code None} 仍表示不发送 —— 升级后行为不变。 */
    @Test
    void legacyNoneStillMeansDoNotSendReasoningEffort() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reasoning_effort", "low");

        Map<String, Object> prepared = assemble(request, false, "model-a", providerWithReasoningEffort("None"));

        assertThat(prepared).doesNotContainKey("reasoning_effort");
    }

    // ==================== 辅助 ====================

    private static ProviderRuntimeConfiguration provider() {
        return new ProviderRuntimeConfiguration("stub", "", "", List.of());
    }

    private static ProviderRuntimeConfiguration providerWithRules(String bodyRulesJson) {
        return new ProviderRuntimeConfiguration("stub", "https://api.example/v1", "key", List.of(),
                "[]", bodyRulesJson);
    }

    private static ProviderRuntimeConfiguration providerWithReasoningEffort(String effort) {
        return new ProviderRuntimeConfiguration("stub", "", "", List.of(
                new ProviderRuntimeModel("model-a", 32768, false, false, effort)));
    }
}
