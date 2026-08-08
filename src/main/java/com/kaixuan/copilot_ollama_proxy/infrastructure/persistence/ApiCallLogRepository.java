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
     * 发出一次「调用记录已落库」信号，供管理后台的日志 SSE 流唤醒前端重新拉取。
     *
     * 由 provider 层在<strong>整条落库流程收尾时</strong>调用，而非在本类的 INSERT 内部 ——
     * 一次调用要写两张表（api_call_log 与可选的 api_call_usage），若在日志 INSERT 后
     * 立即发信号，消费者视角收到通知去查时用量行可能尚未写入，那一行的 token 会短暂显示为空。
     *
     * 信号是同步投递的（{@code Sinks.directBestEffort().tryEmitNext}
     * 在调用线程上直接推给订阅者），所以「发布点在哪一行」就是可观测的时序边界，
     * 不存在异步排队把窗口自然抹平的可能。
     *
     * 语义是「流程走完」而非「两张表都写了」：失败调用与上游未返回 usage 的调用
     * 本就不写用量行，若按「都写了」发信号，这些记录永远不会实时出现在前端 ——
     * 而错误行恰恰最需要立刻看到。
     */
    @Override
    public void publishCallRecorded() {
        logEventPublisher.publishLogCreated();
    }

    /**
     * 执行 INSERT 并返回新行的自增 id。
     *
     * 仅改变客户端读取生成键的方式（{@link KeyHolder}），不改变 SQL 与表结构；
     * SQLite 每次 INSERT 本就生成自增 id，此处只是把它读回用于软链接关联。
     * 沿用原有"只 warn 不抛"的容错策略：写入失败返回 null，绝不影响主调用链。
     *
     * 不在此发变更信号：见 {@link #publishCallRecorded()} 的说明。
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
            Number key = keyHolder.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception e) {
            log.warn("保存 API 调用日志失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 基于游标分页查询 API 调用日志，按时间倒序，每行附带 token 用量。
     *
     * 一个查询同时服务两个视角：调用者视角关心「这次请求成功了吗」，只读前 8 列；
     * 消费者视角关心「这次请求花了多少 token」，还要读后 4 列。曾经是两个方法两个
     * 端点，但它们主表、游标语义、排序、分页响应格式完全相同，后者的列是前者的严格
     * 超集，拆开只是让同一段逻辑维护两份。
     *
     * 为何总是带上用量而不做成开关：实测附加 4 列的代价在测量噪声内
     * （50 行/页时约 -0.2 ~ +0.6 ms，偶尔反而更快）。列表查询的成本几乎全在
     * 「回表跨过大载荷」—— 最新 50 行的请求/响应载荷可达 20 MB 量级，SQLite 读
     * 这些行的小列时必须跨过溢出页，故首屏约 38 ms，而载荷已瘦身的旧数据段同样
     * 50 行只要 0.5 ms。省不下来的部分本来就省不下，多带的部分几乎免费。
     *
     * 为何以 api_call_log 为主表：V8.5 起日志只瘦身载荷、永不删行，
     * 故它在语义上是 api_call_usage 的超集 —— 每条用量必有对应日志行，
     * 反之则不然（失败调用、上游未返回 usage 的调用都只有日志）。
     * 以超集为主表 + LEFT JOIN 附属表，单表游标即可覆盖全部记录，
     * 不需要 UNION 两表与复合游标。V8.5 之前产生的孤儿用量行不在本视图内，
     * 它们的数据价值已由概览页的聚合视图承担。
     *
     * JOIN 条件写成 {@code u.id = (SELECT ... ORDER BY u2.id DESC LIMIT 1)} 而非
     * 直接 {@code u.log_id = l.id}：{@code findByLogId} 的同款排序说明一个 log_id
     * 理论上可能对应多行用量，直接等值 JOIN 会让这类日志在列表里重复出现。
     * 子查询固定取最新一行，行数因此严格等于日志行数。
     *
     * 不带 usage_raw：那是零损失原始 JSON，单条可达数百字节，
     * 列表一次取 50 行不该为悬浮浮窗才用得上的字段付流量，详情端点已提供。
     *
     * token 列一律原样透传，NULL 不折成 0 —— null 表示上游未提供，
     * 0 表示上游报告的真实零值，这个区分对缓存命中率的解读至关重要。
     *
     * @param cursor 上一页最后一条记录的 ID，首屏传 null
     * @param pageSize 每页条数，钳制到 [1, 100]
     * @return 包含分页信息的 Map：items、nextCursor、hasMore、pageSize
     */
    public Map<String, Object> findLogs(Long cursor, int pageSize) {
        if (pageSize < 1) pageSize = 10;
        if (pageSize > 100) pageSize = 100;

        String sql = "SELECT l.id, l.provider_key, l.model_name, l.is_stream, l.status_code, "
                + "l.duration_ms, l.payload_trimmed, l.created_at, "
                + "u.prompt_tokens, u.completion_tokens, u.cached_tokens, u.ttfb_ms "
                + "FROM api_call_log l LEFT JOIN api_call_usage u ON u.id = ("
                + "SELECT u2.id FROM api_call_usage u2 WHERE u2.log_id = l.id "
                + "ORDER BY u2.id DESC LIMIT 1) ";

        List<Map<String, Object>> items;
        if (cursor == null) {
            items = jdbcTemplate.queryForList(
                    sql + "ORDER BY l.created_at DESC, l.id DESC LIMIT ?",
                    pageSize);
        } else {
            items = jdbcTemplate.queryForList(
                    sql + "WHERE l.id < ? ORDER BY l.created_at DESC, l.id DESC LIMIT ?",
                    cursor, pageSize);
        }

        return buildPage(items, pageSize);
    }

    /**
     * 把查询结果包装成游标分页响应。
     *
     * hasMore 用「本页恰好取满」判定：这会让最后一页刚好满时多一次空请求，
     * 但代价只是一次索引查找，比多查一行再丢弃更直观。
     */
    private Map<String, Object> buildPage(List<Map<String, Object>> items, int pageSize) {
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
