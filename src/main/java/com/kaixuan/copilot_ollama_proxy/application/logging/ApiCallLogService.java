package com.kaixuan.copilot_ollama_proxy.application.logging;

import java.util.List;
import java.util.Map;

/**
 * API 调用日志写入服务 —— 领域接口。
 *
 * provider 层通过该接口记录上游调用日志，而不直接依赖具体的持久化实现，
 * 从而与底层存储解耦。
 *
 * 仅暴露 provider 需要的写入能力；查询能力（分页、详情）属于管理后台关注点，
 * 保留在 infrastructure 的 Repository 上，不纳入本接口。
 *
 * 这是依赖倒置（DIP）的体现：接口定义在 application 层，实现放在 infrastructure 层。
 */
public interface ApiCallLogService {

    /**
     * 保存一条非流式调用日志。
     *
     * @param providerKey     供应商标识
     * @param modelName       模型名称
     * @param requestHeaders  请求头（脱敏后）
     * @param requestBody     请求体
     * @param responseHeaders 响应头
     * @param statusCode      HTTP 状态码
     * @param responseBody    响应体
     * @param durationMs      调用耗时（毫秒）
     * @return 新插入日志行的自增 id；写入失败时返回 null（不抛异常）
     */
    Long saveNonStream(String providerKey, String modelName,
                       Map<String, String> requestHeaders, Map<String, Object> requestBody,
                       Map<String, String> responseHeaders, int statusCode, String responseBody, long durationMs);

    /**
     * 保存一条带线路协议信息的非流式调用日志。
     */
    default Long saveNonStream(String providerKey, String modelName,
                               String downstreamProtocol, String upstreamProtocol,
                               Map<String, String> requestHeaders, Map<String, Object> requestBody,
                               Map<String, String> responseHeaders, int statusCode, String responseBody, long durationMs) {
        return saveNonStream(providerKey, modelName, requestHeaders, requestBody, responseHeaders,
                statusCode, responseBody, durationMs);
    }

    /**
     * 保存一条流式调用日志。
     *
     * @param providerKey     供应商标识
     * @param modelName       模型名称
     * @param requestHeaders  请求头（脱敏后）
     * @param requestBody     请求体
     * @param responseHeaders 响应头
     * @param statusCode      HTTP 状态码
     * @param chunks          流式响应分片列表
     * @param durationMs      调用耗时（毫秒）
     * @return 新插入日志行的自增 id；写入失败时返回 null（不抛异常）
     */
    Long saveStream(String providerKey, String modelName,
                    Map<String, String> requestHeaders, Map<String, Object> requestBody,
                    Map<String, String> responseHeaders, int statusCode, List<String> chunks, long durationMs);

    /**
     * 保存一条带线路协议信息的流式调用日志。
     */
    default Long saveStream(String providerKey, String modelName,
                            String downstreamProtocol, String upstreamProtocol,
                            Map<String, String> requestHeaders, Map<String, Object> requestBody,
                            Map<String, String> responseHeaders, int statusCode, List<String> chunks, long durationMs) {
        return saveStream(providerKey, modelName, requestHeaders, requestBody, responseHeaders,
                statusCode, chunks, durationMs);
    }

    /**
     * 保存一条流式调用日志（含错误信息）。
     * 当流式响应过程中发生错误且重试耗尽时，将错误响应体保存到非流式响应列。
     *
     * @param providerKey     供应商标识
     * @param modelName       模型名称
     * @param requestHeaders  请求头（脱敏后）
     * @param requestBody     请求体
     * @param responseHeaders 响应头
     * @param statusCode      HTTP 状态码
     * @param chunks          流式响应分片列表
     * @param errorHeaders    错误响应头
     * @param errorCode       错误状态码
     * @param errorBody       错误响应体
     * @param durationMs      调用耗时（毫秒）
     * @return 新插入日志行的自增 id；写入失败时返回 null（不抛异常）
     */
    Long saveStreamWithError(String providerKey, String modelName,
                             Map<String, String> requestHeaders, Map<String, Object> requestBody,
                             Map<String, String> responseHeaders, int statusCode, List<String> chunks,
                             Map<String, String> errorHeaders, int errorCode, String errorBody, long durationMs);

    /**
     * 保存一条带线路协议信息的流式错误调用日志。
     */
    default Long saveStreamWithError(String providerKey, String modelName,
                                     String downstreamProtocol, String upstreamProtocol,
                                     Map<String, String> requestHeaders, Map<String, Object> requestBody,
                                     Map<String, String> responseHeaders, int statusCode, List<String> chunks,
                                     Map<String, String> errorHeaders, int errorCode, String errorBody, long durationMs) {
        return saveStreamWithError(providerKey, modelName, requestHeaders, requestBody, responseHeaders,
                statusCode, chunks, errorHeaders, errorCode, errorBody, durationMs);
    }

    /**
     * 发出一次「本次调用的记录已落库」信号，供管理后台的日志 SSE 流唤醒前端重新拉取。
     *
     * 调用方须在<strong>整条落库流程收尾时</strong>调用，即日志写入与可选的用量写入
     * 都已经过（不论用量是否实际写入），语义等同于 try-finally 的 finally ——
     * 「流程走完」而非「两张表都写了」。
     *
     * 为何不在 save* 内部自动发：一次调用要写两张表，日志表先写。若在日志 INSERT 后
     * 立即发信号，消费者视角收到通知去查时用量行可能尚未写入，那一行的 token 会短暂为空。
     * 把发布点交给编排方，才能表达「这次调用的记录整体就绪」这一时刻。
     *
     * 为何不是「两张表都写了」：失败调用与上游未返回 usage 的调用本就不写用量行，
     * 若按 {@code &&} 判定，这些记录永远不会实时出现 —— 而错误行最需要立刻看到。
     *
     * 与 save* 一致的容错策略：实现不得因推送失败抛异常影响主调用链。
     */
    void publishCallRecorded();
}
