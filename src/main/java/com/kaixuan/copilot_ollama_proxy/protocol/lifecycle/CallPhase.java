package com.kaixuan.copilot_ollama_proxy.protocol.lifecycle;

/**
 * 单次调用的生命周期阶段。
 *
 * <p>用于前端 Toast 观察每次 Copilot 调用流经代理时的实时状态，
 * 判断"是否卡住 / 上游是否有响应"。阶段流转：
 *
 * <pre>
 *   流式：  RECEIVED -> CONNECTED -> CHUNK(多次) -> COMPLETED
 *   非流式：RECEIVED -> CONNECTED -> COMPLETED
 *   任意阶段出错：-> FAILED
 *   客户端（下游 Copilot）主动断连：-> CANCELED
 * </pre>
 *
 * <p>后端只下发阶段枚举与必要数据（chunk 计数等），具体展示文案由前端根据阶段渲染，
 * 保持"能力/展示分离"——后端不掺杂 UI 文案。
 */
public enum CallPhase {

    /** 下游请求已被代理接收（虚拟模型拦截之后、真正调上游之前）。 */
    RECEIVED,

    /** 已向上游发起调用，正在等待首字响应。 */
    CONNECTED,

    /** 已收到上游 chunk（流式专属），携带当前累计 chunk 数。 */
    CHUNK,

    /** 响应正常完成，携带最终 chunk 总数（非流式为 0）。 */
    COMPLETED,

    /** 调用失败（上游错误、连接失败等；客户端主动断连不计入，见 {@link #CANCELED}）。 */
    FAILED,

    /** 客户端（下游 Copilot）主动断开连接，本次调用被取消（非错误，属于用户预期行为）。 */
    CANCELED
}
