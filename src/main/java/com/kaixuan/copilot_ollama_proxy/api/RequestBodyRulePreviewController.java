package com.kaixuan.copilot_ollama_proxy.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台请求体规则预览 API。
 *
 * <h2>为何预览要走后端</h2>
 * 预览的全部价值在于「所见即将发生」。前端曾有一份独立的 TS 引擎实现，它与后端引擎
 * 靠注释和纪律保持一致，而实践证明这防不住漂移（{@code set_value} 缺 value 时
 * 一侧落 null、另一侧落 undefined，同一条规则在预览里表现为删除字段、在生产中表现为置 null）。
 * 会说谎的预览比没有预览更糟 —— 用户会信它。改由生产引擎直接计算后，
 * 「预览与实际不一致」这一整类问题在结构上不再可能。
 *
 * <h2>为何不复用保存端点</h2>
 * 保存路径会校验、落库、影响线上请求；预览只是一次纯函数求值，不该要求规则先合法到能保存。
 * 用户在编辑中途（字段还没选、操作还没定）同样需要看到预览，此时规则集必然过不了保存校验。
 */
@RestController
public class RequestBodyRulePreviewController {

    private final RequestBodyRuleEngine requestBodyRuleEngine;

    public RequestBodyRulePreviewController(RequestBodyRuleEngine requestBodyRuleEngine) {
        this.requestBodyRuleEngine = requestBodyRuleEngine;
    }

    /**
     * 用生产引擎计算一组规则作用于给定请求体的结果。
     *
     * <p>请求体形如 <code>{"previewBody": {...}, "rules": [...]}</code>，
     * 响应形如 <code>{"output": {...}, "warnings": [{ruleId, fieldPath, message}]}</code>。
     *
     * <p>不做协议筛选：编辑器里「适用协议」是规则组自己的属性，预览的语义是
     * 「这一组规则作用在这一组样本上」，替用户筛掉他正在编辑的组只会让预览变空白。
     *
     * <p>无论输入多离谱都返回 200：引擎对未知操作类型、缺字段、路径不匹配一律
     * 产出结构化警告而非抛错，那些警告正是编辑器要展示给用户的东西。
     * 只有请求体本身不是 JSON 对象才算调用方错误。
     */
    @PostMapping("/config/api/request-body-rules/preview")
    public Mono<Map<String, Object>> preview(@RequestBody PreviewRequest request) {
        return Mono.fromCallable(() -> {
            RequestBodyRuleEngine.TransformResult result = requestBodyRuleEngine.transformWithRules(
                    request.previewBody() == null ? Map.of() : request.previewBody(), request.rules());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("output", result.output());
            response.put("warnings", result.warnings().stream().map(RequestBodyRulePreviewController::toMap).toList());
            return response;
        });
    }

    private static Map<String, Object> toMap(RequestBodyRuleEngine.TransformWarning warning) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ruleId", warning.ruleId());
        map.put("fieldPath", warning.fieldPath());
        map.put("message", warning.message());
        return map;
    }

    /**
     * 预览请求。
     *
     * <p>{@code rules} 保持为 {@link JsonNode} 而不反序列化成规则 record：
     * 它来自正在编辑中的表单，可能缺字段或带未知操作类型；强类型绑定会让整个请求
     * 在用户打字的中途 400，而引擎本就把这些情况处理成警告。
     *
     * @param previewBody 该规则组的调试样本请求体
     * @param rules 规则数组
     */
    public record PreviewRequest(Map<String, Object> previewBody, JsonNode rules) {
    }
}
