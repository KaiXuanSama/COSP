package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CallLifecyclePublisher} 进行中调用快照的行为测试。
 *
 * <p>快照用于消除「晚打开前端错过历史事件」的盲区：SSE 连接建立时先补发一份
 * 进行中调用的最新状态。核心不变量——非终态事件写入/更新快照，终态事件移除。
 */
class CallLifecyclePublisherSnapshotTests {

    @Test
    void nonTerminalEventEntersSnapshotAndKeepsLatestPhasePerRequest() {
        CallLifecyclePublisher publisher = new CallLifecyclePublisher();

        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.RECEIVED, "model-a", true));
        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.CONNECTED, "model-a", true));

        Collection<CallLifecycleEvent> snapshot = publisher.snapshot();
        assertThat(snapshot).hasSize(1);
        // 同一 requestId 只保留最新阶段（CONNECTED 覆盖 RECEIVED）。
        assertThat(snapshot.iterator().next().phase()).isEqualTo(CallPhase.CONNECTED);
    }

    @Test
    void multipleInFlightCallsCoexistInSnapshot() {
        CallLifecyclePublisher publisher = new CallLifecyclePublisher();

        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.CONNECTED, "model-a", true));
        publisher.publish(CallLifecycleEvent.of("req-2", CallPhase.RECEIVED, "model-b", false));

        assertThat(publisher.snapshot())
                .extracting(CallLifecycleEvent::requestId)
                .containsExactlyInAnyOrder("req-1", "req-2");
    }

    @Test
    void terminalEventRemovesCallFromSnapshot() {
        CallLifecyclePublisher publisher = new CallLifecyclePublisher();

        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.CONNECTED, "model-a", true));
        assertThat(publisher.snapshot()).hasSize(1);

        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.COMPLETED, "model-a", true, 5));
        assertThat(publisher.snapshot()).isEmpty();
    }

    @Test
    void allTerminalPhasesRemoveCallFromSnapshot() {
        for (CallPhase terminal : new CallPhase[] {
                CallPhase.COMPLETED, CallPhase.FAILED, CallPhase.CANCELED, CallPhase.ABORTED }) {
            CallLifecyclePublisher publisher = new CallLifecyclePublisher();
            publisher.publish(CallLifecycleEvent.of("req-x", CallPhase.CONNECTED, "model-a", true));
            publisher.publish(CallLifecycleEvent.of("req-x", terminal, "model-a", true));
            assertThat(publisher.snapshot())
                    .as("终态 %s 应从快照移除", terminal)
                    .isEmpty();
        }
    }

    @Test
    void snapshotIsAnImmutableCopyDecoupledFromLaterMutations() {
        CallLifecyclePublisher publisher = new CallLifecyclePublisher();
        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.CONNECTED, "model-a", true));

        Collection<CallLifecycleEvent> snapshot = publisher.snapshot();
        // 拿到快照后再发终态：已取得的快照不应随之改变（是拷贝而非视图）。
        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.COMPLETED, "model-a", true));

        assertThat(snapshot).hasSize(1);
        assertThat(publisher.snapshot()).isEmpty();
    }
}
