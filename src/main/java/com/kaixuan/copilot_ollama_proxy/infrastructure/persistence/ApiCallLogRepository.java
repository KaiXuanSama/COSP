package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.LogEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API 调用日志数据访问层 — 基于 JdbcTemplate 操作 SQLite api_call_log 表。
 *
 * 实现 application 层的 {@link ApiCallLogService} 写入接口，provider 层通过接口依赖本类；
 * 查询方法（findLogs/findLogById）供管理后台使用，不属于领域接口。
 *
 * 记录每次调用上游供应商 API 的完整请求/响应信息，用于排查和审计。
 */
@Repository
public class ApiCallLogRepository implements ApiCallLogService {

    private static final Logger log = LoggerFactory.getLogger(ApiCallLogRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final LogEventPublisher logEventPublisher;

    public ApiCallLogRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                LogEventPublisher logEventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.logEventPublisher = logEventPublisher;
    }

    /**
     * 保存一条非流式调用日志。
     */
    @Override
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
            logEventPublisher.publishLogCreated();
        } catch (Exception e) {
            log.warn("保存 API 调用日志失败: {}", e.getMessage());
        }
    }

    /**
     * 保存一条流式调用日志。
     */
    @Override
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
            logEventPublisher.publishLogCreated();
        } catch (Exception e) {
            log.warn("保存 API 调用日志失败: {}", e.getMessage());
        }
    }

    /**
     * 保存一条流式调用日志（含错误信息）。
     * 当流式响应过程中发生错误且重试耗尽时，将错误响应体保存到非流式响应列。
     */
    @Override
    public void saveStreamWithError(String providerKey, String modelName,
                                    Map<String, String> requestHeaders, Map<String, Object> requestBody,
                                    Map<String, String> responseHeaders, int statusCode, List<String> chunks,
                                    Map<String, String> errorHeaders, int errorCode, String errorBody, long durationMs) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO api_call_log (provider_key, model_name, is_stream, status_code, request_headers, request_body, response_headers, response_body, chunks, duration_ms) "
                            + "VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?)",
                    providerKey, modelName,
                    errorCode,
                    toJson(requestHeaders), toJson(requestBody),
                    toJson(errorHeaders), errorBody, toJson(chunks), durationMs);
            logEventPublisher.publishLogCreated();
        } catch (Exception e) {
            log.warn("保存 API 调用日志失败: {}", e.getMessage());
        }
    }

    /**
     * 基于游标分页查询 API 调用日志，按时间倒序。
     *
     * @param cursor 上一页最后一条记录的 ID，首屏传 null
     * @param pageSize 每页条数
     * @return 包含分页信息的 Map：items、nextCursor、hasMore、pageSize
     */
    public Map<String, Object> findLogs(Long cursor, int pageSize) {
        if (pageSize < 1) pageSize = 10;
        if (pageSize > 100) pageSize = 100;

        List<Map<String, Object>> items;
        if (cursor == null) {
            items = jdbcTemplate.queryForList(
                    "SELECT id, provider_key, model_name, is_stream, status_code, duration_ms, created_at "
                            + "FROM api_call_log ORDER BY created_at DESC, id DESC LIMIT ?",
                    pageSize);
        } else {
            items = jdbcTemplate.queryForList(
                    "SELECT id, provider_key, model_name, is_stream, status_code, duration_ms, created_at "
                            + "FROM api_call_log WHERE id < ? ORDER BY created_at DESC, id DESC LIMIT ?",
                    cursor, pageSize);
        }

        Long nextCursor = null;
        boolean hasMore = false;
        if (!items.isEmpty()) {
            hasMore = items.size() == pageSize;
            if (hasMore) {
                nextCursor = ((Number) items.get(items.size() - 1).get("id")).longValue();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("nextCursor", nextCursor);
        result.put("hasMore", hasMore);
        result.put("pageSize", pageSize);
        return result;
    }

    /**
     * 根据 ID 查询单条日志详情（含完整请求/响应）。
     *
     * @param id 日志 ID
     * @return 日志详情，不存在时返回 null
     */
    public Map<String, Object> findLogById(long id) {
        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                "SELECT * FROM api_call_log WHERE id = ?", id);
        return logs.isEmpty() ? null : logs.get(0);
    }

    /**
     * 删除超出数量上限的旧日志，仅保留 ID 最大的最新记录。
     *
     * 完整请求、响应和 SSE chunks 不做任何截断；该方法只删除整条旧记录。
     *
     * @param maxRecords 最多保留的记录数，必须大于 0
     * @return 删除的旧日志数量
     */
    public int trimToLatest(int maxRecords) {
        if (maxRecords <= 0) {
            throw new IllegalArgumentException("maxRecords 必须大于 0");
        }
        return jdbcTemplate.update(
                "DELETE FROM api_call_log WHERE id < COALESCE(("
                        + "SELECT MIN(id) FROM (SELECT id FROM api_call_log ORDER BY id DESC LIMIT ?)"
                        + "), 0)",
                maxRecords);
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
