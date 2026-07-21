package com.kaixuan.copilot_ollama_proxy.protocol.lifecycle;

/**
 * 单次调用的生命周期阶段。
 *
 * <p>用于前端 Toast 观察每次 Copilot 调用流经代理时的实时状态，
 * 判断"是否卡住 / 上游是否有响应"。阶段流转：
 *
 * <pre>
 *   流式：  RECEIVED -> [RETRYING...] -> CONNECTED -> CHUNK(多次) -> COMPLETED
 *   非流式：RECEIVED -> [RETRYING...] -> CONNECTED -> COMPLETED
 *   任意阶段出错：-> FAILED
 *   客户端（下游 Copilot）主动断连：-> CANCELED
 *   管理员在等待首字期间主动取消：-> ABORTED
 * </pre>
 *
 * <p>RETRYING 只在首字到达之前出现（连接建立失败、429/5xx/可重试 400、SSL 握手失败），
 * 可能出现多次（每次重试前一次）；一旦收到上游响应头进入 CONNECTED，就不会再退回 RETRYING。
 *
 * <p>后端只下发阶段枚举与必要数据（chunk 计数等），具体展示文案由前端根据阶段渲染，
 * 保持"能力/展示分离"——后端不掺杂 UI 文案。
 */
public enum CallPhase {

    /** 下游请求已被代理接收（虚拟模型拦截之后、真正调上游之前）。 */
    RECEIVED,

    /** 已收到上游响应头（连接真正建立、开始等待首字响应）。 */
    CONNECTED,

    /** 上游异常，正在重试（携带当前重试次数，仅出现在首字到达之前）。 */
    RETRYING,

    /** 已收到上游 chunk（流式专属），携带当前累计 chunk 数。 */
    CHUNK,

    /** 响应正常完成，携带最终 chunk 总数（非流式为 0）。 */
    COMPLETED,

    /** 调用失败（上游错误、连接失败等；客户端主动断连不计入，见 {@link #CANCELED}）。 */
    FAILED,

    /** 客户端（下游 Copilot）主动断开连接，本次调用被取消（非错误，属于用户预期行为）。 */
    CANCELED,

    /**
     * 管理员在管理后台主动取消本次调用（仅在 CONNECTED 后等待首字期间可触发）。
     *
     * <p>与 {@link #CANCELED} 区分：CANCELED 是下游 Copilot 断开，ABORTED 是管理员
     * 通过取消端点主动中止「上游迟迟不吐首字」的调用，代理会向下游回传超时错误以触发其重试。
     */
    ABORTED
}
