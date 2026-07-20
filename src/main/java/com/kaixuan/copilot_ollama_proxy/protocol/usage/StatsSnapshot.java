package com.kaixuan.copilot_ollama_proxy.protocol.usage;

/**
 * 概览页统计快照 DTO。
 *
 * <p>同时用于两条链路：
 * <ul>
 *   <li>HTTP {@code GET /config/api/stats} 首屏拉取；</li>
 *   <li>SSE {@code GET /config/api/stats/stream} 增量推送 —— 每次实际 Copilot 调用完成后，
 *       后端写库成功即推送一份最新快照，前端据此刷新统计卡与热力图“今日”单格。</li>
 * </ul>
 *
 * <p>字段与前端 {@code StatsData} 一一对应，避免前端再做字段映射。
 *
 * @param totalApiCalls    自服务启动以来累计的 API 调用总次数
 * @param todayApiCalls    今日（本地时区 00:00~23:59）API 调用次数
 * @param todayInputTokens 今日输入 token 数
 * @param todayOutputTokens 今日输出 token 数
 */
public record StatsSnapshot(
        long totalApiCalls,
        long todayApiCalls,
        long todayInputTokens,
        long todayOutputTokens) {
}
