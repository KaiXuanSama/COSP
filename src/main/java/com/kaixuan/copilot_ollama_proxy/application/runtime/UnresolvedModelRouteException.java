package com.kaixuan.copilot_ollama_proxy.application.runtime;

/**
 * 模型名解析不到<strong>唯一</strong>供应商时抛出。
 *
 * <h2>为什么值得一个独立类型</h2>
 * 此前三处应用服务各自抛裸 {@code RuntimeException("没有可用的上游服务来处理模型: …")}，
 * 控制器认不出它，只能落进 502 兜底分支、被译成「无法连接到上游服务」。
 * 而这条路径上<strong>上游一次都没被连接过</strong> ——
 * {@link ProviderRouteResolver#resolve} 在本地供应商目录里就返回了 null。
 * 把排查方向指向网络与上游可用性，是这个错误最容易造成的误导。
 *
 * <p>它与 {@code ProtocolTranslationNotSupportedException}、
 * {@code NoSupportedProtocolException}、{@code RequestTranslationException} 属于同一族：
 * <strong>都是「改请求或改配置」就能解决，都不该被报成网关故障</strong>。
 *
 * <h2>为何直接派生 {@link RuntimeException}</h2>
 * 与 {@code NoSupportedProtocolException} 刻意不同：那个继承 {@code IllegalStateException}
 * 是为了不破坏既有的「调度器对空集合必须报错」断言；本异常<strong>没有任何既有断言</strong>
 * 依赖某个父类型（改类型前全项目零测试断言过这条消息），因此不必借道某个标准异常。
 * 少一层继承就少一处「谁还会 catch 到它」的推断成本。
 *
 * <h2>三种成因（{@code resolve} 返回 null 的全部情形）</h2>
 * <ol>
 *   <li>模型名为 null 或空白；</li>
 *   <li>带了 {@code [provider-key]} 前缀，但该供应商不存在、未启用，或没声明这个模型；</li>
 *   <li>没带前缀，而该模型名同时命中多个供应商 —— 此时按约定<strong>拒绝</strong>解析，
 *       要求调用方改用带前缀的名字（无前缀只在唯一匹配时才允许路由）。</li>
 * </ol>
 * 三者都是<strong>下游请求的问题</strong>，改请求即可解决，因此控制器回 400 而非 502 ——
 * 5xx 会诱导客户端重试，而重试同一个名字结果不会变。
 *
 * <h2>消息暂与旧文案逐字一致</h2>
 * 本次只把<strong>类型与状态码</strong>掰正，不改措辞，这样评审时 diff 消息文本可见「零变化」。
 * 第 3 种成因最需要一句「请使用 {@code [provider-key]} 前缀」的指引，但那要求
 * {@code resolve()} 把「为什么没解析出来」一并返回，属于单独一步。
 *
 * @see ProviderRouteResolver#resolve(String)
 */
public class UnresolvedModelRouteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String model;

    /**
     * @param model 下游请求的原始模型名（可能含前缀），会原样进入消息
     */
    public UnresolvedModelRouteException(String model) {
        super("没有可用的上游服务来处理模型: " + model);
        this.model = model;
    }

    /** 下游请求的原始模型名，供上层记日志或断言，不必再回头解析消息。 */
    public String model() {
        return model;
    }
}
