package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协议信息的补写（{@code recordProtocols}）。
 *
 * <p>钉住「上游协议晚一步才知道」这条链路的行为：控制器先发不含上游协议的事件，
 * 应用层调度完成后再补一次。三个不显然的规则：
 * <ul>
 *   <li>改写的是<strong>当前最新事件</strong>且阶段不变 —— 前端不能看到一条已产生 chunk
 *       的调用倒回「下游发出请求」；</li>
 *   <li>时间戳<strong>不变</strong> —— 前端拿它当计时起点，重发时刷新会让已跑了
 *       一会儿的调用显示成刚刚开始；</li>
 *   <li>已终态（不在 inFlight）的调用<strong>不补也不重发</strong> —— 给收尾的 Toast
 *       补协议无意义，重发还会让它重新活跃。</li>
 * </ul>
 *
 * <p>断言用「先订阅到列表、再发事件」的手动订阅而非 {@code StepVerifier}：
 * 本项目没引 {@code reactor-test}。这样写可行的前提是发布器用
 * {@code multicast().directBestEffort()}，其发射在调用线程上<strong>同步</strong>分发，
 * 因此事件发出后列表立即就绪，不需要等待或轮询。
 */
class CallLifecyclePublisherProtocolTests {

    private final CallLifecyclePublisher publisher = new CallLifecyclePublisher();

    private static CallLifecycleEvent received(String requestId, String model) {
        return CallLifecycleEvent.of(requestId, CallPhase.RECEIVED, model, true);
    }

    /** 订阅事件流并收集到列表；返回的列表随发布实时增长。 */
    private List<CallLifecycleEvent> subscribe() {
        List<CallLifecycleEvent> collected = new ArrayList<>();
        publisher.events().subscribe(collected::add);
        return collected;
    }

    @Test
    void resendsLatestEventKeepingPhaseAndTimestamp() {
        List<CallLifecycleEvent> collected = subscribe();
        CallLifecycleEvent first = received("r1", "m");

        publisher.publish(first);
        publisher.publish(CallLifecycleEvent.of("r1", CallPhase.CHUNK, "m", true, 3));
        publisher.recordProtocols("r1", "OPENAI", "ANTHROPIC");

        assertThat(collected).hasSize(3);
        assertThat(collected.get(0).phase()).isEqualTo(CallPhase.RECEIVED);
        assertThat(collected.get(1).phase()).isEqualTo(CallPhase.CHUNK);
        // 补写后重发的这条：阶段与 chunk 计数必须是调用当前的真实状态，不能倒回 RECEIVED。
        CallLifecycleEvent resent = collected.get(2);
        assertThat(resent.phase()).isEqualTo(CallPhase.CHUNK);
        assertThat(resent.chunkCount()).isEqualTo(3);
        assertThat(resent.model()).isEqualTo("m");
        assertThat(resent.downstreamProtocol()).isEqualTo("OPENAI");
        assertThat(resent.upstreamProtocol()).isEqualTo("ANTHROPIC");
    }

    @Test
    void keepsOriginalTimestampWhenSupplementing() {
        List<CallLifecycleEvent> collected = subscribe();
        CallLifecycleEvent first = received("r2", "m");

        publisher.publish(first);
        publisher.recordProtocols("r2", "OPENAI", "ANTHROPIC");

        assertThat(collected).hasSize(2);
        // 前端拿时间戳当「流存在时间」的起点，重发刷新它会让已跑的调用显示成刚刚开始。
        assertThat(collected.get(1).timestamp()).isEqualTo(first.timestamp());
    }

    @Test
    void ignoresSupplementForUnknownRequestId() {
        List<CallLifecycleEvent> collected = subscribe();

        publisher.recordProtocols("ghost", "OPENAI", "ANTHROPIC");

        assertThat(collected).isEmpty();
    }

    @Test
    void ignoresSupplementForTerminalCall() {
        List<CallLifecycleEvent> collected = subscribe();

        publisher.publish(received("r3", "m"));
        publisher.publish(CallLifecycleEvent.of("r3", CallPhase.COMPLETED, "m", true, 5));
        publisher.recordProtocols("r3", "OPENAI", "ANTHROPIC");

        // 只有原本那两条；终态补协议不重发，否则收尾中的 Toast 会重新活跃。
        assertThat(collected).hasSize(2);
        assertThat(collected.get(1).phase()).isEqualTo(CallPhase.COMPLETED);
    }

    @Test
    void snapshotCarriesSupplementedProtocols() {
        publisher.publish(received("r4", "m"));
        publisher.recordProtocols("r4", "ANTHROPIC", "OPENAI");

        // 快照供晚订阅的前端补历史，必须带上已补写的协议，否则刷新页面后 Tag 消失。
        assertThat(publisher.snapshot())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.downstreamProtocol()).isEqualTo("ANTHROPIC");
                    assertThat(event.upstreamProtocol()).isEqualTo("OPENAI");
                });
    }

    @Test
    void nullProtocolsLeaveExistingValuesUntouched() {
        publisher.publish(received("r5", "m"));
        publisher.recordProtocols("r5", "OPENAI", "ANTHROPIC");
        // null 表示「不改写该字段」，用于只确定一侧的场景。
        publisher.recordProtocols("r5", null, null);

        assertThat(publisher.snapshot())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.downstreamProtocol()).isEqualTo("OPENAI");
                    assertThat(event.upstreamProtocol()).isEqualTo("ANTHROPIC");
                });
    }
}
