package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 翻译器查表 —— 按 {@link TranslationRoute}「(下游, 上游)」查去程与回程翻译器。
 *
 * <h2>它就是方向文档说的「缺的只是那个 Map」</h2>
 * 基建早已就位（{@link ProtocolTranslator} 声明了两个查表键、两个实现带 {@code @Component}），
 * 唯一缺的是把它们收集成 Map。本类正是那个 Map：靠 Spring 集合注入拿到所有
 * {@link RequestProtocolTranslator} / {@link ResponseProtocolTranslator} 实现，
 * 各自按方向建索引。「加一个方向的翻译器 = 加一个 {@code @Component}」——
 * 本类一个字不动，它自动出现在查表里（前提是那个类在组件扫描范围内）。
 *
 * <h2>为何是 Bean 而非无状态静态工具</h2>
 * 项目里 {@code UpstreamCallReporter} / {@code ProtocolNotifier} 那类共享件都是无状态纯静态、
 * 依赖作参数传入。本类不同：它<strong>持有</strong>集合注入建好的两张表，是有状态的注册表，
 * 状态来自容器收集的 Bean —— 因此它本身必须是 Bean。两种取向的判据是「有没有需要在
 * 构造期固化下来的状态」，本类有，故成 Bean。
 *
 * <h2>为何两张表而不是一张</h2>
 * 一条链的去程（{@code ChatToMessagesRequestTranslator}）与回程
 * （{@code MessagesToChatResponseTranslator}）<strong>分属两个类、方法形状不同</strong>，
 * 但都声明同一条链的方向（都 {@code (CHAT, MESSAGES)}）。用两张<strong>同键不同值</strong>的表：
 * 同一个 {@code (CHAT, MESSAGES)} 键在去程表查到请求翻译器、在回程表查到响应翻译器。
 *
 * <p>这正是「去程/回程独立缺省」（方向文档 §2.3.2）的落地形状：两张表各查各的，
 * 去程命中而回程未命中，就表达出「开发者写了去程、还没写回程」这个真实中间态 ——
 * 编排层据此对回程做原样透传（但不静默，见 §2.3.2）。
 *
 * <h2>同一方向两个实现 = 配置错误，启动即失败</h2>
 * 若两个 {@code @Component} 声明了相同的 {@code (下游, 上游)}，建索引时抛
 * {@link IllegalStateException} —— 而不是静默留下后写入的那个。「哪个翻译器配哪个方向」
 * 是声明式事实，冲突意味着声明矛盾，早失败比运行期随机选一个可预测得多。
 */
@Component
public class TranslatorRegistry {

    private final Map<TranslationRoute, RequestProtocolTranslator> requestTranslators;
    private final Map<TranslationRoute, ResponseProtocolTranslator> responseTranslators;

    /**
     * 由 Spring 集合注入构造。
     *
     * <p>两个 {@code List} 收集容器里全部对应方向的翻译器实现。空 List 是合法的
     * （某个方向一个实现都没有），只是那个方向的查表恒未命中 —— 恰好表达「未实现」。
     *
     * @param requestTranslators  所有去程翻译器实现
     * @param responseTranslators 所有回程翻译器实现
     */
    public TranslatorRegistry(List<RequestProtocolTranslator> requestTranslators,
                              List<ResponseProtocolTranslator> responseTranslators) {
        this.requestTranslators = index(requestTranslators);
        this.responseTranslators = index(responseTranslators);
    }

    /**
     * 按方向建索引，同键冲突即抛。
     *
     * <p>用 {@link ProtocolTranslator} 的两个键方法取方向 —— 键是<strong>声明</strong>
     * （写在实现里）而非靠类名<strong>猜</strong>，与项目其它地方一致
     * （能力由 {@code caps_tools} 声明，不按模型名猜）。
     */
    private static <T extends ProtocolTranslator> Map<TranslationRoute, T> index(List<T> translators) {
        Map<TranslationRoute, T> byRoute = new HashMap<>();
        for (T translator : translators) {
            TranslationRoute route =
                    new TranslationRoute(translator.downstreamProtocol(), translator.upstreamProtocol());
            T previous = byRoute.put(route, translator);
            if (previous != null) {
                throw new IllegalStateException(
                        "翻译方向 " + route + " 有两个实现：" + previous.getClass().getName()
                                + " 与 " + translator.getClass().getName()
                                + "。每个方向只能有一个翻译器实现");
            }
        }
        return Map.copyOf(byRoute);
    }

    /**
     * 查<strong>去程</strong>（请求体）翻译器。
     *
     * @param downstream 下游协议
     * @param upstream   上游协议
     * @return 命中则为该方向的请求翻译器；未命中为空
     */
    public Optional<RequestProtocolTranslator> findRequestTranslator(WireProtocol downstream,
                                                                     WireProtocol upstream) {
        return Optional.ofNullable(requestTranslators.get(new TranslationRoute(downstream, upstream)));
    }

    /**
     * 查<strong>回程</strong>（响应/事件流）翻译器。
     *
     * <p>未命中<strong>不是错误</strong>：它表达「回程翻译尚未实现」，编排层据此原样透传
     * 上游响应（去程/回程独立缺省，方向文档 §2.3.2）。调用方拿到空后应留痕再透传，
     * 不可静默。
     *
     * @param downstream 下游协议
     * @param upstream   上游协议
     * @return 命中则为该方向的响应翻译器；未命中为空（表示回程未实现）
     */
    public Optional<ResponseProtocolTranslator> findResponseTranslator(WireProtocol downstream,
                                                                       WireProtocol upstream) {
        return Optional.ofNullable(responseTranslators.get(new TranslationRoute(downstream, upstream)));
    }
}
