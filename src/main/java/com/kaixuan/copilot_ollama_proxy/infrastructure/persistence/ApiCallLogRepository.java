package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.LogEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
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
    public Long saveNonStream(String providerKey, String modelName,
                              Map<String, String> requestHeaders, Map<String, Object> requestBody,
                              Map<String, String> responseHeaders, int statusCode, String responseBody, long durationMs) {
        String sql = "INSERT INTO api_call_log (provider_key, model_name, is_stream, status_code, request_headers, request_body, response_headers, response_body, duration_ms) "
                + "VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?)";
        Object[] args = {providerKey, modelName, statusCode,
                toJson(requestHeaders), toJson(requestBody),
                toJson(responseHeaders), responseBody, durationMs};
        return insertReturningId(sql, args);
    }

    /**
     * 保存一条流式调用日志。
     */
    @Override
    public Long saveStream(String providerKey, String modelName,
                           Map<String, String> requestHeaders, Map<String, Object> requestBody,
                           Map<String, String> responseHeaders, int statusCode, List<String> chunks, long durationMs) {
        String sql = "INSERT INTO api_call_log (provider_key, model_name, is_stream, status_code, request_headers, request_body, response_headers, chunks, duration_ms) "
                + "VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?)";
        Object[] args = {providerKey, modelName, statusCode,
                toJson(requestHeaders), toJson(requestBody),
                toJson(responseHeaders), toJson(chunks), durationMs};
        return insertReturningId(sql, args);
    }

    /**
     * 保存一条流式调用日志（含错误信息）。
     * 当流式响应过程中发生错误且重试耗尽时，将错误响应体保存到非流式响应列。
     */
    @Override
    public Long saveStreamWithError(String providerKey, String modelName,
                                    Map<String, String> requestHeaders, Map<String, Object> requestBody,
                                    Map<String, String> responseHeaders, int statusCode, List<String> chunks,
                                    Map<String, String> errorHeaders, int errorCode, String errorBody, long durationMs) {
        String sql = "INSERT INTO api_call_log (provider_key, model_name, is_stream, status_code, request_headers, request_body, response_headers, response_body, chunks, duration_ms) "
                + "VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?)";
        Object[] args = {providerKey, modelName, errorCode,
                toJson(requestHeaders), toJson(requestBody),
                toJson(errorHeaders), errorBody, toJson(chunks), durationMs};
        return insertReturningId(sql, args);
    }

    /**
     * 执行 INSERT 并返回新行的自增 id。
     *
     * 仅改变客户端读取生成键的方式（{@link KeyHolder}），不改变 SQL 与表结构；
     * SQLite 每次 INSERT 本就生成自增 id，此处只是把它读回用于软链接关联。
     * 沿用原有"只 warn 不抛"的容错策略：写入失败返回 null，绝不影响主调用链。
     *
     * @param sql  带占位符的 INSERT 语句
     * @param args 占位符实参，顺序与 SQL 一致
     * @return 新行自增 id；写入失败返回 null
     */
    private Long insertReturningId(String sql, Object[] args) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                return ps;
            }, keyHolder);
            logEventPublisher.publishLogCreated();
            Number key = keyHolder.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception e) {
            log.warn("保存 API 调用日志失败: {}", e.getMessage());
            return null;
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
                    "SELECT id, provider_key, model_name, is_stream, status_code, duration_ms, "
                            + "payload_trimmed, created_at "
                            + "FROM api_call_log ORDER BY created_at DESC, id DESC LIMIT ?",
                    pageSize);
        } else {
            items = jdbcTemplate.queryForList(
                    "SELECT id, provider_key, model_name, is_stream, status_code, duration_ms, "
                            + "payload_trimmed, created_at "
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
     * 把超出数量上限的旧日志「瘦身」：只清空大载荷列，保留整行。
     *
     * 清空 request_headers / request_body / response_headers / response_body / chunks
     * 五列并置 payload_trimmed = 1；provider_key、model_name、status_code、duration_ms、
     * created_at 等调用元信息一律保留。<strong>行永不删除。</strong>
     *
     * 1. 为何不再整行删除
     *    整行删除会让日志表在时间维度上成为 api_call_usage 的子集（用量表永不裁剪），
     *    于是老数据段全是 log_id 悬空的孤儿用量行。保留元信息后两表恢复
     *    「日志 ⊇ 用量」的稳定包含关系，消费者视角的明细视图因此能单表分页。
     *
     * 2. 为何没有行数上限兜底
     *    瘦身后每行仅剩定长元信息（约百字节量级），与 api_call_usage 的行宽同一量级，
     *    而后者本就按「永不裁剪」设计 —— 给一张设上限而另一张不设并不自洽。
     *    游标分页走 {@code WHERE id < ?} 加主键索引，代价与表总量无关，行数增长也不会拖慢翻页。
     *    更重要的是，任何按行数删除的兜底都会让被删行对应的用量记录重新变成孤儿，
     *    亲手打破本方法要建立的超集关系 —— 那是个平时看不见、触发时正好在排查现场的隐患。
     *    真需要控制总量时应按时间删除，且两张表同步进行。
     *
     * 3. payload_trimmed = 0 条件的必要性
     *    使操作幂等：每小时一次的定时任务只处理新落入瘦身区间的行，不会反复重写
     *    早已清空的历史行，避免无谓的写放大与 WAL 增长。也正因如此，返回值是
     *    「本次新瘦身的行数」而非「瘦身区间内的总行数」。
     *
     * 4. 性能特征（实测，20 万行 / ~1 年数据量）
     *    外层 UPDATE 是主键范围扫描（id &lt; 边界），但 {@code payload_trimmed = 0}
     *    只能作为逐行过滤器，故空转（无新行需瘦身）时仍摸过历史主键区间，耗时约 44 ms。
     *    真正有活干（~100 行）时约 162 ms。每小时一次，代价完全可接受。
     *    若将来规模增长到无法接受，可加偏索引
     *    {@code CREATE INDEX ON api_call_log(id) WHERE payload_trimmed = 0}，
     *    实测可将空转降到 7 ms；目前属于过早优化，暂不引入。
     *
     * @param maxRecords 保留完整载荷的最新记录数，必须大于 0
     * @return 本次新瘦身的行数
     */
    public int trimPayloadToLatest(int maxRecords) {
        if (maxRecords <= 0) {
            throw new IllegalArgumentException("maxRecords 必须大于 0");
        }
        return jdbcTemplate.update(
                "UPDATE api_call_log SET request_headers = NULL, request_body = NULL, "
                        + "response_headers = NULL, response_body = NULL, chunks = NULL, "
                        + "payload_trimmed = 1 "
                        + "WHERE payload_trimmed = 0 AND id < COALESCE(("
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
