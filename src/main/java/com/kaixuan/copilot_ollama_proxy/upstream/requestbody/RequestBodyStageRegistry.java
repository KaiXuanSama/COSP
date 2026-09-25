package com.kaixuan.copilot_ollama_proxy.upstream.requestbody;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.upstream.requestbody.maxtokens.MaxTokensNormalizeStage;
import com.kaixuan.copilot_ollama_proxy.upstream.requestbody.system.SystemPromptNormalizeStage;
import com.kaixuan.copilot_ollama_proxy.upstream.requestbody.thinking.ThinkingInjectStage;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 请求体支线的查表 —— 按 {@link WireProtocol} 查三个协议特定步骤的实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：<strong>注册表</strong>（接入点级） · 位置：{@code upstream/requestbody/}
 * 步骤「请求体协议特定步骤」—— 三步聚合；未命中即<strong>跳过</strong>（「该协议没这一步」是领域事实）
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>它就是方向文档说的「缺的只是那个 Map」</h2>
 * 三个接口各自声明 {@code protocol()} 键、实现带 {@code @Component}，
 * 本类靠 Spring 集合注入把它们收成三张表。
 * <strong>「加一个协议的实现 = 加一个 {@code @Component}」，主干一个字不动</strong> ——
 * 它自动出现在查表里（前提是那个类在组件扫描范围内）。
 *
 * <h2>为何三张表而不是一张</h2>
 * 三个步骤的方法形状不同（有的只需 {@code body}、有的要 {@code resolvedModel + provider}），
 * 因此它们是<strong>三个不同的类型</strong>。用三条链而不是「一个接口 + 一个 type 枚举」：
 * 后者会把不同形状的方法逼成最小公倍数，且让「哪个步骤属于哪种协议」变成运行期数据而非类型。
 *
 * <p>这与 {@code TranslatorRegistry} 用「两张同键不同值的表」是同一个手法：
 * <strong>键相同（都是协议）、值类型不同，因此分表</strong>。
 *
 * <h2>未命中是合法的 —— 「跳过本步」</h2>
 * 查不到实现表示<strong>这种协议没有这个步骤</strong>（如 Chat 不需要抬升 system、
 * Responses 不需要补 max_tokens），主干据此跳过。
 * 这与「装配坏了」是两件事：装配问题由 Spring 自己的失败呈现，
 * 而结构断言（{@code RequestBodyStageSpringWiringTests}）守住「本应存在的实现确实被收集到」。
 *
 * <h2>为何是 Bean 而非无状态静态工具</h2>
 * 与 {@code TranslatorRegistry} 同一判据：「有没有需要在构造期固化下来的状态」。
 * 本类<strong>持有</strong>集合注入建好的三张表，状态来自容器收集的 Bean，因此必须是 Bean。
 *
 * <h2>同一个协议两个实现 = 配置错误，启动即失败</h2>
 * 与 {@code TranslatorRegistry} 同一取向：声明矛盾早失败，比运行期随机选一个可预测得多。
 */
@Component
public class RequestBodyStageRegistry {

    private final Map<WireProtocol, SystemPromptNormalizeStage> systemPromptStages;
    private final Map<WireProtocol, MaxTokensNormalizeStage> maxTokensStages;
    private final Map<WireProtocol, ThinkingInjectStage> thinkingStages;

    /**
     * 由 Spring 集合注入构造。
     *
     * <p>三个 {@code List} 收集容器里全部对应类型的实现。空 List 是合法的
     * （某个步骤一个实现都没有），只是那个步骤的查表恒未命中。
     *
     * @param systemPromptStages 所有 system 提示词抬升支线实现
     * @param maxTokensStages    所有 max_tokens 落定支线实现
     * @param thinkingStages     所有思考注入支线实现
     */
    public RequestBodyStageRegistry(List<SystemPromptNormalizeStage> systemPromptStages,
                                    List<MaxTokensNormalizeStage> maxTokensStages,
                                    List<ThinkingInjectStage> thinkingStages) {
        this.systemPromptStages = index(systemPromptStages, SystemPromptNormalizeStage::protocol);
        this.maxTokensStages = index(maxTokensStages, MaxTokensNormalizeStage::protocol);
        this.thinkingStages = index(thinkingStages, ThinkingInjectStage::protocol);
    }

    /**
     * 按协议建索引，同键冲突即抛。
     *
     * <p>键是<strong>声明</strong>（写在实现里）而非靠类名<strong>猜</strong>，
     * 与项目其它地方一致（能力由 {@code caps_tools} 声明，不按模型名猜）。
     */
    private static <T> Map<WireProtocol, T> index(List<T> stages,
                                                  java.util.function.Function<T, WireProtocol> keyOf) {
        Map<WireProtocol, T> byProtocol = new HashMap<>();
        for (T stage : stages) {
            WireProtocol protocol = keyOf.apply(stage);
            T previous = byProtocol.put(protocol, stage);
            if (previous != null) {
                throw new IllegalStateException(
                        "协议 " + protocol + " 的请求体支线有两个实现："
                                + previous.getClass().getName() + " 与 " + stage.getClass().getName()
                                + "。每个步骤每个协议只能有一个实现");
            }
        }
        return Map.copyOf(byProtocol);
    }

    /**
     * 查 system 提示词抬升支线。
     *
     * @param protocol 当前请求体所属的协议
     * @return 命中则为该协议的实现；未命中为空（表示这种协议不需要本步骤）
     */
    public Optional<SystemPromptNormalizeStage> findSystemPromptStage(WireProtocol protocol) {
        return Optional.ofNullable(systemPromptStages.get(protocol));
    }

    /**
     * 查 {@code max_tokens} 落定支线。
     *
     * @param protocol 当前请求体所属的协议
     * @return 命中则为该协议的实现；未命中为空（表示这种协议的该字段可选，不该补）
     */
    public Optional<MaxTokensNormalizeStage> findMaxTokensStage(WireProtocol protocol) {
        return Optional.ofNullable(maxTokensStages.get(protocol));
    }

    /**
     * 查思考注入支线。
     *
     * @param protocol 当前请求体所属的协议
     * @return 命中则为该协议的实现；未命中为空（表示该协议的注入尚未支线化）
     */
    public Optional<ThinkingInjectStage> findThinkingStage(WireProtocol protocol) {
        return Optional.ofNullable(thinkingStages.get(protocol));
    }
}
