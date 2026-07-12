package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * API 调用日志保留任务。
 *
 * 应用启动时和之后每小时按 ID 裁剪旧日志，只保留最新的指定数量记录。
 * 日志中的完整请求、响应和 SSE chunks 不做截断。
 */
@Component
public class ApiCallLogRetentionTask implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiCallLogRetentionTask.class);

    private final ApiCallLogRepository apiCallLogRepository;
    private final int maxRecords;

    /**
     * 创建 API 调用日志保留任务。
     *
     * @param apiCallLogRepository API 调用日志仓储
     * @param maxRecords 最多保留的最新日志条数
     */
    public ApiCallLogRetentionTask(ApiCallLogRepository apiCallLogRepository,
                                   @Value("${call-log.max-records:100}") int maxRecords) {
        this.apiCallLogRepository = apiCallLogRepository;
        this.maxRecords = Math.max(1, maxRecords);
    }

    /**
     * 应用启动后立即清理一次历史日志。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        trimOldLogs();
    }

    /**
     * 每小时整点清理超出数量上限的旧日志。
     */
    @Scheduled(cron = "0 0 * * * *")
    public void trimOldLogs() {
        try {
            int deleted = apiCallLogRepository.trimToLatest(maxRecords);
            if (deleted > 0) {
                log.info("[ApiCallLog] 已删除 {} 条旧日志，保留最新 {} 条", deleted, maxRecords);
            }
        } catch (Exception e) {
            log.warn("[ApiCallLog] 清理旧日志失败: {}", e.getMessage());
        }
    }
}