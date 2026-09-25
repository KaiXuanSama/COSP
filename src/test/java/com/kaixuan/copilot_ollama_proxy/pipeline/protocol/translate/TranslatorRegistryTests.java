package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslatedRequest;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TranslatorRegistry} 的单元测试 —— 查表命中/未命中、去程/回程各自独立、同向冲突即失败。
 *
 * <h2>为何这组用例值得单独存在</h2>
 * 本类是「去程/回程独立缺省」（方向文档 §2.3.2）的落地形状。它承载三个不显而易见的约定：
 * <ul>
 *   <li>去程表与回程表<strong>各查各的</strong> —— 一条链只接了去程时，去程命中而回程未命中，
 *       这正是「半轮实现态」在结构上的表达；</li>
 *   <li>未命中<strong>不是错误</strong>而是「该方向没有实现」的正常信号（编排层据此透传）；</li>
 *   <li>同一方向两个实现是<strong>配置矛盾</strong>，建索引即抛，而非静默留下后写入的那个。</li>
 * </ul>
 * 这些约定靠读代码保不住，靠单测才行。
 *
 * <h2>用匿名假实现而非真实翻译器</h2>
 * 真实翻译器只覆盖 {@code (CHAT, MESSAGES)} 一条链，测不到「未命中」「同向冲突」「多条链并存」。
 * 假实现只声明方向、方法体是最简桩 —— 本类测的是<strong>按方向建索引与查表</strong>，
 * 与翻译逻辑无关。
 */
class TranslatorRegistryTests {

    /** 只声明方向的假去程翻译器：方法体是不改写的最简桩。 */
    private static RequestProtocolTranslator fakeRequest(WireProtocol downstream, WireProtocol upstream) {
        return new RequestProtocolTranslator() {
            @Override
            public WireProtocol downstreamProtocol() {
                return downstream;
            }

            @Override
            public WireProtocol upstreamProtocol() {
                return upstream;
            }

            @Override
            public TranslatedRequest translateRequest(Map<String, Object> downstreamBody) {
                return new TranslatedRequest(downstreamBody, new TranslationContext(false, false));
            }
        };
    }

    /** 只声明方向的假回程翻译器：三个方法都是最简桩。 */
    private static ResponseProtocolTranslator fakeResponse(WireProtocol downstream, WireProtocol upstream) {
        return new ResponseProtocolTranslator() {
            @Override
            public WireProtocol downstreamProtocol() {
                return downstream;
            }

            @Override
            public WireProtocol upstreamProtocol() {
                return upstream;
            }

            @Override
            public Mono<UpstreamEvent> translateResponse(Mono<UpstreamEvent> upstreamBody) {
                return upstreamBody;
            }

            @Override
            public Flux<UpstreamEvent> translateStream(Flux<UpstreamEvent> upstreamEvents, String upstreamModel,
                                                       TranslationContext context) {
                return upstreamEvents;
            }

            @Override
            public TranslatedChunkLog translateChunksForLog(List<String> upstreamEvents, String upstreamModel,
                                                            boolean includeUsage) {
                return new TranslatedChunkLog(upstreamEvents, List.of());
            }
        };
    }

    @Nested
    @DisplayName("命中与未命中")
    class HitAndMiss {

        @Test
        @DisplayName("去程按声明的方向命中")
        void requestHitsByDeclaredRoute() {
            RequestProtocolTranslator c2m = fakeRequest(WireProtocol.CHAT, WireProtocol.MESSAGES);
            TranslatorRegistry registry = new TranslatorRegistry(List.of(c2m), List.of());

            assertThat(registry.findRequestTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES))
                    .containsSame(c2m);
        }

        @Test
        @DisplayName("回程按声明的方向命中")
        void responseHitsByDeclaredRoute() {
            ResponseProtocolTranslator m2c = fakeResponse(WireProtocol.CHAT, WireProtocol.MESSAGES);
            TranslatorRegistry registry = new TranslatorRegistry(List.of(), List.of(m2c));

            assertThat(registry.findResponseTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES))
                    .containsSame(m2c);
        }

        @Test
        @DisplayName("未声明的方向查不到 —— 表达「该方向未实现」")
        void undeclaredRouteMisses() {
            TranslatorRegistry registry = new TranslatorRegistry(
                    List.of(fakeRequest(WireProtocol.CHAT, WireProtocol.MESSAGES)),
                    List.of(fakeResponse(WireProtocol.CHAT, WireProtocol.MESSAGES)));

            assertThat(registry.findRequestTranslator(WireProtocol.CHAT, WireProtocol.RESPONSES)).isEmpty();
            assertThat(registry.findResponseTranslator(WireProtocol.MESSAGES, WireProtocol.CHAT)).isEmpty();
        }

        @Test
        @DisplayName("空注册表：一切查表都未命中，且不报错")
        void emptyRegistryMissesEverything() {
            TranslatorRegistry registry = new TranslatorRegistry(List.of(), List.of());

            assertThat(registry.findRequestTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES)).isEmpty();
            assertThat(registry.findResponseTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES)).isEmpty();
        }
    }

    /**
     * 去程与回程<strong>各查各的</strong> —— 这是「去程/回程独立缺省」的核心。
     *
     * <p>同一条链的两半分属两张表：一张有、另一张没有，就表达「开发者写了去程、还没写回程」
     * 这个真实中间态。若两张表被合成一张（或共用一个判据），这个中间态就表达不出来了。
     */
    @Nested
    @DisplayName("去程与回程相互独立")
    class RequestAndResponseIndependent {

        @Test
        @DisplayName("同一方向：去程有、回程无（半轮实现态）")
        void requestPresentResponseAbsent() {
            TranslatorRegistry registry = new TranslatorRegistry(
                    List.of(fakeRequest(WireProtocol.CHAT, WireProtocol.MESSAGES)),
                    List.of());

            assertThat(registry.findRequestTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES))
                    .as("去程已接")
                    .isPresent();
            assertThat(registry.findResponseTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES))
                    .as("回程未接 —— 编排层据此原样透传上游响应")
                    .isEmpty();
        }

        @Test
        @DisplayName("同一方向：回程有、去程无")
        void responsePresentRequestAbsent() {
            TranslatorRegistry registry = new TranslatorRegistry(
                    List.of(),
                    List.of(fakeResponse(WireProtocol.CHAT, WireProtocol.MESSAGES)));

            assertThat(registry.findRequestTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES)).isEmpty();
            assertThat(registry.findResponseTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES)).isPresent();
        }
    }

    @Nested
    @DisplayName("同一方向两个实现即配置矛盾")
    class DuplicateRoute {

        @Test
        @DisplayName("去程同向冲突：建索引即抛，不静默择一")
        void duplicateRequestFailsFast() {
            assertThatThrownBy(() -> new TranslatorRegistry(
                    List.of(fakeRequest(WireProtocol.CHAT, WireProtocol.MESSAGES),
                            fakeRequest(WireProtocol.CHAT, WireProtocol.MESSAGES)),
                    List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("两个实现")
                    .hasMessageContaining("CHAT")
                    .hasMessageContaining("MESSAGES");
        }

        @Test
        @DisplayName("回程同向冲突：同样建索引即抛")
        void duplicateResponseFailsFast() {
            assertThatThrownBy(() -> new TranslatorRegistry(
                    List.of(),
                    List.of(fakeResponse(WireProtocol.CHAT, WireProtocol.MESSAGES),
                            fakeResponse(WireProtocol.CHAT, WireProtocol.MESSAGES))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("两个实现");
        }

        @Test
        @DisplayName("不同方向不冲突：多条链可并存")
        void differentRoutesCoexist() {
            TranslatorRegistry registry = new TranslatorRegistry(
                    List.of(fakeRequest(WireProtocol.CHAT, WireProtocol.MESSAGES),
                            fakeRequest(WireProtocol.CHAT, WireProtocol.RESPONSES)),
                    List.of());

            assertThat(registry.findRequestTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES)).isPresent();
            assertThat(registry.findRequestTranslator(WireProtocol.CHAT, WireProtocol.RESPONSES)).isPresent();
        }
    }
}
