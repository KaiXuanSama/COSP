package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import java.util.List;

/**
 * 一轮上游事件的批量翻译结果，附带逐事件的产帧数。
 *
 * <h2>为何需要 frameCounts</h2>
 * 日志页要把两栏并排对照，而<strong>帧数不对等是常态</strong>（实测 26 个上游事件
 * → 20 个下游 chunk）。只给两个独立数组，前端只能各自从 #1 开始编号，
 * 视觉上无法对齐 —— 左侧 #2 是零帧的 {@code content_block_start}，
 * 右侧 #2 已经是第二个内容帧了。
 *
 * <p>{@code frameCounts[i]} 是第 i 个上游事件产出的下游帧数（可能为 0）。
 * 前端据此渲染对齐行：零帧事件右侧留占位，头部因此自然对齐，
 * 也能一眼看出哪些事件被吸收了（{@code signature_delta} / {@code ping} /
 * {@code content_block_start} 等）。
 *
 * <h2>收尾帧不在 frameCounts 里</h2>
 * finish chunk、usage chunk 与 {@code [DONE]} 由「流结束」触发，
 * 不属于任何上游事件。它们的数量等于
 * {@code translated.size() - sum(frameCounts)}，前端无需额外字段就能算出来，
 * 在对齐视图里单独成段、左侧留空。
 *
 * @param translated  下游实际收到的完整 chunk 序列
 * @param frameCounts 与上游事件一一对应的产帧数，长度等于上游事件数
 */
public record TranslatedChunkLog(List<String> translated, List<Integer> frameCounts) {
}
