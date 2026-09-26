package com.kaixuan.copilot_ollama_proxy.api.shared;

import com.kaixuan.copilot_ollama_proxy.api.shared.UpstreamFailureClassifier.Failure;
import com.kaixuan.copilot_ollama_proxy.api.shared.UpstreamFailureClassifier.FailureKind;
import org.slf4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 把失败渲染成 HTTP 响应 / SSE 错误体 —— <strong>分类骨架共用、body 形状各协议自备</strong>。
 *
 * <h2>它与 {@link UpstreamFailureClassifier} 的分工</h2>
 * 分类器只回答「这是什么失败」（{@link FailureKind}），并明说<strong>不做渲染</strong> ——
 * 「渲染是各控制器的出口，由下游协议决定」。本类补上分类与渲染之间那层骨架：
 *
 * <pre>
 *   classify(ex) → FailureKind ─┬─→ 状态码 + 日志   ← 本类（三条线路逐字相同）
 *                                └─→ body 形状       ← ErrorBodies（各协议一份）
 * </pre>
 *
 * <h2>为何状态码与日志可以共用，body 不行</h2>
 * 状态码表达的是<strong>「用户该去改什么」</strong>（见分类器的可操作性对照表），
 * 与下游说的是哪个协议无关：四个「上游没被连上」的类别都是 400，其余 502，
 * 上游 HTTP 错误原样透传。日志同理 —— 它是给运维看的，不面向客户端。
 *
 * <p>而 body 是<strong>下游客户端要解析的东西</strong>，形状由协议规定：
 * Chat 与 Responses 非流式用 {@code {"error":{...}}}，Anthropic 多一层 {@code "type":"error"}，
 * Responses <strong>流式</strong>用扁平事件体（顶层带 {@code type}）——
 * 后者若复用非流式骨架，客户端的事件状态机无法分派，症状是<strong>流挂住、界面转圈</strong>
 * 而不是报错。那是刻意保留的差异，由 {@link ErrorBodies} 表达。
 *
 * <h2>两个入口对应两条出口路径</h2>
 * <ul>
 *   <li>{@link #response} —— <strong>非流式</strong>：状态码还没提交，给完整 {@code ResponseEntity}；</li>
 *   <li>{@link #streamBody} —— <strong>流式</strong>：状态码在第一帧就提交了，
 *       改它没有意义，只能给一个 error 帧的 body。</li>
 * </ul>
 * 两者的<strong>类别判定完全相同</strong>，只有「怎么送出去」不同 —— 这正是两个入口并列的理由。
 *
 * <h2>本类为何无状态、纯静态</h2>
 * 与 {@link UsageAccounting} / {@link StreamLifecycle} 同一取向：
 * 不做 Spring Bean、不持有字段。<strong>logger 走参数</strong>是为了让三条日志
 * 保持各自的<strong>端点归属</strong>（{@code c.k.c.api.anthropic.AnthropicController}），
 * 按端点过滤日志的人才能看到它们。
 *
 * @see UpstreamFailureClassifier
 */
public final class UpstreamErrorRenderer {

    private UpstreamErrorRenderer() {
    }

    /**
     * 每个下游协议自备的 body 形状 —— 出口判据（「由下游协议决定」）的落点。
     *
     * <p>三个方法对应失败渲染需要的三种 body：
     * <ul>
     *   <li>{@link #badRequest} —— 400 类（上游没被连上）的 body；</li>
     *   <li>{@link #upstreamError} —— 502 类（连上了但无法处理）的 body；</li>
     *   <li>{@link #adaptUpstreamBody} —— 上游 HTTP 错误原文的适配，
     *       <strong>默认原样透传</strong>，只有 Responses 流式覆写。</li>
     * </ul>
     *
     * <p>为何 400 与 502 分成两个方法而不是「一个方法带状态码」：某些协议的 400 与 502 体
     * <strong>形状相同但 {@code type} 取值不同</strong>（Chat 用 {@code invalid_request_error}
     * 与 {@code upstream_error}）。用一个方法带状态码参数会把那个差异挤进参数里，
     * 读的人反而看不出「原来这两档长得不一样」。
     *
     * <p>实现必须走序列化而不拼字符串：message 可能来自异常消息，内容不可控 ——
     * 一个引号或换行就能把错误体本身变成非法 JSON，而客户端解析失败后看到的是
     * 一个完全无关的错误。
     */
    public interface ErrorBodies {

        /** 400 类失败的 body。 */
        String badRequest(String message);

        /** 502 类失败的 body。 */
        String upstreamError(String message);

        /**
         * 上游 HTTP 错误原文的适配。
         *
         * <p><strong>默认原样透传</strong>：上游那句话（余额不足、模型不存在、限流）
         * 往往比我们能编的任何文案都准确，且状态码本身也是透传的。
         *
         * <p>只有 <strong>Responses 流式</strong>覆写它 —— 那里的上游原文可能是
         * REST 错误体（顶层没有 {@code type}），原样下发等于制造一帧无法被事件状态机
         * 分派的脏数据。它需要判断「原文是否已是合法 error 事件」，那是
         * <strong>Responses 特有的</strong>协议知识。
         *
         * @param raw       上游错误响应体原文
         * @param exception 携带状态码的异常（适配方需要时用，如拼「上游返回错误 4xx」）
         */
        default String adaptUpstreamBody(String raw, WebClientResponseException exception) {
            return raw;
        }
    }

    /**
     * 渲染<strong>非流式</strong>错误响应（含状态码）。
     *
     * <p>状态码分两层，判据是<strong>「上游到底有没有被连上」</strong>：
     * <ul>
     *   <li><strong>400</strong>（四个类别）—— 请求根本没发出去。与 502 的区别在于
     *       「改什么才能解决」：改配置、改请求、改模型名，都与上游可用性无关。
     *       用 5xx 会诱导客户端重试，而重试同一份输入结果不会变。</li>
     *   <li><strong>502</strong>（响应翻译失败、兜底）—— 请求发出去了，
     *       下游没做错任何事，不该报 400。</li>
     * </ul>
     *
     * <p>上游 HTTP 错误<strong>原样透传状态码与错误体</strong>，不包一层自己的解释。
     *
     * @param ex    失败异常
     * @param model 模型名（含前缀），仅用于日志
     * @param log   端点自己的 logger，保持日志归属
     * @param bodies 本协议的 body 形状
     * @return 已具备状态码与错误体的响应
     */
    public static ResponseEntity<?> response(Throwable ex, String model, Logger log, ErrorBodies bodies) {
        Failure failure = UpstreamFailureClassifier.classify(ex);
        return switch (failure.kind()) {
            case PROTOCOL_UNSUPPORTED -> {
                log.warn("协议不可用 [{}]: {}", model, failure.message());
                yield badRequest(bodies, failure.message());
            }
            case NO_SUPPORTED_PROTOCOL -> {
                log.warn("供应商未配置任何协议 [{}]: {}", model, failure.message());
                yield badRequest(bodies, failure.message());
            }
            case REQUEST_TRANSLATION -> {
                log.warn("请求翻译失败 [{}]: {}", model, failure.message());
                yield badRequest(bodies, failure.message());
            }
            case UNRESOLVED_MODEL_ROUTE -> {
                log.warn("模型未解析到供应商 [{}]: {}", model, failure.message());
                yield badRequest(bodies, failure.message());
            }
            case UPSTREAM_HTTP -> {
                WebClientResponseException upstream = failure.asHttpFailure();
                log.warn("上游 API 返回错误 [{}] {}: {}", model,
                        upstream.getStatusCode().value(), upstream.getResponseBodyAsString());
                yield ResponseEntity.status(upstream.getStatusCode().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(upstream.getResponseBodyAsString());
            }
            case RESPONSE_TRANSLATION -> {
                log.warn("响应翻译失败 [{}]: {}", model, failure.message());
                yield upstreamError(bodies, failure.message());
            }
            default -> {
                log.warn("上游 API 调用失败 [{}]: {} ({})", model,
                        extractRootCause(ex), extractRequestUrl(ex));
                yield upstreamError(bodies, CONNECT_FAILED_MESSAGE);
            }
        };
    }

    /**
     * 渲染<strong>流式</strong> error 帧的 body。
     *
     * <p>与非流式不能共用一个方法：流式的状态码在第一帧就提交了，之后改它没有意义，
     * 客户端只能靠 SSE 的 {@code event: error} 识别失败。类别判定完全相同，
     * 只有「怎么送出去」不同。
     *
     * <p>上游 HTTP 错误在这里也走 {@link ErrorBodies#adaptUpstreamBody} ——
     * 默认原样透传错误体（不带状态码，那已无处可放），与非流式同一取向。
     *
     * @param ex     失败异常
     * @param model  模型名（含前缀），仅用于日志
     * @param log    端点自己的 logger
     * @param bodies 本协议的 body 形状
     * @return error 帧的 body（调用方负责包成 {@code ServerSentEvent}）
     */
    public static String streamBody(Throwable ex, String model, Logger log, ErrorBodies bodies) {
        Failure failure = UpstreamFailureClassifier.classify(ex);
        return switch (failure.kind()) {
            case PROTOCOL_UNSUPPORTED -> {
                log.warn("协议不可用 [{}]: {}", model, failure.message());
                yield bodies.badRequest(failure.message());
            }
            case NO_SUPPORTED_PROTOCOL -> {
                log.warn("供应商未配置任何协议 [{}]: {}", model, failure.message());
                yield bodies.badRequest(failure.message());
            }
            case REQUEST_TRANSLATION -> {
                // 能走到这里是因为服务层的 Flux.defer 把组装期异常转成了 onError 信号；
                // 否则它会逃出控制器变成 500 JSON。
                log.warn("请求翻译失败 [{}]: {}", model, failure.message());
                yield bodies.badRequest(failure.message());
            }
            case UNRESOLVED_MODEL_ROUTE -> {
                log.warn("模型未解析到供应商 [{}]: {}", model, failure.message());
                yield bodies.badRequest(failure.message());
            }
            case UPSTREAM_HTTP -> {
                WebClientResponseException upstream = failure.asHttpFailure();
                log.warn("上游 API 返回错误 [{}] {}: {}", model,
                        upstream.getStatusCode().value(), upstream.getResponseBodyAsString());
                yield bodies.adaptUpstreamBody(upstream.getResponseBodyAsString(), upstream);
            }
            case RESPONSE_TRANSLATION -> {
                log.warn("响应翻译失败 [{}]: {}", model, failure.message());
                yield bodies.upstreamError(failure.message());
            }
            default -> {
                log.warn("上游 API 调用失败 [{}]: {} ({})", model,
                        extractRootCause(ex), extractRequestUrl(ex));
                yield bodies.upstreamError(CONNECT_FAILED_MESSAGE);
            }
        };
    }

    /**
     * 兜底文案。
     *
     * <p>它描述的是<strong>「我们没能得到可用响应」</strong>，而不是断言的网络故障 ——
     * 走到这里的原因可能是连接、DNS、TLS、超时、响应被截断，或空响应重试耗尽
     * （那些都不是「连不上」）。措辞保持与既有一致，不在本步改文案。
     */
    private static final String CONNECT_FAILED_MESSAGE = "无法连接到上游服务";

    private static ResponseEntity<?> badRequest(ErrorBodies bodies, String message) {
        return ResponseEntity.status(400).contentType(MediaType.APPLICATION_JSON)
                .body(bodies.badRequest(message));
    }

    private static ResponseEntity<?> upstreamError(ErrorBodies bodies, String message) {
        return ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON)
                .body(bodies.upstreamError(message));
    }

    /**
     * 从异常链中提取最底层的有意义错误信息，过滤掉 Reactor/Netty 内部异常。
     *
     * <p>例如 DNS 解析失败会提取 {@code "Failed to resolve 'api.kimi.com'"}。
     *
     * <h2>为何从 Chat 搬到此处（阶段 6 步 1）</h2>
     * 它此前<strong>只有 OpenAI 端点有</strong>，另两条的兜底分支打印
     * {@code ex.getMessage()} —— 那是 Reactor 包装后的消息。后果是同一个网络故障，
     * Chat 的日志能看出目标主机，另两条看不出。<strong>只影响排查体验</strong>
     * （用户看到的一直是 {@link #CONNECT_FAILED_MESSAGE}），但那正是本方法存在的全部理由。
     */
    private static String extractRootCause(Throwable throwable) {
        Throwable deepest = throwable;
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            String msg = current.getMessage();
            // 跳过无意义的包装异常（Reactor、Netty 内部）
            if (msg != null && !msg.isBlank() && !msg.startsWith("Retries exhausted")) {
                deepest = current;
            }
        }
        String msg = deepest.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : deepest.getClass().getSimpleName();
    }

    /**
     * 从异常中提取请求 URL（如有）。
     *
     * <p>{@link WebClientRequestException} 携带 URI；Reactor 的 checkpoint 则把
     * {@code "Request to POST <url>"} 放在 message 里，两条都试。
     */
    private static String extractRequestUrl(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientRequestException requestEx) {
                java.net.URI uri = requestEx.getUri();
                if (uri != null) {
                    return uri.toString();
                }
            }
            // 从 Reactor checkpoint 中提取 URL
            String msg = current.getMessage();
            if (msg != null && msg.contains("Request to POST ")) {
                int start = msg.indexOf("Request to POST ") + 16;
                int end = msg.indexOf(" ", start);
                if (end < 0) {
                    end = msg.indexOf("]", start);
                }
                if (end < 0) {
                    end = msg.length();
                }
                return msg.substring(start, end).trim();
            }
            current = current.getCause();
        }
        return "unknown";
    }
}
