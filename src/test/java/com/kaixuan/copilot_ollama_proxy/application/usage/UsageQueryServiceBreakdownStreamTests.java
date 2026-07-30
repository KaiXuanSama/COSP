package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 验证与锁定：下钻用量明细的<strong>增量帧</strong>推送流。
 *
 * <p>这条流曾是「信号唤醒 → 重查全窗口 → 下发全量」，现已改为纯转发增量帧。
 * 本测试锁定改造后的三条关键性质：
 * <ul>
 *   <li>发布的帧原样到达订阅方 —— 前端据此累加，字段不能被篡改；</li>
 *   <li>流本身<strong>不查库</strong> —— 这正是改造要省掉的开销；</li>
 *   <li>多个订阅者（多标签页）都能收到同一帧。</li>
 * </ul>
 *
 * <p>「订阅即发全量快照」的责任在 controller（用 concat 把快照接在增量流前面），
 * 不在本方法，故不在此断言。
 *
 * <h2>为何用 subscribe 而非 blockFirst</h2>
 * sink 是 {@code directBestEffort}，订阅者尚无 demand 时它就丢弃帧。
 * {@code blockFirst().doOnSubscribe(发布)} 看似可行，实则 {@code doOnSubscribe}
 * 早于 {@code request(n)} 触发，帧会在需求登记前被丢掉，测试一路超时。
 * 故这里显式 {@code subscribe}（订阅即请求无界），拿到 Disposable 后再发布。
 */
class UsageQueryServiceBreakdownStreamTests {

    private static final UsageBreakdownDelta DELTA = new UsageBreakdownDelta(
            "2026-07-27", "2026-07-27T14:05:09", "deepseek", "chat", 1L, 120, 45);

    private ApiCallUsageRepository usageRepository;
    private UsageEventPublisher usageEventPublisher;
    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(ApiCallUsageRepository.class);
        usageEventPublisher = new UsageEventPublisher();
        service = new UsageQueryService(
                mock(ApiUsageRepository.class), usageRepository, usageEventPublisher);
    }

    /**
     * 发布的增量帧原样送达订阅方。
     *
     * <p>字段完整性是硬要求：前端靠 date 匹配柱子、靠三元组定位明细行，
     * 任何一项被改写都会让计数落到错误的格子里。
     */
    @Test
    void publishedDeltaReachesSubscriber() {
        List<UsageBreakdownDelta> received = new ArrayList<>();
        Disposable subscription = service.streamBreakdownDeltas().subscribe(received::add);

        usageEventPublisher.publishBreakdownDelta(DELTA);
        subscription.dispose();

        assertThat(received).containsExactly(DELTA);
    }

    /**
     * 增量流不触碰数据库。
     *
     * <p>这是整次改造的收益所在：旧实现每收到一次调用信号就把整个窗口
     * （7 天 × 全部供应商 × 全部模型）重新聚合一遍，而一次调用只影响一个格子。
     */
    @Test
    void deltaStreamDoesNotQueryDatabase() {
        Disposable subscription = service.streamBreakdownDeltas().subscribe();

        usageEventPublisher.publishBreakdownDelta(DELTA);
        subscription.dispose();

        verify(usageRepository, never()).aggregateBreakdown(anyInt());
    }

    /** 多标签页同时看概览时，每个订阅者都应收到同一帧（sink 是 multicast）。 */
    @Test
    void deltaIsBroadcastToEverySubscriber() {
        List<UsageBreakdownDelta> first = new CopyOnWriteArrayList<>();
        List<UsageBreakdownDelta> second = new CopyOnWriteArrayList<>();
        Disposable one = service.streamBreakdownDeltas().subscribe(first::add);
        Disposable two = service.streamBreakdownDeltas().subscribe(second::add);

        usageEventPublisher.publishBreakdownDelta(DELTA);
        one.dispose();
        two.dispose();

        assertThat(first).containsExactly(DELTA);
        assertThat(second).containsExactly(DELTA);
    }

    /**
     * 无人订阅时发布的帧被直接丢弃，不做无界缓冲 —— 这是 directBestEffort 的语义。
     *
     * <p>这条性质正是「订阅时必须先发一次全量快照」的原因：迟到的订阅者拿不到历史帧，
     * 只靠增量永远补不齐基准。
     */
    @Test
    void deltaPublishedWithoutSubscriberIsDropped() {
        usageEventPublisher.publishBreakdownDelta(DELTA);

        List<UsageBreakdownDelta> received = new ArrayList<>();
        Disposable subscription = service.streamBreakdownDeltas().subscribe(received::add);
        subscription.dispose();

        assertThat(received).as("迟到的订阅者不该收到历史帧").isEmpty();
    }
}
