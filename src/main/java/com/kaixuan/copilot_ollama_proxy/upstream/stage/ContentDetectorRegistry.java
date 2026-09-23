package com.kaixuan.copilot_ollama_proxy.upstream.stage;

import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容检测器的查表 —— 按 {@link WireProtocol} 查 {@link ContentDetectorStage} 实现。
 *
 * <h2>它与另两个 registry 的区别：未命中 = <strong>报错</strong></h2>
 * 项目里已有两个同型注册表，但三者的未命中语义<strong>刻意不同</strong>：
 *
 * <table>
 *   <caption>三个注册表的未命中语义</caption>
 *   <tr><th>注册表</th><th>未命中</th><th>理由</th></tr>
 *   <tr><td>{@link RequestBodyStageRegistry}</td><td>跳过</td>
 *       <td>「这种协议的 body 没有这一步」是领域事实（如 Chat 不需要抬升 system）</td></tr>
 *   <tr><td>{@link ChunkStageRegistry}</td><td>跳过</td>
 *       <td>同上（归一是 Chat 专属步骤）</td></tr>
 *   <tr><td><strong>本表</strong></td><td><strong>报错</strong></td>
 *       <td>每个上游协议都<strong>必须</strong>能判空 —— 判不了意味着空响应兜底对那条线路
 *           失效，而那是保护用户的功能。没有「这种协议不需要判空」的领域解释</td></tr>
 * </table>
 *
 * <p>这与 {@code UpstreamExecutorRegistry} 同一取向（那个也是未命中即报错，
 * 理由同为「协议被声明支持却没有实现 = 装配坏了」）。
 *
 * <h2>为何不提供返回 {@code Optional} 的查找方法</h2>
 * 因为没有任何合法的「查不到」分支要写。给一个 {@code find} 只会诱使调用方
 * 写「查不到就跳过」的安全跳过，而那正是本表要避免的。
 *
 * <h2>键由实现声明，不靠类名猜</h2>
 * 与另两个 registry 同一机制：收集容器里全部实现，按各自 {@link ContentDetectorStage#protocol()}
 * 建索引。同一协议两个实现 **启动即失败**，不静默择一。
 */
@Component
public class ContentDetectorRegistry {

    private final Map<WireProtocol, ContentDetectorStage> byProtocol;

    /**
     * 由 Spring 集合注入构造。
     *
     * @param detectors 容器里全部检测器实现
     */
    public ContentDetectorRegistry(List<ContentDetectorStage> detectors) {
        Map<WireProtocol, ContentDetectorStage> indexed = new HashMap<>();
        for (ContentDetectorStage detector : detectors) {
            WireProtocol protocol = detector.protocol();
            ContentDetectorStage previous = indexed.put(protocol, detector);
            if (previous != null) {
                throw new IllegalStateException(
                        "协议 " + protocol + " 的内容检测器有两个实现："
                                + previous.getClass().getName() + " 与 " + detector.getClass().getName()
                                + "。每个协议只能有一个检测器实现");
            }
        }
        this.byProtocol = Map.copyOf(indexed);
    }

    /**
     * 取指定协议的检测器。
     *
     * @param upstreamProtocol 本次调用的上游协议（来自调度结论）
     * @return 该协议的检测器
     * @throws IllegalStateException 没有检测器服务该协议 —— 装配错误，不是领域事实。
     *         用 {@link IllegalStateException} 而非业务异常：它不该被控制器译成 400，
     *         而应作为「程序接线错误」暴露出来
     */
    public ContentDetectorStage require(WireProtocol upstreamProtocol) {
        ContentDetectorStage detector = byProtocol.get(upstreamProtocol);
        if (detector == null) {
            throw new IllegalStateException(
                    "没有内容检测器服务上游协议 " + upstreamProtocol
                            + " —— 这会让空响应兜底对该协议失效，属装配错误。已注册的协议："
                            + byProtocol.keySet());
        }
        return detector;
    }
}
