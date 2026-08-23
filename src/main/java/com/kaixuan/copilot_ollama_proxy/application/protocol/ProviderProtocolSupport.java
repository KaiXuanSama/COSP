package com.kaixuan.copilot_ollama_proxy.application.protocol;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * 供应商支持的<strong>上游线路协议</strong>集合。
 *
 * <h2>为何是集合而不是单值</h2>
 * 一个中转站可以同时提供 OpenAI 兼容与 Anthropic 两套端点，用单值枚举无法表达
 * 「两者都支持」。而一旦支持集合，「同名协议优先直连」就成了有意义的规则 ——
 * 下游打 OpenAI 端点、供应商两种都支持时走 OpenAI 直连，不必绕翻译。
 *
 * <p>不设「偏好哪个协议」的额外配置：若某供应商的 Anthropic 端点有问题，
 * 取消勾选它即可表达「强制走 OpenAI + 翻译」，无需第二个维度。
 *
 * <h2>V8.8 起读数据库</h2>
 * 取值来自 {@code provider_config.supported_protocols}（JSON 字符串数组）。
 * 在此之前本类对所有供应商一律返回全集，代价是下游打 Anthropic 端点、而供应商实际
 * 只有 OpenAI 端点时，失败发生在上游侧 —— 日志里是一个上游 404，看起来像上游故障。
 * 落库后这类请求由 {@link ProtocolDispatchManager} 提前拦下，原因明确。
 *
 * <h2>解析失败一律放行为全集</h2>
 * 脏数据不该让一个供应商彻底不可用：{@code CHECK (json_valid(...))} 已挡住非 JSON，
 * 剩下的异常形态（不是数组、元素不是已知协议名）说明配置有问题，但把它判成「一种都不支持」
 * 会让该供应商的<strong>所有</strong>调用失败，而回退到全集只是回到 V8.8 之前的行为 ——
 * 后者顶多在上游侧失败，前者是本地全面拒绍。同一个坏配置，前者可排查、后者像是服务坏了。
 *
 * <p><strong>但显式的空数组要如实返回。</strong>它是用户主动声明的「哪条线路都不要」，
 * 与「字段缺失 / 读不懂」不是一回事；把它也补成全集，「配置成空集」这一非法状态就永远
 * 无法被 {@link ProtocolDispatchManager} 的规则 3 发现。
 */
public final class ProviderProtocolSupport {

    private static final Logger log = LoggerFactory.getLogger(ProviderProtocolSupport.class);

    /**
     * 解析失败或字段缺失时的回退：两种协议都支持。
     *
     * <p>用 {@code EnumSet.allOf} 而非硬编码两个值，纯粹是让常量与枚举保持同源，
     * 不为「将来可能有第三种协议」做准备 —— OpenAI 与 Anthropic 已是事实标准，
     * 第三种协议出现的概率极低。
     */
    private static final Set<WireProtocol> OPTIMISTIC_ALL = EnumSet.allOf(WireProtocol.class);

    private ProviderProtocolSupport() {
    }

    /**
     * 读取某供应商支持的上游协议集合。
     *
     * @param provider 供应商运行时配置
     * @return 支持的协议集合（不可变）；解析失败时回退为全部协议
     */
    public static Set<WireProtocol> of(ProviderRuntimeConfiguration provider) {
        if (provider == null) {
            return OPTIMISTIC_ALL;
        }
        return parse(provider.providerKey(), provider.supportedProtocolsJson());
    }

    /** 供应商是否支持指定的上游协议。 */
    public static boolean supports(ProviderRuntimeConfiguration provider, WireProtocol protocol) {
        return of(provider).contains(protocol);
    }

    /**
     * 解析协议集合 JSON。
     *
     * <p>手工解析而不注入 {@code ObjectMapper}：本类是无状态工具，注入依赖就得变成 Bean，
     * 进而波及所有调用点（含调度器与测试）。而要解析的形状只是「字符串数组」，
     * 用拆元素的方式表达完整且无歧义，不值得为它换掉整个类的形态。
     *
     * <p>未知协议名（比如将来降级回滚、库里留着新版本写入的第三种协议）被忽略而非报错：
     * 忽略后集合仍包含全部认得的协议，功能正常；报错则会让整个供应商不可用。
     */
    private static Set<WireProtocol> parse(String providerKey, String json) {
        if (json == null || json.isBlank()) {
            return OPTIMISTIC_ALL;
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            log.warn("供应商 {} 的协议支持配置不是 JSON 数组，按两种协议都支持处理: {}", providerKey, json);
            return OPTIMISTIC_ALL;
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            // 显式空数组：如实返回空集，交由调度器报出「未声明支持任何协议」。
            return Set.of();
        }
        Set<WireProtocol> protocols = EnumSet.noneOf(WireProtocol.class);
        for (String element : body.split(",")) {
            String name = element.trim().replace("\"", "").trim();
            if (name.isEmpty()) {
                continue;
            }
            WireProtocol protocol = resolve(name);
            if (protocol == null) {
                log.warn("供应商 {} 的协议支持配置含未知协议 {}，已忽略", providerKey, name);
                continue;
            }
            protocols.add(protocol);
        }
        if (protocols.isEmpty()) {
            log.warn("供应商 {} 的协议支持配置没有任何可识别的协议，按两种协议都支持处理: {}",
                    providerKey, json);
            return OPTIMISTIC_ALL;
        }
        return Set.copyOf(protocols);
    }

    private static WireProtocol resolve(String name) {
        for (WireProtocol protocol : WireProtocol.values()) {
            if (protocol.name().equalsIgnoreCase(name)) {
                return protocol;
            }
        }
        return null;
    }
}
