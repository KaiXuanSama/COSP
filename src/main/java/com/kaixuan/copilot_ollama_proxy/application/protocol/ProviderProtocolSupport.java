package com.kaixuan.copilot_ollama_proxy.application.protocol;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;

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
 * <h2>本阶段是乐观假设</h2>
 * 第一阶段刻意不动数据库，因此 {@link #of} 对所有供应商一律返回「两种都支持」。
 * 这让协议调度链路可以先跑通并被验证，代价是：下游打 Anthropic 端点、
 * 而供应商实际只有 OpenAI 端点时，上游会直接失败（404 或协议错误）而非被提前拦下。
 * 该失败发生在上游侧、有完整日志与重试记录，不会静默；补上数据库字段后即消失。
 */
public final class ProviderProtocolSupport {

    /**
     * 乐观假设：所有供应商都支持两种协议。
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
     * @param provider 供应商运行时配置；当前实现不读取它的任何字段
     * @return 支持的协议集合（不可变）；本阶段恒为全部协议
     */
    public static Set<WireProtocol> of(ProviderRuntimeConfiguration provider) {
        // TODO 协议支持尚未落库，此处对所有供应商一律返回「两种都支持」。
        //  待 provider_config 增加协议支持列后改为读 provider 的该字段，
        //  并在 ProviderRuntimeConfiguration 中带上它（这也是 provider 参数
        //  现在就保留在签名里的原因 —— 届时不必波及所有调用点）。
        //  在此之前：下游打 Anthropic 端点而供应商实际只有 OpenAI 端点时，
        //  上游会返回 404 / 协议错误，而非被本方法提前拦下。该失败有完整日志，不会静默。
        return OPTIMISTIC_ALL;
    }

    /** 供应商是否支持指定的上游协议。 */
    public static boolean supports(ProviderRuntimeConfiguration provider, WireProtocol protocol) {
        return of(provider).contains(protocol);
    }
}
