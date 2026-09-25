package com.kaixuan.copilot_ollama_proxy.pipeline;

/**
 * 请求处理管道里的一个<strong>可能被跳过的步骤</strong>。
 *
 * <h2>为什么需要这个枚举</h2>
 * 管道里绝大多数步骤<strong>每条请求都会执行</strong>（路由、调度、请求体规则、
 * 头装配、发送、落库……），因而没有「跳过」这一态，也就无需登记。
 * 只有那些<strong>按协议组合可有可无</strong>的步骤需要登记，而它们全是翻译链上的：
 * <ul>
 *   <li>{@link #REQUEST_TRANSLATION} 只在「下游协议 ≠ 上游协议」时才需要；</li>
 *   <li>{@link #RESPONSE_TRANSLATION} 同上，且<strong>两者可以只有其一</strong> ——
 *       这正是「半实现态」的来源。</li>
 * </ul>
 *
 * <p>没有实现的步骤<strong>跳过</strong>，而不是报错。这是刻意的：
 * 一个方向「只做了一半」是<em>开发新协议翻译时的中间态</em>，
 * 开发者需要能看到上游真实返回的帧来对齐契约，而不是收到一个
 * 「本方向未实现」的报错 —— 那个报错会让他无法判断自己的去程翻译对不对。
 *
 * <h2>为何暂时只有两个值</h2>
 * 只有一个消费者（空响应拦截），它只读这两个。
 *
 * <p><strong>3.1 把上游响应归一（{@code UpstreamChunkNormalizer}）与 reasoning fallback
 * 接成查表支线之后，这里并没有补上对应枚举值</strong> —— 而那个预测是错的，
 * 原因值得记下：那些步骤的「跳过」由<strong>查表未命中</strong>直接表达
 * （{@code ChunkStageRegistry} 返回空即跳过），不需要额外登记一位状态。
 * 登记是用来回答「这个步骤执行过了吗」的，而支线只需要回答「该不该执行」——
 * 后者查表就有答案，多记一位反而会让人以为它在参与决策。
 *
 * <p>因此本枚举的成员权限收紧为：<strong>只登记「按协议组合可有可无、且判据要读它」的步骤</strong>。
 * 目前这样的步骤只有翻译链的两半（空响应拦截要读回程登记来决定是否介入）。
 *
 * <h2>与协议的关系</h2>
 * 本枚举描述<strong>步骤</strong>而非协议。同一个步骤在不同协议组合下的有无实现，
 * 由编排层按请求逐个登记（入口是 {@link RequestPipelineContext#markCompleted}）——
 * 不在这里写「哪个协议支持哪一步」的映射表，那种表会在新增协议时静默过期。
 */
public enum PipelineStep {

    /**
     * 去程翻译：把下游形态的请求体改写成上游形态。
     *
     * <p>它被跳过只可能发生在两侧协议相同时（直连），此时整条翻译链都不存在。
     */
    REQUEST_TRANSLATION,

    /**
     * 回程翻译：把上游形态的响应改写成下游形态。
     *
     * <p><strong>它是「半实现态」的判据</strong>：一个方向若只接了去程翻译
     * （{@link #REQUEST_TRANSLATION}）而没接回程，上游发回的帧就是上游协议的形态，
     * 而下游期待的是另一种形态。此时空响应拦截必须<strong>跳过</strong> ——
     * 它按<em>本服务所服务的协议</em>判「有没有内容」，而那批帧属于另一个协议，
     * 判定结果没有意义。判据见 {@link RequestPipelineContext#shouldApplyEmptyResponseGate()}。
     */
    RESPONSE_TRANSLATION
}
