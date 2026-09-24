package com.kaixuan.copilot_ollama_proxy.upstream.outbound;

import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AuthHeaderSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 出站请求装配 —— 主干上「发往哪、带什么头」那一段的编排（阶段 4 刀 3 B）。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：主干 · 位置：{@code upstream/outbound/}
 * 步骤「出站请求装配」—— 发送前块在<strong>请求体装配之后</strong>调它，把出站请求头与
 * 基础地址算好写进 {@code ctx.outboundHeaders/outboundBaseUrl}，供发送后块的 {@code buildWebClient} 直接铺用。
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 *
 * <h2>它取代了什么</h2>
 * 三个执行器此前各在 {@code buildWebClient} 里做同一件事：解析地址（各读各的列）+ 调
 * {@code applyHeaders} 装三层头 + 补协议必需头（只有 Anthropic 的 {@code anthropic-version}）。
 * 那是 send 插槽「吞掉主干后半段」的最后一块：协议<strong>无关</strong>的三层头装配本应是主干步骤，
 * 却在三个执行器里各写一遍。本类把它收归发送前块，协议<strong>相关</strong>的两件事
 * （读哪一列地址、补哪个协议头）交给 {@link OutboundRequestStage} 的支线（按 {@code ctx.upstreamProtocol()} 查表）。
 *
 * <p>搬迁后发送后块的 {@code buildWebClient} 只剩「读 ctx 的头和地址 + capturingHttpClient + filter + 发送」——
 * 那些是<strong>真正碰上游</strong>时才有意义的传输层动作（抓传输层头快照要等 Reactor Netty 发出请求那一刻），
 * 故留块 2。
 *
 * <h2>装配顺序（有语义，不可乱）</h2>
 * <pre>
 * 1. 解析地址   支线 resolveBaseUrl（读协议特定列）→ normalizeBaseUrl（协议无关的尾斜杠归一）
 * 2. 三层头     ProviderRequestHeaderService.applyHeaders：下游头透传 → 鉴权头再分配 → 请求头规则
 * 3. 协议头     支线 applyProtocolHeaders（set-if-absent）—— <strong>必须在第 2 步之后</strong>
 * </pre>
 *
 * <h2>为何协议头（第 3 步）必须在请求头规则（第 2 步末）之后</h2>
 * 这是从旧 {@code buildWebClient} 逐字保留的顺序：{@code anthropic-version} 用 set-if-absent
 * 补写，放在 {@code applyHeaders} 之后，<strong>使供应商请求头规则仍可覆盖它</strong>
 * （某些中转站要求特定版本）。若反过来先补协议头再跑规则，规则要删/改这个头就得写额外分支 ——
 * 而现在的顺序让「规则拥有最终决定权」这条全局约束自然成立。
 *
 * <h2>它持有的是无状态协作者</h2>
 * {@link ProviderRequestHeaderService}（三层头装配）、{@link OutboundRequestStageRegistry}（查表）、
 * {@link ObjectMapper}（解析 {@code auth_header} JSON）都是单例、无请求态，故本类做成单例 Bean 安全。
 * 请求数据（headers / baseUrl）写进 per-request 的 ctx。
 */
@Component
public class OutboundRequestAssembler {

    private final ProviderRequestHeaderService headerService;
    private final OutboundRequestStageRegistry stageRegistry;
    private final ObjectMapper objectMapper;

    public OutboundRequestAssembler(ProviderRequestHeaderService headerService,
                                    OutboundRequestStageRegistry stageRegistry,
                                    ObjectMapper objectMapper) {
        this.headerService = headerService;
        this.stageRegistry = stageRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 装配出站请求头与地址，写回 ctx。
     *
     * <p>产出一份<strong>新</strong> {@link HttpHeaders}（不与下游头共享引用），经
     * {@link RequestPipelineContext#applyOutbound} 写回。发送后块的 {@code buildWebClient}
     * 随后 {@code addAll} 进 WebClient 的 defaultHeaders。
     *
     * @param ctx 本次请求的管道上下文（路由已回填、请求体已装配）
     */
    public void assemble(RequestPipelineContext ctx) {
        ProviderRuntimeConfiguration provider = ctx.provider();
        OutboundRequestStage stage = stageRegistry.require(ctx.upstreamProtocol());

        // 1. 解析地址：支线读协议特定列，assembler 做协议无关的尾斜杠归一化。
        String baseUrl = headerService.normalizeBaseUrl(stage.resolveBaseUrl(provider));

        // 2. 三层头：下游头透传 → 鉴权头再分配 → 请求头规则（协议不参与，故不在支线里）。
        HttpHeaders headers = new HttpHeaders();
        headerService.applyHeaders(headers, ctx.downstreamHeaders(), provider.apiKey(),
                provider.headerRulesJson(), ctx.stream(),
                AuthHeaderSetting.parse(provider.authHeaderJson(), objectMapper));

        // 3. 协议头：set-if-absent 补协议必需头（如 anthropic-version）——
        //    必须在第 2 步之后，规则才能覆盖它（见类注释）。
        stage.applyProtocolHeaders(headers);

        ctx.applyOutbound(headers, baseUrl);
    }
}
