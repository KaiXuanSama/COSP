package com.kaixuan.copilot_ollama_proxy.application.logging;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallLogRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.LogEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 管理后台的调用日志查询用例。
 *
 * <p>提供两条链路：
 * <ul>
 *   <li>{@link #listLogs(Long, int)} / {@link #findLog(long)} —— HTTP 游标分页与详情查询；</li>
 *   <li>{@link #streamLogEvents()} —— SSE 纯信号推送，通知前端“有新日志”，
 *       前端据此自动拉取最新列表，替代手动点刷新。</li>
 * </ul>
 */
@Service
public class CallLogQueryService {

    private final ApiCallLogRepository apiCallLogRepository;
    private final ApiCallUsageRepository apiCallUsageRepository;
    private final LogEventPublisher logEventPublisher;

    public CallLogQueryService(ApiCallLogRepository apiCallLogRepository,
                               ApiCallUsageRepository apiCallUsageRepository,
                               LogEventPublisher logEventPublisher) {
        this.apiCallLogRepository = apiCallLogRepository;
        this.apiCallUsageRepository = apiCallUsageRepository;
        this.logEventPublisher = logEventPublisher;
    }

    /**
     * 调用记录分页列表，每行附带 token 用量。
     *
     * <p>同时服务两个视角：调用者视角只读元信息列，消费者视角还读
     * prompt_tokens / completion_tokens / cached_tokens / ttfb_ms。
     * 两者的主表、游标语义与分页格式完全一致，故共用一个端点，
     * 客户端无需为每行再发一次详情请求即可渲染消费者列表。
     */
    public Mono<Map<String, Object>> listLogs(Long cursor, int pageSize) {
        return Mono.fromCallable(() -> apiCallLogRepository.findLogs(cursor, pageSize))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 查询单条日志详情，并附带该次调用的 token 用量。
     *
     * <p>返回结构在 {@code api_call_log} 全列之上多一个 {@code usage} 字段：
     * <ul>
     *   <li>查到用量行 —— 为 {@code api_call_usage} 的完整行（含 {@code usage_raw} 原始 JSON）；</li>
     *   <li>{@code null} —— 未查询到用量。含三种情形：V8.3 之前产生的旧日志（迁移期）、
     *       失败调用（不写用量行）、上游未返回 usage 的调用。</li>
     * </ul>
     *
     * <p>用量表永不裁剪、日志表按条数裁剪，因此"日志 → 用量"是可查方向；
     * 反向的孤儿用量（日志已被裁剪）不在详情页场景内。
     *
     * <p>两次阻塞 JDBC 查询在同一个 {@code fromCallable} 内完成，整体桥接到
     * {@code boundedElastic}，符合 WebFlux 下的阻塞调用约定。
     */
    public Mono<Optional<Map<String, Object>>> findLog(long id) {
        return Mono.fromCallable(() -> {
                    Map<String, Object> logRow = apiCallLogRepository.findLogById(id);
                    if (logRow == null) {
                        return Optional.<Map<String, Object>>empty();
                    }
                    Map<String, Object> detail = new LinkedHashMap<>(logRow);
                    detail.put("usage", apiCallUsageRepository.findByLogId(id));
                    return Optional.of(detail);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 日志变更信号流（SSE）。
     *
     * <p>每当写库产生一条新日志，{@link LogEventPublisher} 发出信号，
     * 这里将其映射为一个递增时间戳向前端下发。信号<strong>不携带日志内容</strong>，
     * 前端收到后走带 Bearer Token 的 {@code /config/api/logs} 拉取实际数据，
     * 因此该端点即使 permitAll 也不会泄露信息。
     */
    public Flux<Long> streamLogEvents() {
        return logEventPublisher.changes().map(ignored -> System.currentTimeMillis());
    }
}