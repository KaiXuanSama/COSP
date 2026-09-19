package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Responses 上游服务的请求构造、空响应兜底与重试。
 *
 * <p>分工与 {@code GenericAnthropicChatServiceTests} 相同：请求构造那批走真实
 * {@code HttpServer}（{@code ClientRequest.body()} 是个 {@code BodyInserter}，
 * 读不出已序列化的内容，而请求体形态正是要验的东西），响应形态那批用
 * {@code ExchangeFunction} stub。
 *
 * <h2>这条线路的测试重点与另两侧不同</h2>
 * Anthropic 侧的大头是「形态转换对不对」（system 提取、{@code max_tokens} 必填、
 * 两个思考维度的顺序）。Responses 直连<strong>没有形态转换</strong>，因此重点转向
 * 「有没有多做事」：不该注入的字段确实没注入、不该发的头确实没发。
 * 那类缺陷不会报错，只会让上游收到用户从未配置过的内容。
 */
class GenericResponsesChatServiceTests {

    /**
     * 默认预算下的上游往返总次数。
     *
     * <p>配置项的语义是<strong>重试次数</strong>，不是总次数：首发那一次不算重试，
     * 因此总往返是 {@code 1 + 5 = 6}。写成表达式而非字面量 {@code 6}，
     * 是为了让「改了默认预算」与「重试语义被写反」这两种情况在失败信息里可区分。
     */
    private static final int EXPECTED_ATTEMPTS_ON_DEFAULT_BUDGET =
            1 + RetryPolicyService.DEFAULT_MAX_ATTEMPTS;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer upstream;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name, String.join(", ", values)));
            capturedHeaders.set(headers);
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] response = okBody().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    // ==================== 出站路径 ====================

    /**
     * Base URL 自带的路径被<strong>完整保留</strong>，{@code /responses} 接在其后。
     *
     * <p>已有供应商的 Base URL 通常是 {@code .../v1}，因而实际请求为
     * {@code /v1/responses} —— 与官方端点一致。裁切规则在 Anthropic 侧已用一个真实
     * 缺陷证明过是错的（对某些中转站会打到站点根路径拿 405），此处沿用保留规则。
     */
    @Test
    void baseUrlPathIsPreservedAndResponsesPathIsAppended() {
        realService().exposeResponses(newRequest(), routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(capturedPath.get()).isEqualTo("/v1/responses");
    }

    /** Base URL 本就是站点根路径时，端点落在 {@code /responses}。 */
    @Test
    void baseUrlWithoutVersionPrefixHitsRootResponsesPath() {
        realService().exposeResponses(newRequest(), routeTo(baseUrlRoot())).block(Duration.ofSeconds(10));

        assertThat(capturedPath.get()).isEqualTo("/responses");
    }

    /**
     * 配了独立 Responses 地址时以它为准，{@code base_url} 不参与。
     *
     * <p>这是 V13 那一列存在的意义。与 Anthropic 那列的区别只在<strong>常态</strong>：
     * 多数中转站没有独立的 Responses 端点，留空回退是常见情形而非例外。
     */
    @Test
    void dedicatedResponsesBaseUrlOverridesChatBaseUrl() {
        ResolvedProviderRoute route = new ResolvedProviderRoute(
                new ProviderRuntimeConfiguration("oai", "https://chat.invalid/v1", "test-key",
                        List.of(), "[]", "{\"version\":2,\"groups\":[]}",
                        "[\"CHAT\",\"RESPONSES\"]", "", baseUrlWithV1(), false),
                "gpt-5", "[oai] gpt-5");

        realService().exposeResponses(newRequest(), route).block(Duration.ofSeconds(10));

        assertThat(capturedPath.get()).isEqualTo("/v1/responses");
    }

    /**
     * 留空时回退 {@code base_url} —— V13 之前不存在这条线路，因此这里钉的不是兼容性，
     * 而是「不配也能用」这个常态路径。
     */
    @Test
    void blankResponsesBaseUrlFallsBackToChatBaseUrl() {
        ResolvedProviderRoute route = new ResolvedProviderRoute(
                new ProviderRuntimeConfiguration("oai", baseUrlWithV1(), "test-key",
                        List.of(), "[]", "{\"version\":2,\"groups\":[]}",
                        "[\"CHAT\",\"RESPONSES\"]", "", "", false),
                "gpt-5", "[oai] gpt-5");

        realService().exposeResponses(newRequest(), route).block(Duration.ofSeconds(10));

        assertThat(capturedPath.get()).isEqualTo("/v1/responses");
    }

    // ==================== 请求头 ====================

    /**
     * 下游送来的凭据值绝不出站，无论它落在哪个头上。
     *
     * <h2>这条用例换过一次判据</h2>
     * 它原先叫 {@code onlyBearerAuthenticationHeaderIsSent}，断言「发 Authorization、
     * 不发 x-api-key」——那个判据是<strong>头名</strong>，成立的前提是「出站头名由上游协议
     * 决定，而 Responses 属 OpenAI 系故恒为 Bearer」。该前提已被移除：头名现在由供应商级
     * 配置决定，默认「取下游」，因此下游只发 {@code x-api-key} 时出站也是 {@code x-api-key}。
     *
     * <p>但这条用例真正要守的东西没变，只是需要换成正确的判据 —— <strong>值</strong>：
     * {@code downstream-leak} 不能出现在任何一个出站鉴权头里。头名可以随配置变，
     * 「下游凭据泄露给上游供应商」永远是缺陷。
     *
     * <p>头名与配置的对应关系由 {@code ProviderRequestHeaderServiceTests} 穷举，
     * 这里只验本服务把配置<strong>接上了</strong>（见
     * {@link #authenticationHeaderFollowsProviderConfiguration}）。
     */
    @Test
    void downstreamCredentialNeverReachesUpstream() {
        HttpHeaders downstream = new HttpHeaders();
        downstream.add("x-api-key", "downstream-leak");

        realService().exposeResponses(newRequest(), routeTo(baseUrlWithV1()), downstream)
                .block(Duration.ofSeconds(10));

        // 默认「取下游」：下游只带 x-api-key，于是供应商 key 也走这个头。
        assertThat(capturedHeaders.get()).containsEntry("X-api-key", "test-key");
        assertThat(capturedHeaders.get()).doesNotContainKey("Authorization");
        assertThat(capturedHeaders.get().values()).doesNotContain("downstream-leak");
    }

    /**
     * 出站头名取自供应商配置，与本服务走哪个协议无关。
     *
     * <p>配「取设置 + x-api-key」并<strong>不给下游任何鉴权头</strong>：若本服务漏传配置
     * （{@code applyHeaders} 那个参数给了 {@code defaults()} 而不是 provider 的值），
     * 装配会落到默认的 Authorization，本用例即红。
     *
     * <p>刻意选 {@code x-api-key} 而非 Authorization 作为配置值 —— 后者与默认值相同，
     * 漏接线也照样绿。
     */
    @Test
    void authenticationHeaderFollowsProviderConfiguration() {
        realService().exposeResponses(newRequest(),
                        routeWithAuthHeader(baseUrlWithV1(), "{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}"))
                .block(Duration.ofSeconds(10));

        assertThat(capturedHeaders.get()).containsEntry("X-api-key", "test-key");
        assertThat(capturedHeaders.get()).doesNotContainKey("Authorization");
    }

    /**
     * <strong>不发</strong> {@code anthropic-version}。
     *
     * <p>{@code applyProtocolHeaders} 的判据同样是否定式
     * （{@code if (protocol != MESSAGES) return}），Responses 因此自动跳过。
     * 发了不会立刻报错（多数上游忽略未知头），但它会出现在日志的请求头快照里，
     * 把排查引向错误方向。
     */
    @Test
    void anthropicVersionHeaderIsNotSent() {
        realService().exposeResponses(newRequest(), routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(capturedHeaders.get()).doesNotContainKey("Anthropic-version");
    }

    // ==================== 请求体 ====================

    /** {@code stream} 由本服务决定，覆盖下游传来的值 —— 两个入口各自对应一种取值。 */
    @Test
    void streamFlagIsOverwrittenByTheChosenEntryPoint() {
        Map<String, Object> request = newRequest();
        request.put("stream", true);

        realService().exposeResponses(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(bodyNode().path("stream").asBoolean()).isFalse();
    }

    /** 供应商前缀被剥掉，上游收到真实模型名。 */
    @Test
    void providerPrefixIsStrippedFromModelName() {
        Map<String, Object> request = newRequest();
        request.put("model", "[oai] gpt-5");

        realService().exposeResponses(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(bodyNode().path("model").asText()).isEqualTo("gpt-5");
    }

    /**
     * {@code input} 原样透传，无论是字符串还是数组。
     *
     * <p>这条线路是直连，<strong>没有</strong>形态转换。本服务不认识 {@code input} 的内部结构，
     * 也不应该认识 —— 认识就意味着有一份必须跟着 OpenAI 更新的白名单。
     */
    @Test
    void inputIsPassedThroughVerbatim() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "gpt-5");
        request.put("input", List.of(Map.of("role", "user",
                "content", List.of(Map.of("type", "input_text", "text", "hi")))));

        realService().exposeResponses(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        JsonNode input = bodyNode().path("input");
        assertThat(input.isArray()).isTrue();
        assertThat(input.get(0).path("content").get(0).path("text").asText()).isEqualTo("hi");
    }

    /**
     * 有状态字段原样透传。
     *
     * <p>{@code previous_response_id} / {@code store} / {@code conversation} 是 Responses
     * 独有的服务端状态机入口。直连场景下上游就是真正的会话持有者，本服务不该干预 ——
     * 剥掉它们会让下游客户端的多轮上下文静默丢失（不报错，只是模型突然「失忆」）。
     */
    @Test
    void statefulFieldsArePassedThroughVerbatim() {
        Map<String, Object> request = newRequest();
        request.put("previous_response_id", "resp_prev");
        request.put("store", true);

        realService().exposeResponses(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(bodyNode().path("previous_response_id").asText()).isEqualTo("resp_prev");
        assertThat(bodyNode().path("store").asBoolean()).isTrue();
    }

    /**
     * <strong>不注入 {@code max_output_tokens}。</strong>
     *
     * <p>这是本类最容易失效的一条断言：字段名与 {@code MaxOutputTokensSetting} 看起来
     * 天造地设，接上去毫无阻力。但那个设置只有 Anthropic 线路该消费 —— 那条线路
     * {@code max_tokens} 必填。Responses 侧接上会给所有「下游没带」的调用凭空补一个上限，
     * 而 Copilot 通常就是不带。
     *
     * <p>用例里给模型配了一个明确的最大输出值：若哪天有人接上了，这条会立刻失败。
     */
    @Test
    void maxOutputTokensIsNeverInjected() {
        ResolvedProviderRoute route = routeWithModel(baseUrlWithV1(), new ProviderRuntimeModel(
                "gpt-5", 128000, true, true, null,
                "{\"max_output_tokens\":8000,\"overwrite_mode\":\"override\"}"));

        realService().exposeResponses(newRequest(), route).block(Duration.ofSeconds(10));

        assertThat(bodyNode().has("max_output_tokens")).isFalse();
        assertThat(bodyNode().has("max_tokens")).isFalse();
    }

    /**
     * <strong>不注入 Anthropic 的思考方式。</strong>
     *
     * <p>{@code thinking} 是 Anthropic 与 Chat 的方言，Responses 协议里没有它。
     * 给模型配上思考方式后本条仍应看不到该字段。
     */
    @Test
    void anthropicThinkingModeIsNeverInjected() {
        ResolvedProviderRoute route = routeWithModel(baseUrlWithV1(), new ProviderRuntimeModel(
                "gpt-5", 128000, true, true, null, null,
                "{\"thinking_type\":\"enabled\",\"overwrite_mode\":\"override\"}", 4096));

        realService().exposeResponses(newRequest(), route).block(Duration.ofSeconds(10));

        assertThat(bodyNode().has("thinking")).isFalse();
        assertThat(bodyNode().has("thinking_budget_tokens")).isFalse();
    }

    /**
     * 思考深度写成 Responses 的原生形态。
     *
     * <p>字段映射的细节由 {@code ReasoningEffortSettingTests.Responses注入模式} 覆盖；
     * 这里只钉「这条线路确实接上了那个方法」以及「没有走成另两条线路的字段」。
     */
    @Test
    void reasoningEffortIsWrittenInResponsesShape() {
        ResolvedProviderRoute route = routeWithModel(baseUrlWithV1(), new ProviderRuntimeModel(
                "gpt-5", 128000, true, true,
                "{\"reasoning_effort\":\"high\",\"overwrite_mode\":\"override\"}"));

        realService().exposeResponses(newRequest(), route).block(Duration.ofSeconds(10));

        assertThat(bodyNode().path("reasoning").path("effort").asText()).isEqualTo("high");
        assertThat(bodyNode().has("reasoning_effort")).isFalse();
        assertThat(bodyNode().has("output_config")).isFalse();
    }

    /** 请求体规则按 RESPONSES 协议筛选后执行。 */
    @Test
    void bodyRulesDeclaredForResponsesAreApplied() {
        String rules = """
                {"version":2,"groups":[{"id":"g1","name":"g1","order":0,"enabled":true,
                 "protocols":["RESPONSES"],"templateKeys":["custom"],"previewBody":{},
                 "rules":[{"id":"r1","order":0,"field":"truncation","array":false,
                  "conditional":false,"conditionMode":"all","conditions":[],
                  "operations":[{"type":"set_value","value":"auto"}]}]}]}""";

        realService().exposeResponses(newRequest(), routeWithRules(baseUrlWithV1(), rules))
                .block(Duration.ofSeconds(10));

        assertThat(bodyNode().path("truncation").asText()).isEqualTo("auto");
    }

    /**
     * 只声明给另一条线路的规则组不在此执行。
     *
     * <p>协议筛选写反的症状是「用户为 Chat 写的规则悄悄污染了 Responses 请求」——
     * 不报错，只是上游收到没人配过的字段。
     */
    @Test
    void bodyRulesDeclaredForOtherProtocolsAreSkipped() {
        String rules = """
                {"version":2,"groups":[{"id":"g1","name":"g1","order":0,"enabled":true,
                 "protocols":["CHAT","MESSAGES"],"templateKeys":["custom"],"previewBody":{},
                 "rules":[{"id":"r1","order":0,"field":"truncation","array":false,
                  "conditional":false,"conditionMode":"all","conditions":[],
                  "operations":[{"type":"set_value","value":"auto"}]}]}]}""";

        realService().exposeResponses(newRequest(), routeWithRules(baseUrlWithV1(), rules))
                .block(Duration.ofSeconds(10));

        assertThat(bodyNode().has("truncation")).isFalse();
    }

    // ==================== 空响应兜底（非流式） ====================

    /**
     * 只有 {@code usage} 没有 {@code output} 的响应算空响应，按重试预算重发。
     *
     * <p>{@code usage} 不是判据 —— 一次「什么都没生成但计了费」的调用对下游毫无价值。
     */
    @Test
    void nonStreamEmptyOutputTriggersRetry() {
        AtomicInteger calls = new AtomicInteger();
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return jsonResponse(HttpStatus.OK,
                    "{\"id\":\"resp_1\",\"output\":[],\"usage\":{\"input_tokens\":9,\"output_tokens\":0}}");
        });

        service.exposeResponses(newRequest(), routeTo("http://upstream.invalid"))
                .block(Duration.ofSeconds(10));

        assertThat(calls.get()).isEqualTo(EXPECTED_ATTEMPTS_ON_DEFAULT_BUDGET);
    }

    /**
     * 空响应重试耗尽后<strong>放行最后一轮的原始 body</strong>，不抛错。
     *
     * <p>「透传上游真实返回」优先于「报一个我们自己造的错」—— 后者会让用户以为
     * 是代理坏了，而事实是上游确实什么都没说。
     */
    @Test
    void nonStreamEmptyResponseIsPassedThroughAfterBudgetExhausted() {
        String upstreamBody = "{\"id\":\"resp_1\",\"output\":[]}";
        TestService service = stubService(request -> jsonResponse(HttpStatus.OK, upstreamBody));

        String result = service.exposeResponses(newRequest(), routeTo("http://upstream.invalid"))
                .block(Duration.ofSeconds(10));

        assertThat(result).isEqualTo(upstreamBody);
    }

    /** 有实质正文时不重试。 */
    @Test
    void nonStreamMeaningfulResponseIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return jsonResponse(HttpStatus.OK, okBody());
        });

        service.exposeResponses(newRequest(), routeTo("http://upstream.invalid"))
                .block(Duration.ofSeconds(10));

        assertThat(calls.get()).isEqualTo(1);
    }

    /**
     * 只有拒绝理由的响应不该重发 —— <strong>缺口 B 的端到端后果</strong>。
     *
     * <p>形态照官方 {@code ResponseOutputRefusal object { refusal, type }} 写。
     * 模型拒绝回答是正常行为而非上游故障，一次往返就该结束。
     */
    @Test
    void nonStreamRefusalOnlyResponseIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return jsonResponse(HttpStatus.OK, refusalOnlyBody());
        });

        service.exposeResponses(newRequest(), routeTo("http://upstream.invalid"))
                .block(Duration.ofSeconds(10));

        assertThat(calls.get()).isEqualTo(1);
    }

    /**
     * 思考链是唯一内容时不该重发 —— <strong>缺口 A 的端到端后果</strong>。
     *
     * <p>形态取自 2026-09-12 对 MiMo（{@code mimo-v2.5}）真实调用的响应：
     * {@code summary} 为空、思考正文在 {@code content[].reasoning_text} 里。
     * 这是「思考吃完全部预算、正文没来得及生成」的真实形态（上游会把
     * {@code status} 标成 {@code incomplete}）。
     */
    @Test
    void nonStreamReasoningOnlyResponseIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return jsonResponse(HttpStatus.OK, reasoningOnlyBody());
        });

        service.exposeResponses(newRequest(), routeTo("http://upstream.invalid"))
                .block(Duration.ofSeconds(10));

        assertThat(calls.get()).isEqualTo(1);
    }

    /** 401 不可重试，立即失败。 */
    @Test
    void unauthorizedIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return jsonResponse(HttpStatus.UNAUTHORIZED, "{\"error\":{\"message\":\"bad key\"}}");
        });

        Throwable error = org.assertj.core.api.Assertions.catchThrowable(() ->
                service.exposeResponses(newRequest(), routeTo("http://upstream.invalid"))
                        .block(Duration.ofSeconds(10)));

        assertThat(error).isNotNull();
        assertThat(calls.get()).isEqualTo(1);
    }

    // ==================== 空响应兜底（流式） ====================

    /**
     * 一轮事件里从未出现实质载荷即判空，按预算重发。
     *
     * <p>这里用的是「只有生命周期事件」的序列：上游把状态机跑完了，
     * 但一个字都没生成。
     */
    @Test
    void streamWithoutPayloadTriggersRetry() {
        AtomicInteger calls = new AtomicInteger();
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return sseResponse(Flux.just(
                    sse(factory, "{\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}"),
                    sse(factory, "{\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"output\":[]}}")));
        });

        service.exposeResponsesStream(newRequest(), routeTo("http://upstream.invalid"))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(calls.get()).isEqualTo(EXPECTED_ATTEMPTS_ON_DEFAULT_BUDGET);
    }

    /**
     * 有一个文本增量事件就不判空。
     *
     * <p>判据是「一轮内出现过一次」而非「每个事件都有」—— 生命周期事件本来就不带内容。
     */
    @Test
    void streamWithOneTextDeltaIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return sseResponse(Flux.just(
                    sse(factory, "{\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}"),
                    sse(factory, "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}"),
                    sse(factory, "{\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}")));
        });

        List<String> events = service.exposeResponsesStream(newRequest(), routeTo("http://upstream.invalid"))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(events).hasSize(3);
    }

    /**
     * 事件<strong>按序完整下发</strong>，不扣帧。
     *
     * <p>这条线路刻意不设 Chat 侧那种 gate：下游客户端是状态机，扣住
     * {@code response.created} 会让它无法初始化。代价是空响应那一轮的事件已经流走了，
     * 但那正是耗尽后本来也要做的事。
     */
    @Test
    void streamEventsAreForwardedInOrderWithoutGating() {
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        TestService service = stubService(request -> sseResponse(Flux.just(
                sse(factory, "{\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}"),
                sse(factory, "{\"type\":\"response.output_text.delta\",\"delta\":\"a\"}"),
                sse(factory, "{\"type\":\"response.output_text.delta\",\"delta\":\"b\"}"),
                sse(factory, "{\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}"))));

        List<String> events = service.exposeResponsesStream(newRequest(), routeTo("http://upstream.invalid"))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(events).hasSize(4);
        assertThat(events.get(0)).contains("response.created");
        assertThat(events.get(3)).contains("response.completed");
    }

    // ==================== usage 落库 ====================

    /**
     * 非流式：{@code prompt_tokens} 直接取 {@code input_tokens}，不加缓存命中数。
     *
     * <p>换算细节由 {@code ResponsesUsageParserTests} 覆盖，这里钉的是「落库链确实
     * 接的是那个解析器」—— 若有人误接了 {@code AnthropicUsageParser}，这条会失败。
     */
    @Test
    void nonStreamUsageIsSavedWithoutAddingCachedTokens() {
        RecordingUsageService usageService = new RecordingUsageService();
        TestService service = stubService(request -> jsonResponse(HttpStatus.OK, """
                {"id":"resp_1","output":[{"type":"message","content":[{"type":"output_text","text":"hi"}]}],
                 "usage":{"input_tokens":100,"output_tokens":7,
                          "input_tokens_details":{"cached_tokens":80}}}"""));
        service.setApiCallUsage(usageService);

        service.exposeResponses(newRequest(), routeTo("http://upstream.invalid"))
                .block(Duration.ofSeconds(10));

        assertThat(usageService.tokens.promptTokens()).isEqualTo(100);
        assertThat(usageService.tokens.completionTokens()).isEqualTo(7);
        assertThat(usageService.tokens.cachedTokens()).isEqualTo(80);
    }

    /**
     * 流式：终态事件里的嵌套 usage 被取到，且 {@code usage_raw} 存的是<strong>一份</strong>上游原文。
     *
     * <p>Responses 的 usage 一次给全，因此不需要 Anthropic 那套跨事件合并 ——
     * 那套机制存在的前提是「输入与输出分别在两个事件里给」，这里不成立。
     */
    @Test
    void streamUsageIsTakenFromTerminalEvent() {
        RecordingUsageService usageService = new RecordingUsageService();
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        TestService service = stubService(request -> sseResponse(Flux.just(
                sse(factory, "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}"),
                sse(factory, """
                        {"type":"response.completed","response":{"id":"r",\
                        "usage":{"input_tokens":42,"output_tokens":7,\
                        "input_tokens_details":{"cached_tokens":12}}}}"""))));
        service.setApiCallUsage(usageService);

        service.exposeResponsesStream(newRequest(), routeTo("http://upstream.invalid"))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(usageService.tokens.promptTokens()).isEqualTo(42);
        assertThat(usageService.tokens.completionTokens()).isEqualTo(7);
        assertThat(usageService.tokens.cachedTokens()).isEqualTo(12);
        assertThat(usageService.usageRaw).contains("\"input_tokens\":42");
    }

    /** 上游不给 usage 时不落 usage 行 —— {@code null} 不能折成 0。 */
    @Test
    void missingUsageIsNotSaved() {
        RecordingUsageService usageService = new RecordingUsageService();
        TestService service = stubService(request -> jsonResponse(HttpStatus.OK, okBody()
                .replace(",\"usage\":{\"input_tokens\":10,\"output_tokens\":2}", "")));
        service.setApiCallUsage(usageService);

        service.exposeResponses(newRequest(), routeTo("http://upstream.invalid"))
                .block(Duration.ofSeconds(10));

        assertThat(usageService.tokens).isNull();
    }

    // ==================== 透传保真度 ====================

    /**
     * JSON 载荷<strong>逐字节</strong>透传，不重新序列化。
     *
     * <p>载荷刻意做成「不像 Jackson 默认输出」的样子：转义的中文、键顺序刻意打乱、
     * 逗号后有空格、`z_` 开头的键排在最前。任何一处「解析后重新序列化」都会让
     * 这条断言失败 —— 那正是本组用例要防的。
     *
     * <p>对照物是 Chat 侧：{@code AbstractUpstreamChatService.normalizeUpstreamChunk}
     * 会把 chunk 读成 Map、改字段名、剪枝，再 {@code writeValueAsString} 写回。
     * Responses 侧<strong>没有</strong>这一步，因此上游异常的键顺序不会被"修正"。
     */
    @Test
    void 上游JSON载荷逐字节透传() {
        String payload = "{\"type\":\"response.output_text.delta\",\"z_first\":1,"
                + "\"delta\":\"\\u4f60\\u597d\",\"odd\":\"a  b\"}";
        TestService service = stubService(request -> sseResponse(Flux.just(
                raw(factory(), "data: " + payload + "\n\n"))));

        List<String> events = service.exposeResponsesStream(newRequest(), routeTo("http://upstream.invalid"))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(events).containsExactly(payload);
    }

    /**
     * 上游的 SSE {@code event:} 名在 provider 层就被剥掉，不进入数据流。
     *
     * <p>下行链路只有 {@code data} 字符串，{@code event:} 名由控制器从 JSON 的
     * {@code type} 字段<strong>重新推导</strong>。因此若某上游的 {@code event:} 名与
     * 自己的 {@code type} 不一致，下游收到的会是 {@code type} 那个值。
     *
     * <p>实测两家上游 363 个事件全部一致（见 {@code ResponsesControllerTests}），
     * 所以这不是当前的问题；写下来是为了让「事件名有唯一真源」这件事显式化 ——
     * 它是设计选择，不是巧合。
     */
    @Test
    void 上游事件名不进入数据流() {
        TestService service = stubService(request -> sseResponse(Flux.just(
                raw(factory(), "event: upstream.custom.name\ndata: {\"type\":\"response.created\"}\n\n"),
                raw(factory(), "event: another.name\n"
                        + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n"))));

        List<String> events = service.exposeResponsesStream(newRequest(), routeTo("http://upstream.invalid"))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(events).containsExactly(
                "{\"type\":\"response.created\"}",
                "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}");
        assertThat(String.join("", events)).doesNotContain("upstream.custom.name", "another.name");
    }

    /**
     * 空 {@code data}、字面量 {@code null}、SSE 注释与只有 {@code id} 的事件全部丢弃。
     *
     * <p>前两类是「中转站预热/保活」的常见形态，第三类是标准心跳写法。丢弃它们是
     * <strong>有意的</strong>而非疏漏：一个 {@code data: null} 直接下发会让下游
     * JSON 解析失败（那是非法 JSON），而注释与 id 在 Responses 协议里没有语义。
     *
     * <p>代价要说清楚：这不是「字节级无损」，而是「语义级无损」——
     * 被丢弃的四类事件都不承载协议内容。
     */
    @Test
    void 空data与字面量null与注释与纯id事件都被丢弃() {
        TestService service = stubService(request -> sseResponse(Flux.just(
                raw(factory(), ": keep-alive\n\n"),
                raw(factory(), "data:\n\n"),
                raw(factory(), "data: null\n\n"),
                raw(factory(), "id: 42\n\n"),
                raw(factory(), "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n"))));

        List<String> events = service.exposeResponsesStream(newRequest(), routeTo("http://upstream.invalid"))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(events).containsExactly("{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}");
    }

    /**
     * 被丢弃的事件不参与空响应计数 —— 只有它们时仍判空并重试。
     *
     * <p>否则一个「只发心跳」的上游会被判成正常响应，下游拿到一个 empty 流却没有报错。
     */
    @Test
    void 只发保活事件时仍判空响应() {
        AtomicInteger calls = new AtomicInteger();
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return sseResponse(Flux.just(
                    raw(factory(), ": keep-alive\n\n"),
                    raw(factory(), "data: null\n\n")));
        });

        service.exposeResponsesStream(newRequest(), routeTo("http://upstream.invalid"))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(calls.get()).isEqualTo(EXPECTED_ATTEMPTS_ON_DEFAULT_BUDGET);
    }

    // ==================== 辅助 ====================

    private TestService realService() {
        return new TestService();
    }

    private TestService stubService(Function<ClientRequest, Mono<ClientResponse>> handler) {
        TestService service = new TestService();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(handler::apply));
        return service;
    }

    private static Mono<ClientResponse> jsonResponse(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
    }

    private static Mono<ClientResponse> sseResponse(Flux<DataBuffer> body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .body(body).build());
    }

    private static DataBuffer sse(DefaultDataBufferFactory factory, String json) {
        return factory.wrap(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    /** 逐字节写入一段原始 SSE 文本，用于构造注释、{@code id} 等非标准事件。 */
    private static DataBuffer raw(DefaultDataBufferFactory factory, String text) {
        return factory.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private static DefaultDataBufferFactory factory() {
        return new DefaultDataBufferFactory();
    }

    /** 带实质载荷的非流式响应 —— 占位响应会被判空并卷入重试循环。 */
    private static String okBody() {
        return """
                {"id":"resp_1","object":"response","status":"completed",\
                "output":[{"type":"message","role":"assistant",\
                "content":[{"type":"output_text","text":"hello"}]}],\
                "usage":{"input_tokens":10,"output_tokens":2}}""";
    }

    /** 只有拒绝理由的响应。字段名照官方 {@code ResponseOutputRefusal}。 */
    private static String refusalOnlyBody() {
        return """
                {"id":"resp_1","object":"response","status":"completed",\
                "output":[{"type":"message","role":"assistant","status":"completed",\
                "content":[{"type":"refusal","refusal":"I cannot help with that."}]}],\
                "usage":{"input_tokens":10,"output_tokens":2}}""";
    }

    /**
     * 只有思考链的响应，形态取自 MiMo 真实调用。
     *
     * <p>{@code summary} 为空数组，思考正文在 {@code content[].reasoning_text}。
     * {@code status} 用 {@code incomplete} 表示正文未能生成。
     */
    private static String reasoningOnlyBody() {
        return """
                {"id":"resp_1","object":"response","status":"incomplete",\
                "incomplete_details":{"reason":"max_output_tokens"},\
                "output":[{"type":"reasoning","summary":[],\
                "content":[{"type":"reasoning_text","text":"Okay, the user asked"}]}],\
                "usage":{"input_tokens":10,"output_tokens":2}}""";
    }

    private static Map<String, Object> newRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "gpt-5");
        request.put("input", "hi");
        return request;
    }

    private JsonNode bodyNode() {
        try {
            return objectMapper.readTree(capturedBody.get());
        } catch (Exception e) {
            throw new AssertionError("上游收到的请求体不是合法 JSON: " + capturedBody.get(), e);
        }
    }

    private String baseUrlWithV1() {
        return "http://localhost:" + upstream.getAddress().getPort() + "/v1";
    }

    private String baseUrlRoot() {
        return "http://localhost:" + upstream.getAddress().getPort();
    }

    private static ResolvedProviderRoute routeTo(String baseUrl) {
        return new ResolvedProviderRoute(
                new ProviderRuntimeConfiguration("oai", baseUrl, "test-key", List.of()),
                "gpt-5", "[oai] gpt-5");
    }

    private static ResolvedProviderRoute routeWithRules(String baseUrl, String bodyRulesJson) {
        return new ResolvedProviderRoute(
                new ProviderRuntimeConfiguration("oai", baseUrl, "test-key", List.of(),
                        "[]", bodyRulesJson),
                "gpt-5", "[oai] gpt-5");
    }

    /**
     * 带出站鉴权头装配方式的路由。
     *
     * <p>该维度是供应商级的（不是模型级），所以要写满参构造器的第 11 个参数 ——
     * 前面那些都取与 {@link #routeTo} 一致的默认值。
     *
     * @param authHeaderJson 持久化原文，形如
     *                       {@code {"mode":"CONFIGURED","header":"X_API_KEY"}}
     */
    private static ResolvedProviderRoute routeWithAuthHeader(String baseUrl, String authHeaderJson) {
        return new ResolvedProviderRoute(
                new ProviderRuntimeConfiguration("oai", baseUrl, "test-key", List.of(),
                        "[]", "{\"version\":2,\"groups\":[]}",
                        ProviderRuntimeConfiguration.DEFAULT_SUPPORTED_PROTOCOLS_JSON,
                        "", "", false, authHeaderJson),
                "gpt-5", "[oai] gpt-5");
    }

    /**
     * 带模型级配置的路由。
     *
     * <p>模型名必须是 {@code gpt-5} —— 那是 {@link #newRequest()} 与路由解析出的上游模型名，
     * 而查找按名字精确匹配。名字不对就会落到「模型未配置」分支，
     * 用例看起来通过了却什么都没验到。
     */
    private static ResolvedProviderRoute routeWithModel(String baseUrl, ProviderRuntimeModel model) {
        return new ResolvedProviderRoute(
                new ProviderRuntimeConfiguration("oai", baseUrl, "test-key", List.of(model)),
                "gpt-5", "[oai] gpt-5");
    }

    /** 捕获落库参数的 usage 服务替身。只需最后一次调用的值。 */
    private static final class RecordingUsageService implements ApiCallUsageService {

        private UsageTokens tokens;
        private String usageRaw;

        @Override
        public void save(Long logId, String providerKey, String modelName, boolean stream,
                         String usageRaw, UsageTokens tokens, Integer ttfbMs) {
            this.usageRaw = usageRaw;
            this.tokens = tokens;
        }
    }

    /**
     * 测试子类：暴露受保护入口并把退避压到毫秒级。
     *
     * <p>不压缩退避的话，「重试到耗尽」的用例要真实等待 62 秒 —— 而它验证的是
     * 重试次数与判定条件，退避时长本身不是被测行为。
     */
    private static final class TestService extends GenericResponsesChatService {

        private TestService() {
            super(new ObjectMapper(), new ProviderRequestHeaderService(new ObjectMapper()),
                    new RequestBodyRuleEngine(new ObjectMapper()));
        }

        private Mono<String> exposeResponses(Map<String, Object> request, ResolvedProviderRoute route) {
            return exposeResponses(request, route, HttpHeaders.EMPTY);
        }

        private Mono<String> exposeResponses(Map<String, Object> request, ResolvedProviderRoute route,
                                             HttpHeaders downstreamHeaders) {
            return responses(request, route, downstreamHeaders, "req-test");
        }

        private Flux<String> exposeResponsesStream(Map<String, Object> request, ResolvedProviderRoute route) {
            return responsesStream(request, route, HttpHeaders.EMPTY, "req-test");
        }

        @Override
        protected Duration retryFirstBackoff() {
            return Duration.ofMillis(5);
        }

        @Override
        protected Duration retryMaxBackoff() {
            return Duration.ofMillis(20);
        }
    }
}
