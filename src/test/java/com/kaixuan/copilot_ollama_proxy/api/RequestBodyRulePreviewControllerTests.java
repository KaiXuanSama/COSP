package com.kaixuan.copilot_ollama_proxy.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 规则预览端点。
 *
 * <p>用 {@code bindToController} 而非整个 {@code @SpringBootTest}：本端点只依赖引擎一个 Bean，
 * 拉起完整上下文对验证「请求体进、转换结果出」没有额外价值，却要付启动代价。
 */
class RequestBodyRulePreviewControllerTests {

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        client = WebTestClient.bindToController(
                        new RequestBodyRulePreviewController(new RequestBodyRuleEngine(objectMapper)))
                .build();
    }

    /** 规则生效，且输出结构与转换结果一致。 */
    @Test
    void appliesRulesAndReturnsTransformedBody() {
        client.post().uri("/config/api/request-body-rules/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"previewBody":{"temperature":0.1,"keep":"me"},
                         "rules":[{"id":"r","order":0,"field":"temperature","array":false,
                          "conditional":false,"conditionMode":"all","conditions":[],
                          "operations":[{"type":"set_value","value":0.9}]}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.output.temperature").isEqualTo(0.9)
                .jsonPath("$.output.keep").isEqualTo("me")
                .jsonPath("$.warnings").isEmpty();
    }

    /**
     * 预览<strong>不做协议筛选</strong>。
     *
     * <p>「适用协议」是规则组自己的属性，预览的语义是「这一组规则作用在这一组样本上」；
     * 若替用户筛掉他正在编辑的组，预览会莫名变成空白。这里传的规则若被当成
     * 仅 OPENAI 适用而跳过，断言就会失败。
     */
    @Test
    void doesNotFilterByProtocol() {
        client.post().uri("/config/api/request-body-rules/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"previewBody":{"system":"原始"},
                         "rules":[{"id":"r","order":0,"field":"system","array":false,
                          "conditional":false,"conditionMode":"all","conditions":[],
                          "operations":[{"type":"set_value","value":"改写"}]}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.output.system").isEqualTo("改写");
    }

    /**
     * 编辑中途的半成品规则返回 200 + 警告，而不是 4xx。
     *
     * <p>用户刚点「添加规则」时字段还没选，此时预览必须仍能渲染 ——
     * 那条警告正是要显示给他的提示。
     */
    @Test
    void incompleteRuleYieldsWarningInsteadOfError() {
        client.post().uri("/config/api/request-body-rules/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"previewBody":{"model":"m"},
                         "rules":[{"id":"blank","order":0,"field":"","array":false,
                          "conditional":false,"conditionMode":"all","conditions":[],"operations":[]}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.output.model").isEqualTo("m")
                .jsonPath("$.warnings[0].message").isEqualTo("规则未选择目标字段，已跳过");
    }

    /** 未知操作类型同样只产警告。 */
    @Test
    void unknownOperationTypeYieldsWarning() {
        client.post().uri("/config/api/request-body-rules/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"previewBody":{"model":"m"},
                         "rules":[{"id":"r","order":0,"field":"model","array":false,
                          "conditional":false,"conditionMode":"all","conditions":[],
                          "operations":[{"type":"replace"}]}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.warnings[0].message").isEqualTo("未知操作类型: replace");
    }

    /** 空规则数组原样返回样本。 */
    @Test
    void emptyRulesReturnBodyUnchanged() {
        client.post().uri("/config/api/request-body-rules/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"previewBody\":{\"a\":1},\"rules\":[]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.output.a").isEqualTo(1)
                .jsonPath("$.warnings").isEmpty();
    }

    /** 缺字段的请求不应 500：两个字段都按空处理。 */
    @Test
    void missingFieldsAreTreatedAsEmpty() {
        client.post().uri("/config/api/request-body-rules/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.warnings").isEmpty();
    }

    /**
     * {@code set_value} 留空即置 null —— 这正是前端旧实现搞错的那处语义。
     *
     * <p>前端曾写入 {@code undefined}，序列化后整个键消失，于是同一条规则在预览里
     * 表现为「删除字段」、在生产中表现为「置 null」。接口化后两者由同一份代码产出，
     * 该分歧在结构上不再可能；这条用例把语义本身钉住。
     */
    @Test
    void setValueWithoutValueYieldsExplicitNull() {
        client.post().uri("/config/api/request-body-rules/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"previewBody":{"temperature":0.5},
                         "rules":[{"id":"r","order":0,"field":"temperature","array":false,
                          "conditional":false,"conditionMode":"all","conditions":[],
                          "operations":[{"type":"set_value"}]}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.output.temperature").isEqualTo(null);
    }
}
