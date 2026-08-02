package com.kaixuan.copilot_ollama_proxy.application.logging;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;

/**
 * API 调用 token 用量写入服务 —— 领域接口。
 *
 * <p>与 {@link ApiCallLogService} 同属"provider 需要的写出能力"这一类领域接口，
 * 但写入的是独立于 {@code api_call_log} 的 {@code api_call_usage} 表。
 *
 * <p>拆表动机：{@code api_call_log} 含完整 chunks，会按条数裁剪（曾膨胀到 8G）；
 * token 用量若挂在日志上，历史统计会随裁剪蒸发。独立表让 token 记录长期存活，
 * 作为用量统计与概览可视化的稳定数据源。
 *
 * <p>这是依赖倒置（DIP）的体现：接口定义在 application 层，实现放在 infrastructure 层。
 */
public interface ApiCallUsageService {

    /**
     * 保存一条 token 用量记录。
     *
     * <p>调用方应仅在<strong>成功且有 usage</strong> 时调用（失败往返不写）。
     * {@code logId} 为软链接：拿到日志自增 id 则关联，拿不到（日志写入失败）传 null 写孤儿行。
     *
     * <p>token 参数遵循 null vs 0 语义：{@code null} = 上游未提供该字段，
     * {@code 0} = 上游报告了但值为零。落库时直接透传，绝不把 null 写成 0。
     *
     * @param logId       软链接到 {@code api_call_log.id}；写入失败或未启用日志时为 null
     * @param providerKey 供应商标识（冗余副本，日志删除后仍可解读）
     * @param modelName   模型名称（冗余副本）
     * @param stream      是否流式
     * @param usageRaw    上游 usage 对象原始 JSON（零损失兜底）；可为 null
     * @param tokens      解析出的核心 token 指标（prompt/completion/cached，均可空）
     * @param ttfbMs      首字响应时长（毫秒）；非流式或未测得时为 null
     */
    void save(Long logId, String providerKey, String modelName, boolean stream,
              String usageRaw, UsageTokens tokens, Integer ttfbMs);
}
