package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * API 调用日志保留任务 —— 载荷瘦身，行永不删除。
 *
 * <p>应用启动时与之后每小时整点各执行一次：超出 {@code call-log.max-records} 的旧行
 * 只清空请求头/体、响应头/体与 chunks 五列，整行保留。调用元信息
 * （供应商、模型、状态码、耗时、时刻）始终可查。
 *
 * <h2>为何不再整行删除</h2>
 * 整行删除会让本表在时间维度上成为 {@code api_call_usage} 的子集（用量表永不裁剪），
 * 于是老数据段全是 {@code log_id} 悬空的孤儿用量行。保留元信息后两表恢复
 * 「日志 ⊇ 用量」的稳定包含关系，消费者视角的明细视图因此能单表分页，
 * 无需跨表 UNION 与复合游标。
 *
 * <h2>为何没有行数上限兜底</h2>
 * 真正占空间的是完整请求体（Copilot 携带全量对话历史与 tools 定义）与 chunks
 * （逐帧 SSE 全文），二者相比定长元信息高出数个数量级，清掉它们即已消除膨胀源。
 * 瘦身后的行宽与 {@code api_call_usage} 同一量级，而后者本就按「永不裁剪」设计。
 * 任何按行数删除的兜底都会让被删行对应的用量记录重新变成孤儿，
 * 亲手打破本任务要建立的超集关系。
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
     * @param maxRecords 保留完整载荷的最新日志条数
     */
    public ApiCallLogRetentionTask(ApiCallLogRepository apiCallLogRepository,
                                   @Value("${call-log.max-records:1000}") int maxRecords) {
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
     * 每小时整点把超出保留条数的旧日志瘦身。
     */
    @Scheduled(cron = "0 0 * * * *")
    public void trimOldLogs() {
        try {
            int trimmed = apiCallLogRepository.trimPayloadToLatest(maxRecords);
            if (trimmed > 0) {
                log.info("[ApiCallLog] 已瘦身 {} 条旧日志（清空请求/响应载荷），保留最新 {} 条完整记录",
                        trimmed, maxRecords);
            }
        } catch (Exception e) {
            log.warn("[ApiCallLog] 瘦身旧日志失败: {}", e.getMessage());
        }
    }
}