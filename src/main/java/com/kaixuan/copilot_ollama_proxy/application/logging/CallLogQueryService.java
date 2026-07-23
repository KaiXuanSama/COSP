package com.kaixuan.copilot_ollama_proxy.application.logging;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallLogRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.LogEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private final LogEventPublisher logEventPublisher;

    public CallLogQueryService(ApiCallLogRepository apiCallLogRepository, LogEventPublisher logEventPublisher) {
        this.apiCallLogRepository = apiCallLogRepository;
        this.logEventPublisher = logEventPublisher;
    }

    public Mono<Map<String, Object>> listLogs(Long cursor, int pageSize) {
        return Mono.fromCallable(() -> apiCallLogRepository.findLogs(cursor, pageSize))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Optional<Map<String, Object>>> findLog(long id) {
        return Mono.fromCallable(() -> Optional.ofNullable(apiCallLogRepository.findLogById(id)))
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