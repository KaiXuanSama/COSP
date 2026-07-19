package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.logging.CallLogQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** 管理后台调用日志 API。 */
@RestController
public class CallLogController {

    private final CallLogQueryService callLogQueryService;

    public CallLogController(CallLogQueryService callLogQueryService) {
        this.callLogQueryService = callLogQueryService;
    }

    @GetMapping("/config/api/logs")
    public Mono<Map<String, Object>> listLogs(@RequestParam(value = "cursor", required = false) Long cursor,
                                               @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return callLogQueryService.listLogs(cursor, pageSize);
    }

    @GetMapping("/config/api/logs/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> getLogDetail(@PathVariable long id) {
        return callLogQueryService.findLog(id)
                .map(log -> log.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build()));
    }
}