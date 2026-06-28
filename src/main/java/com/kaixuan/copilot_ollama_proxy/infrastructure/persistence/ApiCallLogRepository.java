package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
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

    public ApiCallLogRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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
                    statusCode,
                    toJson(requestHeaders), toJson(requestBody),
                    toJson(responseHeaders), errorBody, toJson(chunks), durationMs);
        } catch (Exception e) {
            log.warn("保存 API 调用日志失败: {}", e.getMessage());
        }
    }

    /**
     * 分页查询 API 调用日志，按时间倒序。
     *
     * @param page     页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 包含分页信息的 Map：currentPage, totalPages, pageSize, totalItems, items
     */
    public Map<String, Object> findLogs(int page, int pageSize) {
        // 参数校验
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 10;
        if (pageSize > 100) pageSize = 100;

        // 查询总数
        Long totalItems = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM api_call_log", Long.class);
        if (totalItems == null) totalItems = 0L;

        // 计算分页
        int totalPages = (int) Math.ceil((double) totalItems / pageSize);
        if (totalPages < 1) totalPages = 1;
        if (page > totalPages) page = totalPages;

        int offset = (page - 1) * pageSize;

        // 查询当前页数据（不含大字段 request_body, response_body, chunks）
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, provider_key, model_name, is_stream, status_code, duration_ms, created_at "
                        + "FROM api_call_log ORDER BY created_at DESC LIMIT ? OFFSET ?",
                pageSize, offset);

        // 构建响应
        Map<String, Object> result = new HashMap<>();
        result.put("currentPage", page);
        result.put("totalPages", totalPages);
        result.put("pageSize", pageSize);
        result.put("totalItems", totalItems);
        result.put("items", items);
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
