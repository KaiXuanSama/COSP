package com.kaixuan.copilot_ollama_proxy.application.logging;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallLogRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Optional;

/**
 * 管理后台的调用日志查询用例。
 */
@Service
public class CallLogQueryService {

    private final ApiCallLogRepository apiCallLogRepository;

    public CallLogQueryService(ApiCallLogRepository apiCallLogRepository) {
        this.apiCallLogRepository = apiCallLogRepository;
    }

    public Mono<Map<String, Object>> listLogs(Long cursor, int pageSize) {
        return Mono.fromCallable(() -> apiCallLogRepository.findLogs(cursor, pageSize))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Optional<Map<String, Object>>> findLog(long id) {
        return Mono.fromCallable(() -> Optional.ofNullable(apiCallLogRepository.findLogById(id)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}