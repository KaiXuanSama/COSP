package com.kaixuan.copilot_ollama_proxy.application.protocol;

/**
 * 供应商一种线路协议都没声明支持时抛出。
 *
 * <h2>为何从 {@link IllegalStateException} 派生而不是新起一支</h2>
 * 它表达的确实是「配置处于非法状态」，语义上属于 {@code IllegalStateException}；
 * 继承使既有的「调度器对空集合必须报错」断言不必跟着改，也让任何只认
 * {@code IllegalStateException} 的兜底逻辑行为不变。
 *
 * <p>之所以还要独立类型，是为了让控制器能<strong>精确</strong>识别它 ——
 * 直接 catch {@code IllegalStateException} 会把 Reactor、Jackson 或任何库抛出的
 * 同类异常一并译成「协议没配」，那种误导比笼统的 500 更难排查。
 *
 * <h2>与 {@link ProtocolTranslationNotSupportedException} 的分工</h2>
 * 那个是「勾了 A、下游打 B，而 B→A 的翻译本代理还没实现」；
 * 本异常是「一个都没勾」。两者都要用户去改供应商配置，但改法不同：
 * 前者可以选「勾上 B」或「换供应商」，后者只有「至少勾一个」。
 * 消息因此不能共用模板。
 */
public class NoSupportedProtocolException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final String providerKey;

    /**
     * @param providerKey 目标供应商标识，空时退到不点名的通用消息
     */
    public NoSupportedProtocolException(String providerKey) {
        super(buildMessage(providerKey));
        this.providerKey = providerKey;
    }

    private static String buildMessage(String providerKey) {
        String provider = providerKey == null || providerKey.isBlank()
                ? "目标供应商" : "供应商 " + providerKey;
        return provider + " 未声明支持任何线路协议，无法调度。"
                + "请在供应商配置里至少勾选一种协议（OpenAI 或 Anthropic）";
    }

    public String providerKey() {
        return providerKey;
    }
}
