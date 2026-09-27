package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslatedRequest;

import java.util.Map;

/**
 * 一条翻译链的<strong>去程</strong>（请求体：下游协议 → 上游协议）。
 *
 * <h2>为何拆成子接口而不是把方法收进 {@link ProtocolTranslator}</h2>
 * {@link ProtocolTranslator} 的 Javadoc 明确反对「一个翻译器 = 三个方法」的合并：
 * 四个方向的方法形状互不相同，硬统一只会得到一个谁都用不上的最小公倍数，而且
 * 一条链的去程与回程<strong>分属两个类</strong>。本接口不违反那条判断 ——
 * 它只收<strong>去程这一个方向</strong>的方法，形状对所有去程翻译器都一致
 * （{@code Map -> TranslatedRequest}），因此是「贴合一个方向的真实形状」，
 * 不是「跨方向的最小公倍数」。
 *
 * <h2>它买到的东西：查表与去程/回程独立缺省</h2>
 * 有了这个类型，编排层就能按 {@link TranslationRoute}「(下游, 上游)」查<strong>去程表</strong>，
 * 与回程表各查各的。这让「只实现了一半」（先写去程、用真实上游验证请求是否被接受）
 * 在结构上可表达：去程命中即改写请求，回程未命中则原样透传上游响应 ——
 * 
 *
 * <p>「未实现」的表达因此是「没有这个方向的 {@code @Component} 实现」，
 * 而不是某个要记得维护的 bool 开关：写完新方向、加上 {@code @Component}，
 * 它就自动出现在查表里。
 *
 * @see ProtocolTranslator
 * @see ResponseProtocolTranslator
 */
public interface RequestProtocolTranslator extends ProtocolTranslator {

    /**
     * 把下游请求体翻译成上游协议形态。
     *
     * <p>签名与 {@code ChatToMessagesRequestTranslator.translateRequest} 逐字一致 ——
     * 抽子接口是纯粹的「形状改造」，不改任何翻译逻辑。
     *
     * @param downstreamBody 下游请求体，不会被修改
     * @return 翻译产物，含供响应侧使用的上下文
     * @throws RequestTranslationException 请求内容无法表达成上游协议
     */
    TranslatedRequest translateRequest(Map<String, Object> downstreamBody);
}
