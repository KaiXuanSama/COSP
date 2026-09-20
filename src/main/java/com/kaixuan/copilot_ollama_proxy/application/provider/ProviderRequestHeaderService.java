package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AuthHeaderSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 准备数据库供应商的通用出站请求头与 URL。
 *
 * 装配分三层，后者覆盖前者：先完整透传下游的端到端头（只排除
 * {@link #NON_FORWARDABLE_HEADERS} 里那些描述连接本身的头），再装配鉴权头，
 * 最后由供应商请求头规则覆盖、补充或删除任何头。
 *
 * 三层的职责边界不要混：第一层只做传输层正确性，第二层保证出站鉴权头承载的是供应商配置的
 * 凭据、且用的是用户指定的那一种头名，第三层承载「这个上游需要什么」的全部特例。
 * 因此凡是超出传输层正确性的取舍都不应下沉到第一层 —— 规则层拥有最终决定权是有意的设计。
 *
 * <h2>第二层由供应商级配置驱动，不由协议决定</h2>
 * 头名取自 {@link AuthHeaderSetting}（取下游 / 取设置 × {@code Authorization} /
 * {@code x-api-key}）。「哪种头」不是协议属性，依据与反面证据见
 * {@link #applyAuthenticationHeaders}。
 */
@Service
public class ProviderRequestHeaderService {

    private static final Logger log = LoggerFactory.getLogger(ProviderRequestHeaderService.class);
    private static final String DELETE_MARKER = "/del/";
    private static final TypeReference<List<Map<String, String>>> HEADER_RULE_LIST_TYPE = new TypeReference<>() {};

    /**
     * 不跨连接透传的请求头。
     *
     * 这份清单只关心传输层正确性，不是鉴权策略、也不是「哪些头不该给上游看」的黑名单。
     * 收录标准只有一条：该头描述的是「下游到 COSP 这一段连接」而非这条消息本身，
     * 因此它的值对新建的上游连接一律无效，必须由发起方重新生成。
     *
     * 前八项是 RFC 7230 §6.1 的 hop-by-hop 头。其中 transfer-encoding 说的是下游请求体
     * 怎么分帧的，而出站请求体由 Reactor Netty 重新编码，带着旧值出站会让报文自述与实际
     * 线格式矛盾；proxy-authorization 与 proxy-authenticate 是给中间代理的凭据，语义上
     * 只作用于当前这一跳，转发出去等于把代理凭据交给上游。
     *
     * 另两项是必须重算而非必须隐藏：host 要反映目标 authority，下游那个
     * localhost:11434 带到上游会打错虚拟主机或让 TLS SNI 对不上；content-length 要等于
     * 实际字节数，而 COSP 一路在改请求体（模型名替换、协议翻译、请求体规则、null 清洗），
     * 长度几乎必然变，带着旧长度比不带更糟 —— 上游要么在错误的偏移截断，要么一直等
     * 永远不会来的字节。
     *
     * 鉴权头刻意不在此列。Authorization 与 x-api-key 都是端到端头，描述消息而非连接，
     * 「完全透传下游请求头」是本服务的前提；它们由第二层的
     * {@link #applyAuthenticationHeaders} 统一改写，而非在这里剥离。
     * 把鉴权头加进来会把「改写成供应商凭据」偷换成「一律剥离」，届时第二层就没有可覆盖的对象了。
     *
     * 同理，Cookie 与 Accept-Encoding 也不在此列 —— 它们透传后可能带来问题
     * （如上游返回 Brotli 压缩的 SSE 流导致解码失败），但那属于「这个上游需要什么」，
     * 该由供应商的请求头规则按需删除或改写。本清单管的是「不这么做协议就不成立」，
     * 保持最小化，其余取舍一律交给规则层。
     */
    private static final Set<String> NON_FORWARDABLE_HEADERS = Set.of(
            "connection", "content-length", "host", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    /**
     * 以裸值承载凭据的那个鉴权头名（{@code x-api-key: <key>}，没有 scheme 前缀）。
     *
     * <h2>为何名字里不再有 Anthropic</h2>
     * 它曾叫 {@code ANTHROPIC_API_KEY_HEADER}，因为那时出站头名<strong>按上游协议</strong>
     * 决定，这个头专属于 Anthropic 那条线路。该映射实测不成立（头名由下游用的凭据变量决定，
     * 且部分中转站只认 {@code Authorization}），现在它只是两种<strong>可任选</strong>的
     * 承载方式之一，与协议无关 —— 留着旧名字会让人以为选它就等于「走 Anthropic」。
     *
     * <p>与 {@link HttpHeaders#AUTHORIZATION} 的差别只在报文形态：那个要
     * {@code Bearer} 前缀，这个是裸值。两者都是端到端头，一次出站只发其中一个
     * （见 {@link #applyAuthenticationHeaders}）。
     */
    public static final String API_KEY_HEADER = "x-api-key";

    private final ObjectMapper objectMapper;

    public ProviderRequestHeaderService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 合并默认头、下游请求头和供应商请求头规则。
     *
     * 优先级从低到高：下游可透传头、装配的鉴权与媒体类型、供应商规则。
     * Host、Content-Length 和 hop-by-hop 头不跨请求透传，由上游 HTTP 客户端重新计算。
     *
     * @param downstreamHeaders 下游请求头；既是透传来源，也是鉴权头装配的探测依据
     * @param authHeader        出站鉴权头的装配方式；{@code null} 按
     *                          {@link AuthHeaderSetting#defaults()} 处理
     */
    public void applyHeaders(HttpHeaders headers, HttpHeaders downstreamHeaders, String apiKey,
                             String headerRulesJson, boolean stream, AuthHeaderSetting authHeader) {
        copyForwardableHeaders(headers, downstreamHeaders);
        applyAuthenticationHeaders(headers, downstreamHeaders, apiKey, authHeader);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(stream ? MediaType.TEXT_EVENT_STREAM : MediaType.ALL));
        for (Map<String, String> rule : parseHeaderRules(headerRulesJson)) {
            String key = rule.get("key");
            String value = rule.get("value");
            if (key == null || key.isBlank()) {
                continue;
            }
            String trimmedKey = key.trim();
            if (DELETE_MARKER.equals(value)) {
                headers.remove(trimmedKey);
                log.debug("[Transform] 删除请求头: {}", trimmedKey);
                continue;
            }
            String resolvedValue = value == null ? "" : value.replace("{apiKey}", apiKey == null ? "" : apiKey);
            headers.set(trimmedKey, resolvedValue);
            log.debug("[Transform] 设置请求头: {} = {}", trimmedKey, maskValue(trimmedKey, resolvedValue));
        }
    }

    /**
     * 无下游上下文的请求头构造入口，供模型拉取与独立单元测试使用。
     *
     * <p>没有下游请求就没有「下游选了哪个头」可言：两项探测皆为假，
     * {@link AuthHeaderSetting#resolveHeader} 因此在<strong>两种模式下都</strong>落到配置值。
     * 这正是「取下游」模式下配置项仍然有意义的那个场景 —— 它不是用不上，而是兜底。
     */
    public void applyHeaders(HttpHeaders headers, String apiKey, String headerRulesJson,
                             AuthHeaderSetting authHeader) {
        applyHeaders(headers, HttpHeaders.EMPTY, apiKey, headerRulesJson, false, authHeader);
    }

    /**
     * 按供应商级配置装配出站鉴权头：探测 → 决定头名 → 删两个 → 注一个。
     *
     * <h2>为何头名不由协议决定</h2>
     * 这里曾经按出站协议分派 —— MESSAGES 发 {@code x-api-key} 并删 {@code Authorization}，
     * CHAT / RESPONSES 反之；依据是「Anthropic 官方用 x-api-key」。实测该依据不成立：
     * <ul>
     *   <li>Claude CLI 的头名由<strong>凭据环境变量</strong>决定，与协议无关 ——
     *       {@code ANTHROPIC_API_KEY} 发 {@code x-api-key}，
     *       {@code ANTHROPIC_AUTH_TOKEN} 发 {@code Authorization: Bearer}，
     *       而 cc-switch 默认走后者；</li>
     *   <li>部分中转站只认 {@code Authorization}，不认 {@code x-api-key}
     *       （anyrouter 实测：改发 Bearer 后响应从 503 变成 429，即通过了鉴权层）。</li>
     * </ul>
     * 根因不是「选错了那一档」，而是<strong>这个选择本身不该由代码替用户做</strong>，
     * 于是它成了供应商级配置。行为矩阵见 {@link AuthHeaderSetting}。
     *
     * <h2>探测读 {@code downstreamHeaders} 而不是 {@code headers}</h2>
     * 两者在当前调用形态下等价（两个鉴权头都不在 {@link #NON_FORWARDABLE_HEADERS} 里，
     * 因此 {@code copyForwardableHeaders} 会把它们覆盖进 target；而所有调用点给的 target
     * 都是新建的空 {@code HttpHeaders}），但读参数<strong>不依赖那个前提</strong>：
     * 它不要求拷贝步骤先执行，也不会在将来某个调用点传入预置了鉴权头的 target 时误判成
     * 「下游带了」。省掉一条顺序依赖比省掉一个参数更值。
     *
     * <h2>空值不算「带了」</h2>
     * {@code HttpHeaders.containsHeader} 只判键在不在，{@code Authorization: ""} 也会返回
     * {@code true}。用它会把一个空头当成「下游做了选择」，把取下游模式顶到「恰好一个」
     * 的分支上 —— 而那个空头恰恰说明下游什么都没表达。故按值判空。
     *
     * <h2>「删两个」不能省</h2>
     * 「写」用覆盖而非补缺 —— 出站凭据必须是供应商配置的那把 key，不能由下游透传值决定。
     * x-api-key 一侧曾经是「缺失才设」，下游带了就补不进去，而 Authorization 看起来是对的，
     * 是最难排查的那种缺口。
     *
     * 「删」是因为没被选中的那个鉴权头在这条出站链路上是纯噪音：它可能来自下游透传
     * （Claude 系客户端按官方惯例把它放在那里），也可能来自翻译路线上下游与上游协议不一致。
     * 留着它至少有两个坏处：把下游的凭据泄露给上游供应商；以及遇到严格上游时因多余认证头被拒，
     * 而排查时会看到「该发的头明明是对的」。
     *
     * <p>因此实现是「<strong>先删两个、再注一个</strong>」而不是「只改选中那个头的值」：
     * 在「下游恰好带一个」时两者净效果相同，但前者少一条分支，且天然保证了
     * 没被选中的那一侧一定不带下游的值出站。
     *
     * <h2>不做任何自动回退</h2>
     * 上游若不认这个头名会返回 401/403，本服务<strong>不</strong>换另一个头重试、
     * 不按错误码回退、不按 base URL 猜测中转站类型。那等于把「哪个头有效」的猜测搬回代码里，
     * 而且悄悄换过之后用户从界面和日志上都看不出发生了什么 —— 排查会指向凭据而非配置。
     * 401 配上调用日志里的出站头名是完整可自查的引导，与「不按模型名降级思考档位」
     * 是同一条原则。
     *
     * <p>本方法刻意在请求头规则<strong>之前</strong>执行，规则因此保留最终决定权：
     * 需要双头并存的中转站可以用规则把另一个加回来，需要非 Bearer 形态的
     * 可以用 {@code {apiKey}} 占位改写。
     */
    private void applyAuthenticationHeaders(HttpHeaders headers, HttpHeaders downstreamHeaders,
                                            String apiKey, AuthHeaderSetting authHeader) {
        AuthHeaderSetting setting = authHeader == null ? AuthHeaderSetting.defaults() : authHeader;
        AuthHeaderSetting.Header target = setting.resolveHeader(
                hasNonBlankHeader(downstreamHeaders, HttpHeaders.AUTHORIZATION),
                hasNonBlankHeader(downstreamHeaders, API_KEY_HEADER));

        headers.remove(HttpHeaders.AUTHORIZATION);
        headers.remove(API_KEY_HEADER);

        String resolvedKey = apiKey == null ? "" : apiKey;
        if (target == AuthHeaderSetting.Header.X_API_KEY) {
            headers.set(API_KEY_HEADER, resolvedKey);
        } else {
            headers.setBearerAuth(resolvedKey);
        }
    }

    /**
     * 判断请求头里是否有该头且值非空白。
     *
     * <p>不用 {@code containsHeader}：它只判键在不在，详见
     * {@link #applyAuthenticationHeaders} 的「空值不算带了」。
     */
    private boolean hasNonBlankHeader(HttpHeaders headers, String name) {
        if (headers == null) {
            return false;
        }
        String value = headers.getFirst(name);
        return value != null && !value.isBlank();
    }

    /**
     * 规范化上游 Base URL，移除首尾空白与尾部斜杠。
     */
    public String normalizeBaseUrl(String rawBaseUrl) {
        return rawBaseUrl == null ? "" : rawBaseUrl.trim().replaceAll("/+$", "");
    }

    /**
     * 将规范化后的 Base URL 与路径拼接；路径缺省时由调用方决定。
     */
    public String buildRequestUrl(String rawBaseUrl, String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim();
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return normalizeBaseUrl(rawBaseUrl) + path;
    }

    /**
     * 为调用日志生成请求头安全快照。
     *
     * 调用方应传入 WebClient 请求过滤器收到的 headers，以确保快照已经包含默认头、
     * 规则头和请求级头（如 Accept）。敏感值统一脱敏，避免 API Key、Cookie 或令牌落库。
     */
    public Map<String, String> createLogSnapshot(HttpHeaders headers) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            String value = String.join(", ", values);
            snapshot.put(canonicalHeaderName(name), isSensitiveHeader(name) ? "****" : value);
        });
        return snapshot;
    }

    /**
     * 将后续捕获的请求头合并到现有日志快照，头名按 HTTP 语义忽略大小写。
     *
     * WebClient 层与 Reactor Netty 层看到的头集合并不完全相同：前者包含规则头和
     * 编码器头，后者包含 User-Agent、Host、Transfer-Encoding 等传输层头。
     */
    public void mergeLogSnapshot(Map<String, String> target, HttpHeaders headers) {
        createLogSnapshot(headers).forEach((name, value) -> {
            String existingName = target.keySet().stream()
                    .filter(key -> key.equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
            if (existingName == null) {
                target.put(name, value);
            } else {
                target.put(existingName, value);
            }
        });
    }

    private void copyForwardableHeaders(HttpHeaders target, HttpHeaders source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        source.forEach((name, values) -> {
            if (!isForwardableHeader(name)) {
                return;
            }
            target.remove(name);
            target.put(name, List.copyOf(values));
        });
    }

    private boolean isForwardableHeader(String headerName) {
        return headerName != null && !NON_FORWARDABLE_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }

    private List<Map<String, String>> parseHeaderRules(String headerRulesJson) {
        try {
            return objectMapper.readValue(headerRulesJson == null ? "[]" : headerRulesJson, HEADER_RULE_LIST_TYPE);
        } catch (Exception exception) {
            log.warn("[Transform] 解析请求头规则失败: {}", exception.getMessage());
            return List.of();
        }
    }

    private String maskValue(String headerName, String value) {
        if (isSensitiveHeader(headerName)) {
            return value.length() > 8 ? value.substring(0, 4) + "****" : "****";
        }
        return value;
    }

    private boolean isSensitiveHeader(String headerName) {
        String normalizedName = headerName == null ? "" : headerName.toLowerCase();
        return normalizedName.contains("authorization")
                || normalizedName.contains("api-key")
                || normalizedName.contains("apikey")
                || normalizedName.contains("api_key")
                || normalizedName.contains("token")
                || normalizedName.contains("secret")
                || normalizedName.contains("password")
                || normalizedName.contains("cookie");
    }

    private String canonicalHeaderName(String headerName) {
        if (headerName == null) {
            return "";
        }
        return switch (headerName.toLowerCase()) {
            case "accept" -> HttpHeaders.ACCEPT;
            case "authorization" -> HttpHeaders.AUTHORIZATION;
            case "content-length" -> HttpHeaders.CONTENT_LENGTH;
            case "content-type" -> HttpHeaders.CONTENT_TYPE;
            case "cookie" -> HttpHeaders.COOKIE;
            case "host" -> HttpHeaders.HOST;
            case "transfer-encoding" -> HttpHeaders.TRANSFER_ENCODING;
            case "user-agent" -> HttpHeaders.USER_AGENT;
            default -> headerName;
        };
    }
}