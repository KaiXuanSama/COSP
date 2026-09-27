package com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody;

import com.kaixuan.copilot_ollama_proxy.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 请求体装配 —— 主干上「发什么」那一段的编排。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：主干 · 位置：{@code pipeline/before/requestbody/}
 * 步骤「请求体装配」—— 主干在调执行器<strong>之前</strong>调它，把 {@code ctx.body()}
 * 从下游/翻译后形态装配成最终发往上游的形态。
 * <p>完整步骤树见 {@code pipeline/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 *
 * <h2>它取代了什么</h2>
 * 三个执行器此前各有一份 {@code prepareRequestBody}（阶段序列逐字同构，只有协议特定的
 * 中间几步不同）。那几份是 send 插槽「吞掉主干后半段」的一部分：
 * 协议<strong>无关</strong>的 copy / resolveModel / writeProtocolFields / bodyRules / removeNull
 * 本应是主干步骤，却在三个执行器里各写一份。本类把公共序列收归主干，协议<strong>相关</strong>的
 * 三步（system 抬升 / max_tokens / thinking）交给 {@link RequestBodyStageRegistry} 的支线
 * （按 {@code ctx.bodyProtocol()} 查表，未命中即跳过）。
 *
 * <h2>阶段序列（顺序有语义）</h2>
 * <pre>
 * 1. 复制             不污染调用方持有的 Map
 * 2. 解析模型名       剥供应商前缀，后续阶段查配置都要用它
 * 3. 写协议字段       主干自己决定的 model 与 stream
 * 4. system 抬升      【支线】按 bodyProtocol 查表，未命中即跳过（Chat/Responses 没有）
 * 5. max_tokens 补齐  【支线】同上（只有 Anthropic 有）
 * 6. 思考注入         【支线】同上（三协议各一实现，出站字段不同）
 * 7. 请求体规则       主干（协议差异在规则数据里，引擎按 bodyProtocol 筛组）
 * 8. 清 null          <strong>必须最后</strong>
 * </pre>
 *
 * <h2>为何 null 清洗必须在规则之后</h2>
 * 两件事都依赖这个顺序（三条线路此前逐字相同的理由，收归至此）：
 * <ol>
 *   <li>规则产生的 {@code null} 不能出站 ——「设置字段值」留空即置 null，
 *       部分上游对多余的 null 字段并不宽容；</li>
 *   <li>规则看到的输入要与编辑器预览逐字节一致 —— 预览不做 null 剥离，
 *       若运行时先清洗，同一条 {@code exists} 条件会「预览命中、线上不命中」。</li>
 * </ol>
 *
 * <h2>查表键为何是 {@code bodyProtocol} 而非 {@code upstreamProtocol}</h2>
 * 三个支线读写的是 body 的字段形态，因此键描述「手里这份 body 长什么样」。直连时
 * {@code bodyProtocol == upstreamProtocol}；C2M 路线下 body 已被翻译成上游形态，
 * {@code bodyProtocol} 也随之变为上游协议 —— 两者仍一致，但语义上问的是「body 的形态」。
 */
@Component
public class RequestBodyAssembler {

    private static final Logger log = LoggerFactory.getLogger(RequestBodyAssembler.class);

    private final RequestBodyStageRegistry stageRegistry;
    private final RequestBodyRuleEngine ruleEngine;

    public RequestBodyAssembler(RequestBodyStageRegistry stageRegistry,
                                RequestBodyRuleEngine ruleEngine) {
        this.stageRegistry = stageRegistry;
        this.ruleEngine = ruleEngine;
    }

    /**
     * 就地把 {@code ctx.body()} 装配成最终发往上游的形态，并写回 ctx。
     *
     * <p>产出的是一份<strong>新 Map</strong>（不污染下游持有的原始请求体），
     * 经 {@link RequestPipelineContext#replaceBody} 写回；{@code bodyProtocol} 不变
     * （装配不改变 body 所属协议，翻译才改 —— 翻译在本步之前已完成）。
     *
     * @param ctx 本次请求的管道上下文（路由与翻译均已就绪）
     */
    public void assemble(RequestPipelineContext ctx) {
        ProviderRuntimeConfiguration provider = ctx.provider();
        WireProtocol bodyProtocol = ctx.bodyProtocol();

        // 1. 复制：主干会逐阶段改写它，不能把调用方持有的 Map 直接改掉。
        Map<String, Object> body = new LinkedHashMap<>(ctx.body());

        // 2. 解析模型名：剥供应商前缀，后续阶段查配置都要用它。
        String resolvedModel = resolveModel(body.get("model"), ctx.model());

        // 3. 写协议字段：主干自己决定的 model 与 stream（对上游的陈述，非下游原话）。
        body.put("model", resolvedModel);
        body.put("stream", ctx.stream());

        // 4-6. 协议特定支线：未命中即跳过（表达「这种协议没有这一步」，是合法结果）。
        //      装配漏了则由 RequestBodyStageSpringWiringTests 的结构断言兜住。
        stageRegistry.findSystemPromptStage(bodyProtocol)
                .ifPresent(stage -> stage.apply(body));
        stageRegistry.findMaxTokensStage(bodyProtocol)
                .ifPresent(stage -> stage.apply(body, resolvedModel, provider));
        stageRegistry.findThinkingStage(bodyProtocol)
                .ifPresent(stage -> stage.apply(body, resolvedModel, provider));

        // 7. 请求体规则：主干（协议差异在规则数据里，引擎按 bodyProtocol 筛组）。
        applyBodyRules(body, provider, bodyProtocol);

        // 8. 清 null：必须最后（理由见类注释）。
        body.values().removeIf(Objects::isNull);

        ctx.replaceBody(body, bodyProtocol);
    }

    /**
     * 解析出真实的上游模型名（剥除供应商前缀）。
     *
     * <p>两个来源不是「回退关系」，是同一个值的两条路：{@code requestModel} 来自请求体、
     * {@code routedModel} 来自路由解析（{@code ctx.model()}），生产路径上二者同值，
     * 测试常只给其中一个。可枚举默认模型名那一层已剔除。
     */
    private static String resolveModel(Object requestModel, String routedModel) {
        String model;
        if (requestModel instanceof String value && !value.isBlank()) {
            model = value;
        } else if (routedModel != null && !routedModel.isBlank()) {
            model = routedModel;
        } else {
            // 不可达：路由层已拦。保留显式抛错而非凭空返回。
            throw new IllegalArgumentException("请求缺少 model：路由层应已拒绝，不应到达此处");
        }
        return ModelNameUtil.parse(model).modelName();
    }

    /**
     * 执行请求体规则组。引擎返回新 Map，这里原地替换内容以保留 {@code body} 引用。
     *
     * <p>协议筛选由引擎完成：只有声明适用 {@code bodyProtocol} 的规则组才执行。
     */
    private void applyBodyRules(Map<String, Object> body, ProviderRuntimeConfiguration provider,
                               WireProtocol bodyProtocol) {
        RequestBodyRuleEngine.TransformResult result = ruleEngine.transform(
                body, provider.bodyRulesJson(), bodyProtocol);
        body.clear();
        body.putAll(result.output());
        for (RequestBodyRuleEngine.TransformWarning warning : result.warnings()) {
            log.warn("[{}] 请求体规则已跳过: ruleId={}, path={}, message={}",
                    bodyProtocol, warning.ruleId(), warning.fieldPath(), warning.message());
        }
    }
}
