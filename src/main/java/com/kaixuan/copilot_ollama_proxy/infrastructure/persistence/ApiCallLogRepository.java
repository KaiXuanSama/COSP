package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * API 调用日志数据访问层 — 基于 JdbcTemplate 操作 SQLite api_call_log 表。
 *
 * 记录每次调用上游供应商 API 的完整请求/响应信息，用于排查和审计。
 */
@Repository
public class ApiCallLogRepository {

    private static final Logger log = LoggerFactory.getLogger(ApiCallLogRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ApiCallLogRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存一条非流式调用日志。
     */
    public void saveNonStream(String providerKey, String modelName,
                              Map<String, String> requestHeaders, Map<String, Object> requestBody,
                              Map<String, String> responseHeaders, int statusCode, String responseBody, long durationMs) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO api_call_log (provider_key, model_name, is_stream, status_code, request_headers, request_body, response_headers, response_body, duration_ms) "
                            + "VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?)",
                    providerKey, modelName,
                    statusCode,
                    toJson(requestHeaders), toJson(requestBody),
                    toJson(responseHeaders), responseBody, durationMs);
        } catch (Exception e) {
            log.warn("保存 API 调用日志失败: {}", e.getMessage());
        }
    }

    /**
     * 保存一条流式调用日志。
     */
    public void saveStream(String providerKey, String modelName,
                           Map<String, String> requestHeaders, Map<String, Object> requestBody,
                           Map<String, String> responseHeaders, int statusCode, List<String> chunks, long durationMs) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO api_call_log (provider_key, model_name, is_stream, status_code, request_headers, request_body, response_headers, chunks, duration_ms) "
                            + "VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?)",
                    providerKey, modelName,
                    statusCode,
                    toJson(requestHeaders), toJson(requestBody),
                    toJson(responseHeaders), toJson(chunks), durationMs);
        } catch (Exception e) {
            log.warn("保存 API 调用日志失败: {}", e.getMessage());
        }
    }

    /**
     * 保存一条流式调用日志（含错误信息）。
     * 当流式响应过程中发生错误且重试耗尽时，将错误响应体保存到非流式响应列。
     */
    public void saveStreamWithError(String providerKey, String modelName,
                                    Map<String, String> requestHeaders, Map<String, Object> requestBody,
                                    Map<String, String> responseHeaders, int statusCode, List<String> chunks,
                                    Map<String, String> errorHeaders, int errorCode, String errorBody, long durationMs) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO api_call_log (provider_key, model_name, is_stream, status_code, request_headers, request_body, response_headers, response_body, chunks, duration_ms) "
                            + "VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?)",
                    providerKey, modelName,
                    statusCode,
                    toJson(requestHeaders), toJson(requestBody),
                    toJson(responseHeaders), errorBody, toJson(chunks), durationMs);
        } catch (Exception e) {
            log.warn("保存 API 调用日志失败: {}", e.getMessage());
        }
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "\"[序列化失败]\"";
        }
    }
}
