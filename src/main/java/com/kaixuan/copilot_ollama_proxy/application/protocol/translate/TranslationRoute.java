package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;

/**
 * 翻译查表的<strong>键</strong> —— 一对「下游协议 → 上游协议」。
 *
 * <h2>为何是一个具名类型而不是复合键</h2>
 * 去程与回程两张查表都以「(下游, 上游)」为键。用 {@code record} 而非
 * {@code Map<WireProtocol, Map<WireProtocol, T>>} 或字符串拼接：record 自带
 * {@code equals}/{@code hashCode}，键的语义写在类型里而不是散在拼接逻辑里，
 * 且两张表共用同一个键类型，「哪个翻译器配哪个方向」这件事只有一种表达。
 *
 * <h2>去程与回程为何用同向的键</h2>
 * 一条链的两半（{@code ChatToMessagesRequestTranslator} 去程、
 * {@code MessagesToChatResponseTranslator} 回程）都声明自己属于
 * 「下游 CHAT、上游 MESSAGES」这条链 —— 两者的 {@code downstreamProtocol()} 都返回
 * {@code CHAT}、{@code upstreamProtocol()} 都返回 {@code MESSAGES}。因此同一个
 * {@code (CHAT, MESSAGES)} 键既能查到去程也能查到回程，编排层不必为两侧准备两套键。
 *
 * @param downstream 下游使用的协议（由它打的端点决定）
 * @param upstream   实际对上游使用的协议（由供应商配置决定）
 */
public record TranslationRoute(WireProtocol downstream, WireProtocol upstream) {
}
