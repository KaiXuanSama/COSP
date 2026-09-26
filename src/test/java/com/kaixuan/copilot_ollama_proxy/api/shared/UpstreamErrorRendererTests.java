package com.kaixuan.copilot_ollama_proxy.api.shared;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ResponseTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误渲染的分类骨架 —— 三条线路共用一份，逐档钉住状态码与 body 的来源。
 *
 * <h2>为何这些断言值得存在</h2>
 * 状态码分层的判据是「**上游到底有没有被连上**」，它决定用户该去改什么：
 * 400 → 改配置 / 改请求 / 改模型名；502 → 上游侧问题。
 * 差异一旦被抹平（比如把翻译类失败落进 502），排查方向会被指向网络 ——
 * 而那正是 {@link UpstreamFailureClassifier} 类注释警告的失效模式。
 *
 * <h2>本测试同时钉住 6b-1 补上的两个 case</h2>
 * {@code REQUEST_TRANSLATION} / {@code RESPONSE_TRANSLATION} 此前
 * <strong>只有 OpenAI 端点有</strong>，另两条会落进 {@code default} →
 * 502「无法连接到上游服务」。那两个异常目前只在「下游 CHAT」抛，故当时不可达；
 * 但 M2C 落地后 Anthropic 端点就会把「请求字段写错」报成 502。
 * 现在它们是共享骨架的一部分，三条线路天然一致。
 */
class UpstreamErrorRendererTests {

    private static final Logger log = LoggerFactory.getLogger(UpstreamErrorRendererTests.class);

    /** 记录被调用的是哪一档 body —— 用字符串前缀区分。 */
    private static final class RecordingBodies implements UpstreamErrorRenderer.ErrorBodies {
        String lastBadRequest;
        String lastUpstreamError;
        String lastAdapted;

        @Override
        public String badRequest(String message) {
            lastBadRequest = message;
            return "BAD_REQUEST:" + message;
        }

        @Override
        public String upstreamError(String message) {
            lastUpstreamError = message;
            return "UPSTREAM_ERROR:" + message;
        }

        @Override
        public String adaptUpstreamBody(String raw, WebClientResponseException exception) {
            lastAdapted = raw;
            return "ADAPTED:" + raw;
        }
    }

    private final RecordingBodies bodies = new RecordingBodies();

    @Nested
    @DisplayName("非流式：状态码分层")
    class Response {

        @Test
        @DisplayName("四个「上游没被连上」的类别都是 400，且 body 走 badRequest")
        void unreachableUpstreamIs400() {
            assertThat(codeOf(new ProtocolTranslationNotSupportedException(
                    WireProtocol.CHAT, WireProtocol.MESSAGES))).isEqualTo(400);
            assertThat(codeOf(new RequestTranslationException("messages[0].role", "缺失或为空")))
                    .isEqualTo(400);
            assertThat(codeOf(new NoSupportedProtocolException("provider-a"))).isEqualTo(400);
            assertThat(bodies.lastBadRequest).isNotNull();
            assertThat(bodies.lastUpstreamError).isNull();
        }

        @Test
        @DisplayName("响应翻译失败是 502（上游回了 2xx，但报文解析不了）")
        void responseTranslationIs502() {
            ResponseEntity<?> response = UpstreamErrorRenderer.response(
                    new ResponseTranslationException("上游返回空响应体，无法翻译"), "m", log, bodies);

            assertThat(response.getStatusCode().value()).isEqualTo(502);
            assertThat(bodies.lastUpstreamError).isEqualTo("上游返回空响应体，无法翻译");
        }

        @Test
        @DisplayName("未知异常是 502，且用固定兜底文案（不透出异常消息）")
        void unknownIs502WithFixedMessage() {
            ResponseEntity<?> response = UpstreamErrorRenderer.response(
                    new IllegalStateException("Reactor 内部细节"), "m", log, bodies);

            assertThat(response.getStatusCode().value()).isEqualTo(502);
            assertThat(bodies.lastUpstreamError).isEqualTo("无法连接到上游服务");
        }

        @Test
        @DisplayName("上游 HTTP 错误原样透传状态码与错误体（不走 body 工厂）")
        void upstreamHttpPassesThrough() {
            WebClientResponseException upstream = WebClientResponseException.create(
                    429, "Too Many Requests", null, "{\"error\":\"rate limited\"}".getBytes(), null);

            ResponseEntity<?> response = UpstreamErrorRenderer.response(upstream, "m", log, bodies);

            assertThat(response.getStatusCode().value()).isEqualTo(429);
            assertThat(response.getBody()).isEqualTo("{\"error\":\"rate limited\"}");
            // 透传路径不该碰 body 工厂 —— 上游原文比我们能编的任何文案都准确。
            assertThat(bodies.lastBadRequest).isNull();
            assertThat(bodies.lastUpstreamError).isNull();
        }

        private int codeOf(Throwable ex) {
            return UpstreamErrorRenderer.response(ex, "m", log, bodies).getStatusCode().value();
        }
    }

    @Nested
    @DisplayName("流式：只出 body，不出状态码")
    class Stream {

        @Test
        @DisplayName("四个「上游没被连上」的类别走 badRequest 体")
        void unreachableUpstreamUsesBadRequestBody() {
            String body = UpstreamErrorRenderer.streamBody(
                    new RequestTranslationException("messages[0].role", "缺失或为空"), "m", log, bodies);

            assertThat(body).startsWith("BAD_REQUEST:");
            assertThat(body).contains("messages[0].role");
        }

        @Test
        @DisplayName("响应翻译失败走 upstreamError 体")
        void responseTranslationUsesUpstreamErrorBody() {
            String body = UpstreamErrorRenderer.streamBody(
                    new ResponseTranslationException("报文无法翻译"), "m", log, bodies);

            assertThat(body).isEqualTo("UPSTREAM_ERROR:报文无法翻译");
        }

        @Test
        @DisplayName("上游 HTTP 走 adaptUpstreamBody —— 这是 Responses 覆写的那一档")
        void upstreamHttpUsesAdapter() {
            WebClientResponseException upstream = WebClientResponseException.create(
                    500, "Server Error", null, "oops".getBytes(), null);

            String body = UpstreamErrorRenderer.streamBody(upstream, "m", log, bodies);

            assertThat(body).isEqualTo("ADAPTED:oops");
            assertThat(bodies.lastAdapted).isEqualTo("oops");
        }

        @Test
        @DisplayName("adaptUpstreamBody 默认原样透传（只有 Responses 覆写）")
        void defaultAdapterPassesThrough() {
            UpstreamErrorRenderer.ErrorBodies plain = new UpstreamErrorRenderer.ErrorBodies() {
                @Override
                public String badRequest(String message) {
                    return "bad";
                }

                @Override
                public String upstreamError(String message) {
                    return "err";
                }
            };
            WebClientResponseException upstream = WebClientResponseException.create(
                    500, "Server Error", null, "oops".getBytes(), null);

            String body = UpstreamErrorRenderer.streamBody(upstream, "m", log, plain);

            assertThat(body).isEqualTo("oops");
        }
    }

    @Nested
    @DisplayName("HttpStatus 常量对齐（防止状态码被改错）")
    class StatusCodes {

        @Test
        @DisplayName("400 / 502 用的是标准常量语义")
        void usesStandardCodes() {
            assertThat(HttpStatus.BAD_REQUEST.value()).isEqualTo(400);
            assertThat(HttpStatus.BAD_GATEWAY.value()).isEqualTo(502);
        }
    }
}
