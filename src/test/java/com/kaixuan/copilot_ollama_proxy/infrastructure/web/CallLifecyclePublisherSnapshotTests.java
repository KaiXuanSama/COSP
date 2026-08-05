package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CallLifecyclePublisher} 进行中调用快照与模型展示名统一的行为测试。
 *
 * <p>快照用于消除「晚打开前端错过历史事件」的盲区：SSE 连接建立时先补发一份
 * 进行中调用的最新状态。核心不变量——非终态事件写入/更新快照，终态事件移除。
 *
 * <p>该 map 同时兼作模型展示名基准：事件从控制器（带 {@code [provider-key]} 前缀的
 * 客户端原始名）与 provider 层（剥掉前缀的上游模型名）两处发出，须统一为首个事件的名字。
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

    /**
     * 模型展示名以首个事件为准。
     *
     * <p>控制器发的是客户端原始请求名（含 {@code [provider-key]} 前缀），provider 层发的是
     * 剥掉前缀后的上游模型名。若不统一，Toast 标题会在 CONNECTED / RETRYING 到达时
     * 把前缀丢掉，同一次调用的展示名前后不一致。
     */
    @Test
    void laterEventsInheritDisplayModelFromFirstEvent() {
        CallLifecyclePublisher publisher = new CallLifecyclePublisher();
        List<CallLifecycleEvent> received = new ArrayList<>();
        publisher.events().subscribe(received::add);

        // 控制器：带前缀的原始请求名。
        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.RECEIVED, "[foo] bar", true));
        // provider 层：只知道剥掉前缀的上游模型名。
        publisher.publish(CallLifecycleEvent.retrying("req-1", "bar", true, 1));
        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.CONNECTED, "bar", true));

        assertThat(received)
                .extracting(CallLifecycleEvent::model)
                .containsExactly("[foo] bar", "[foo] bar", "[foo] bar");
        // 快照同样带展示名，晚订阅的前端不会看到丢了前缀的标题。
        assertThat(publisher.snapshot().iterator().next().model()).isEqualTo("[foo] bar");
    }

    /** 无前缀路由时首个事件本就没有前缀，不应凭空补出一个。 */
    @Test
    void displayModelStaysUnprefixedWhenClientSentNoPrefix() {
        CallLifecyclePublisher publisher = new CallLifecyclePublisher();
        List<CallLifecycleEvent> received = new ArrayList<>();
        publisher.events().subscribe(received::add);

        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.RECEIVED, "bar", true));
        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.CONNECTED, "bar", true));

        assertThat(received).extracting(CallLifecycleEvent::model).containsExactly("bar", "bar");
    }

    /** 终态事件也要带展示名——Toast 收尾那一帧同样会渲染标题。 */
    @Test
    void terminalEventAlsoInheritsDisplayModel() {
        CallLifecyclePublisher publisher = new CallLifecyclePublisher();
        List<CallLifecycleEvent> received = new ArrayList<>();
        publisher.events().subscribe(received::add);

        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.RECEIVED, "[foo] bar", true));
        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.COMPLETED, "bar", true, 3));

        assertThat(received.get(1).model()).isEqualTo("[foo] bar");
        assertThat(received.get(1).chunkCount()).isEqualTo(3);
        // 终态仍正常清理快照。
        assertThat(publisher.snapshot()).isEmpty();
    }

    /** 不同调用各自独立，展示名不互相串。 */
    @Test
    void displayModelIsScopedPerRequestId() {
        CallLifecyclePublisher publisher = new CallLifecyclePublisher();
        List<CallLifecycleEvent> received = new ArrayList<>();
        publisher.events().subscribe(received::add);

        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.RECEIVED, "[foo] bar", true));
        publisher.publish(CallLifecycleEvent.of("req-2", CallPhase.RECEIVED, "[baz] qux", true));
        publisher.publish(CallLifecycleEvent.of("req-1", CallPhase.CONNECTED, "bar", true));
        publisher.publish(CallLifecycleEvent.of("req-2", CallPhase.CONNECTED, "qux", true));

        assertThat(received)
                .extracting(CallLifecycleEvent::model)
                .containsExactly("[foo] bar", "[baz] qux", "[foo] bar", "[baz] qux");
    }
}
