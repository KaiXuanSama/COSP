package com.kaixuan.copilot_ollama_proxy.application.protocol;

/**
 * 上游响应无法翻译成下游协议时抛出的异常。
 *
 * <h2>与请求侧那两个异常的分工</h2>
 * <ul>
 *   <li>{@link ProtocolTranslationNotSupportedException} —— 这条协议组合本代理还没实现，
 *       是<strong>代理的能力缺口</strong>，改配置能绕开。</li>
 *   <li>{@link RequestTranslationException} —— 下游请求本身无法表达成目标协议，
 *       是<strong>下游请求的问题</strong>，必须改请求。</li>
 *   <li>本异常 —— <strong>上游响应</strong>不符合预期结构。既不是下游的错，
 *       也不是配置的错。</li>
 * </ul>
 *
 * <h2>为何这类失败应回 502 而非 400/500</h2>
 * 下游没做错任何事，代理本身也在正常工作，是上游给了个我们解析不了的东西。
 * 502 Bad Gateway 正是这个语义。控制器现有的「无法连接到上游服务」分支也是 502，
 * 归到同一档不会打乱既有的错误语义分层。
 *
 * <h2>不要用它包装「内容为空」</h2>
 * 空响应有专门的 {@code EmptyUpstreamResponseException} 并参与重试预算。
 * 本异常表达的是<strong>结构性</strong>不可解析（不是 JSON、缺关键容器），
 * 重试同一个上游多半会得到同样的结果。
 */
public class ResponseTranslationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResponseTranslationException(String message) {
        super(message);
    }
}
