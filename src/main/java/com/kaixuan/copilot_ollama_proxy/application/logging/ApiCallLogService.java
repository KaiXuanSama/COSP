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
     */
    void saveNonStream(String providerKey, String modelName,
                       Map<String, String> requestHeaders, Map<String, Object> requestBody,
                       Map<String, String> responseHeaders, int statusCode, String responseBody, long durationMs);

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
     */
    void saveStream(String providerKey, String modelName,
                    Map<String, String> requestHeaders, Map<String, Object> requestBody,
                    Map<String, String> responseHeaders, int statusCode, List<String> chunks, long durationMs);

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
     */
    void saveStreamWithError(String providerKey, String modelName,
                             Map<String, String> requestHeaders, Map<String, Object> requestBody,
                             Map<String, String> responseHeaders, int statusCode, List<String> chunks,
                             Map<String, String> errorHeaders, int errorCode, String errorBody, long durationMs);
}
