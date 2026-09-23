package com.kaixuan.copilot_ollama_proxy.upstream;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 上游失败的<strong>可重试判定</strong> —— 三类上游执行器共用的唯一口径。
 *
 * <h2>它回答的问题</h2>
 * 「这次上游失败值得重发吗」。答案只依赖<strong>异常类型与 HTTP 状态码</strong>，
 * 与上下游协议、与请求体形态都无关 —— 这正是它能跨三条线路共用的原因。
 *
 * <h2>四类可恢复失败</h2>
 * <ol>
 *   <li><strong>连接建立失败</strong>（{@link WebClientRequestException}）——
 *       上游不可达或未响应，多半是瞬时网络问题；</li>
 *   <li><strong>TLS 握手失败</strong>（异常链含 {@code SSLException}）——
 *       与上同理，属瞬时环境问题。它既不是 {@code WebClientRequestException}
 *       也不是 {@code WebClientResponseException}（常被 Netty 的 {@code DecoderException}
 *       包着），必须单独判；</li>
 *   <li><strong>HTTP 错误响应</strong>（{@link WebClientResponseException}）——
 *       状态码本身可重试（429 / 5xx / 400），<em>或</em>异常链含 {@code IOException}
 *       （HTTP 200 之后 SSE 流中途断开，实为网络层失败）；</li>
 *   <li><strong>空响应</strong>（{@link EmptyUpstreamResponseException}）——
 *       HTTP 层通常成功，但一整轮下来无正文、无思考链、无工具调用。
 *       做成异常正是为了复用这份预算，避免出现第二套独立的重试次数配置。</li>
 * </ol>
 * 其余错误（401/403 等确定性 4xx、报文结构错误）一律不重试：
 * 请求内容未变，重试结果必然相同，只是白等一轮退避。
 *
 * <h2>为何 400 也在可重试之列</h2>
 * 看起来与「确定性 4xx 不重试」矛盾，但它是有意为之的历史取舍：
 * 部分中转站在后端抖动时会短暂地回 400，重发一次就恢复。
 * 把它划出可重试集会让这类上游的偶发失败变成硬失败。
 *
 * <h2>为何从三份副本收归一处</h2>
 * 这三个方法此前在 {@code AbstractUpstreamChatService}、
 * {@code GenericAnthropicChatService}、{@code GenericResponsesChatService}
 * 各有一份（其中 {@link #isRetryableFailure} 的写法略有差异但语义等价）。
 * 后两份的 Javadoc 当时写着「刻意不抽公共工具」，理由是
 * 「抽取要改动已验证的线路，而三份相同的代价只是重复」，
 * 并把「三侧出现口径差异」定为抽取的触发信号。
 *
 * <p><strong>那个触发信号至今未出现，本类是按另一条理由落地的</strong>：
 * 主干化重构（见 {@code PLAN.md} / {@code 请求处理链路重构方向.md}）要把三条线路
 * 收敛成「主干 + 支线」，此后它们不再各有各的执行器、而是共用同一条主干。
 * 那种形态下「三份副本」这个前提本身消失了 —— 判定必须有一个单一来源，
 * 否则重构过程中任意一次改动都可能只落在其中一条线路上，
 * 而口径分叉正是那份 Javadoc 自己预警的、真正危险的情况。
 *
 * <p>换言之：<strong>原判断在其前提（三份独立执行器长期并存）下是对的，
 * 是前提变了。</strong>这也解释了为何先做阶段 0（错误分类收归）再做本步 ——
 * 两步是同一件事的两个面：一个管「这是哪类失败」，一个管「要不要重发」。
 *
 * <h2>无状态</h2>
 * 全部为静态方法，不持有配置。重试<em>次数</em>来自
 * {@code RetryPolicyService}（运行时读 {@code app_config}），退避<em>时长</em>由
 * 各执行器的 {@code retryFirstBackoff()} / {@code retryMaxBackoff()} 提供 ——
 * 两者都不属于本类：「要不要重试」是纯判定，与「重试几次、等多久」是两件事。
 */
public final class UpstreamRetryPolicy {

    private UpstreamRetryPolicy() {
    }

    /**
     * 判定某次上游失败是否值得重试。
     *
     * <p>四类可恢复场景见类注释。注意判定顺序不影响结果 ——
     * 四者互斥（异常类型不同），只是让读的人先看到最常见的两类。
     *
     * @param failure 上游抛出的异常
     * @return 是否值得重试
     */
    public static boolean isRetryableFailure(Throwable failure) {
        if (failure instanceof WebClientRequestException) {
            // 连接建立失败：上游不可达 / 未响应
            return true;
        }
        if (hasSslHandshakeFailure(failure)) {
            // TLS 握手失败：瞬时网络环境问题
            return true;
        }
        if (failure instanceof WebClientResponseException responseException) {
            // HTTP 错误响应：状态码可重试，或实际为网络层中断
            return isRetryableStatus(responseException.getStatusCode())
                    || hasNetworkCause(responseException);
        }
        if (failure instanceof EmptyUpstreamResponseException) {
            // 空响应：HTTP 层通常是 200，但内容为空 —— 复用同一份重试预算，
            // 使「重试次数」只有 RetryPolicyService 一个来源，未来做可配置时不必改两处。
            return true;
        }
        return false;
    }

    /**
     * 判定 HTTP 状态码是否可重试。
     *
     * @param status 上游返回的状态码
     * @return 429（限速）、5xx（服务端错误）、400（容忍上游临时抽风）为可重试
     */
    public static boolean isRetryableStatus(HttpStatusCode status) {
        int code = status.value();
        return code == 429 || status.is5xxServerError() || code == 400;
    }

    /**
     * 判断异常的 cause chain 中是否包含网络层异常（{@code IOException} 及其子类，如
     * {@code SocketException}）。
     *
     * <p>这类异常通常表现为「HTTP 200 但 SSE 流中途断开」，需要重试。
     *
     * <p><strong>从 {@code getCause()} 起找而非自身</strong>：调用方只在已经确认
     * 它是 {@link WebClientResponseException} 之后才问这个问题，
     * 而那个容器本身不是 {@code IOException}。
     */
    public static boolean hasNetworkCause(Throwable throwable) {
        Throwable cause = throwable.getCause();
        while (cause != null) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 判断异常的 cause chain 中是否包含 SSL/TLS 握手失败。
     *
     * <p>SSL 握手异常通常由 Netty 的 {@code DecoderException} 包裹
     * {@code SSLHandshakeException}，既不属于 {@code WebClientRequestException}
     * 也不属于 {@code WebClientResponseException}，需要单独判断才支持重试。
     *
     * <p><strong>从自身起找</strong>（与 {@link #hasNetworkCause} 相反）：
     * 调用它时还不知道外层是什么类型，异常本身就可能直接是个
     * {@code SSLException}。
     */
    public static boolean hasSslHandshakeFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof javax.net.ssl.SSLException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 从异常链中解包出 {@link EmptyUpstreamResponseException}。
     *
     * <p>必须递归解包而不能按类型直接匹配：{@code retryWhen} 耗尽时原异常被包进
     * {@code RetryExhaustedException}，直接匹配会漏掉，于是「空响应耗尽后放行最后一轮内容」
     * 这条兜底永远不生效 —— 症状是下游收到一个 502 而不是上游那批空帧。
     *
     * @param throwable 待解包异常
     * @return 链上第一个空响应异常；没有则返回 null
     */
    public static EmptyUpstreamResponseException findEmptyUpstreamException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof EmptyUpstreamResponseException emptyResponse) {
                return emptyResponse;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 从异常链中解包出 {@link WebClientResponseException} —— 与
     * {@link #findEmptyUpstreamException} 同一族的<strong>解包器</strong>，同因同形。
     *
     * <p>同样必须递归：{@code retryWhen} 耗尽时原异常被包进
     * {@code RetryExhaustedException}，只看最外层会漏判，于是
     * 「失败往返落库」「错误响应不重复落库」两处判断同时失效 ——
     * 症状是<strong>上游明明回了 4xx，日志里却记成 statusCode -1 且没有错误体</strong>。
     *
     * <h2>与 {@code api.shared.UpstreamFailureClassifier} 无关（同名不同事）</h2>
     * 后者在<strong>出口</strong>按 {@code FailureKind} 分类，供控制器决定状态码与错误体；
     * 本方法在<strong>主干</strong>只做「捞出来」，不关心它是哪一类失败。
     * 两处都需要沿链查找，但产物与用途都不同，不合并。
     *
     * @param throwable 待解包异常
     * @return 链上第一个 HTTP 错误响应异常；没有则返回 null
     */
    public static WebClientResponseException findWebResponseException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                return responseException;
            }
            current = current.getCause();
        }
        return null;
    }
}
