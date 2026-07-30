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
    private final UsageEventPublisher usageEventPublisher;

    public ApiUsageCollector(ApiUsageRepository repository, UsageEventPublisher usageEventPublisher) {
        this.repository = repository;
        this.usageEventPublisher = usageEventPublisher;
    }

    /**
     * 记录一次 API 调用的 token 消耗。
     *
     * <p>写库成功后发出一次统计变更信号，供 SSE 推送流即时刷新概览页；
     * 写库失败不发信号，避免推送与实际持久化状态不一致。
     *
     * <h2>TODO 信号通道即将收窄</h2>
     * 这里发的是无载荷信号，订阅方收到后重查全量。它曾同时驱动统计卡、柱状图与折线图三条流；
     * 柱状图已改为由 {@code api_call_usage} 写入侧的增量帧驱动（那才是它真正的数据源），
     * 折线图待迁移。全部迁完后本信号只服务统计卡 ——
     * 而统计卡读的 {@code api_usage_daily} 恰好就是本方法写的表，届时信号与数据源终于一致。
     *
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     */
    public void record(int inputTokens, int outputTokens) {
        try {
            repository.insert(inputTokens, outputTokens);
            usageEventPublisher.publishUsageChanged();
        } catch (Exception e) {
            log.warn("API 调用统计写入失败 — inputTokens={}, outputTokens={}, 原因: {}", inputTokens, outputTokens,
                    e.getMessage());
        }
    }
}
