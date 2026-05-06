package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * API 调用统计采集器 — 门面组件，封装写入逻辑。
 * <p>
 * 调用方只需传入 token 数，无需关心数据库细节。
 * 所有异常均被捕获并记录日志，不影响主业务流程。
 */
@Component
public class ApiUsageCollector {

    private static final Logger log = LoggerFactory.getLogger(ApiUsageCollector.class);

    private final ApiUsageRepository repository;

    public ApiUsageCollector(ApiUsageRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录一次 API 调用的 token 消耗。
     *
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     */
    public void record(int inputTokens, int outputTokens) {
        try {
            repository.insert(inputTokens, outputTokens);
        } catch (Exception e) {
            log.warn("API 调用统计写入失败 — inputTokens={}, outputTokens={}, 原因: {}", inputTokens, outputTokens,
                    e.getMessage());
        }
    }
}
