package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ResponseTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTokens;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatToMessagesResponseTranslatorTests {

    private static final TranslationContext CONTEXT = new TranslationContext(true, false);
    private static final String CONTENT = """
            {"id":"chatcmpl_source","model":"upstream-alias","choices":[{"delta":{"content":"答案"}}]}
            """;
    private static final String FINISH = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}";
    private static final String USAGE = """
            {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":7,
             "prompt_tokens_details":{"cached_tokens":40,"cache_write_tokens":20}}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatToMessagesResponseTranslator translator = new ChatToMessagesResponseTranslator(mapper);

    @Test
    void declaresReverseResponseRouteWithoutConfusingRequestDirection() {
        assertThat(translator.downstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);
        assertThat(translator.upstreamProtocol()).isEqualTo(WireProtocol.CHAT);
        TranslatorRegistry registry = new TranslatorRegistry(List.of(new MessagesToChatRequestTranslator(mapper)),
                List.of(translator));
        assertThat(registry.findResponseTranslator(WireProtocol.MESSAGES, WireProtocol.CHAT)).containsSame(translator);
        assertThat(registry.findResponseTranslator(WireProtocol.CHAT, WireProtocol.MESSAGES)).isEmpty();
    }

    @Test
    void nonStreamUsesDisplayUsageButCarriesNativeUsageUnchanged() throws Exception {
        UsageTokens nativeUsage = new UsageTokens(100, 7, 40);
        String body = """
                {"id":"chatcmpl_source","model":"upstream-alias","choices":[{"message":{"content":"答案"}}],
                 "usage":{"prompt_tokens":100,"completion_tokens":7,
                 "prompt_tokens_details":{"cached_tokens":40,"cache_write_tokens":20}}}
                """;
        UpstreamEvent result = translator.translateResponse(Mono.just(UpstreamEvent.body(body, nativeUsage)))
                .block(Duration.ofSeconds(3));

        assertThat(result).isNotNull();
        assertThat(result.isTerminal()).isFalse();
        assertThat(result.usage()).isSameAs(nativeUsage);
        assertThat(mapper.readTree(result.data()).path("usage").path("input_tokens").asInt()).isEqualTo(40);
        assertThat(result.usage().promptTokens()).isEqualTo(100);
    }

    @Test
    void missingAccountingSlotIsNeverReconstructedFromData() {
        List<UpstreamEvent> output = collect(Flux.just(UpstreamEvent.body(CONTENT), UpstreamEvent.body(FINISH),
                UpstreamEvent.body(USAGE), UpstreamEvent.terminal("[DONE]")));

        assertThat(output).allMatch(event -> event.usage() == null);
        assertThat(output).anyMatch(event -> event.data().contains("\"input_tokens\":40"));
        assertThat(output.getLast().isTerminal()).isTrue();
        assertThat(output.getLast().data()).isEqualTo("{\"type\":\"message_stop\"}");
        assertThat(output).noneMatch(event -> "[DONE]".equals(event.data()));
    }

    @Test
    void usageOnZeroOutputEventSurvivesAndEmptySlotCannotEraseIt() {
        UsageTokens known = new UsageTokens(100, 7, 40);
        List<UpstreamEvent> output = collect(Flux.just(UpstreamEvent.body(USAGE, known),
                UpstreamEvent.body("{\"choices\":[],\"usage\":{\"unknown\":1}}", UsageTokens.EMPTY),
                UpstreamEvent.terminal("[DONE]")));

        assertThat(output).hasSize(3);
        assertThat(output).allMatch(event -> event.usage() == known);
        assertThat(output.stream().filter(UpstreamEvent::isTerminal)).hasSize(1);
    }

    @Test
    void earlierFramesUseEarlierUsageSnapshotAndTerminalUsesLatestNativeSlot() {
        UsageTokens first = new UsageTokens(10, 0, null);
        UsageTokens finalUsage = new UsageTokens(100, 7, 40);
        List<UpstreamEvent> output = collect(Flux.just(UpstreamEvent.body(CONTENT, first),
                UpstreamEvent.body(FINISH, first), UpstreamEvent.body(USAGE, finalUsage),
                UpstreamEvent.terminal("[DONE]", finalUsage)));

        assertThat(output.subList(0, 4)).allMatch(event -> event.usage() == first);
        assertThat(output.subList(4, output.size())).allMatch(event -> event.usage() == finalUsage);
        assertThat(output.getLast().usage()).isSameAs(finalUsage);
    }

    @Test
    void resubscriptionGetsFreshBlockIndexesAndFreshMessageStart() {
        Flux<UpstreamEvent> translated = translator.translateStream(Flux.just(UpstreamEvent.body(CONTENT),
                UpstreamEvent.body(FINISH), UpstreamEvent.terminal("[DONE]")), "upstream-model", CONTEXT);

        List<UpstreamEvent> first = translated.collectList().block(Duration.ofSeconds(3));
        List<UpstreamEvent> second = translated.collectList().block(Duration.ofSeconds(3));

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(6);
        assertThat(first.get(1).data()).contains("\"index\":0");
    }

    @Test
    void batchReplayMatchesWireOutputAndKeepsPerEventFrameCounts() {
        List<String> source = List.of(CONTENT, FINISH, USAGE, "[DONE]");
        List<String> original = List.copyOf(source);
        List<UpstreamEvent> output = collect(Flux.fromIterable(source).map(UpstreamEvent::body));

        TranslatedChunkLog log = translator.translateChunksForLog(source, "upstream-model", false);

        assertThat(log.translated()).isEqualTo(output.stream().map(UpstreamEvent::data).toList());
        assertThat(log.frameCounts()).containsExactly(3, 1, 2, 0);
        assertThat(source).isEqualTo(original);
        assertThat(translator.translateChunksForLog(source, "upstream-model", true)).isEqualTo(log);
        assertThat(translator.translateChunksForLog(source, "upstream-model", false)).isEqualTo(log);
    }

    @Test
    void eofOnlyFramesStayOutsidePerEventCounts() {
        TranslatedChunkLog log = translator.translateChunksForLog(List.of(CONTENT, FINISH), "upstream-model", false);

        assertThat(log.frameCounts()).containsExactly(3, 1);
        assertThat(log.translated()).hasSize(6);
        assertThat(log.translated().getLast()).isEqualTo("{\"type\":\"message_stop\"}");
    }

        @Test
        void missingToolIdFallbackIsUniqueAcrossMessagesAndMatchesLogReplay() throws Exception {
        String template = "{\"id\":\"%s\",\"choices\":[{\"delta\":{\"tool_calls\":["
            + "{\"index\":0,\"function\":{\"name\":\"ping\",\"arguments\":\"{}\"}}]}}]}";
        List<String> firstSource = List.of(template.formatted("chatcmpl_first"), "[DONE]");
        List<UpstreamEvent> first = collect(Flux.fromIterable(firstSource).map(UpstreamEvent::body));
        List<UpstreamEvent> second = collect(Flux.just(UpstreamEvent.body(template.formatted("chatcmpl_second")),
            UpstreamEvent.terminal("[DONE]")));
        String firstId = mapper.readTree(first.get(1).data()).path("content_block").path("id").asText();
        String secondId = mapper.readTree(second.get(1).data()).path("content_block").path("id").asText();

        assertThat(firstId).startsWith("toolu_cosp_").isNotEqualTo(secondId);
        assertThat(translator.translateChunksForLog(firstSource, "upstream-model", false).translated())
            .isEqualTo(first.stream().map(UpstreamEvent::data).toList());
        }

    @Test
    void upstreamErrorPropagatesWithoutAppendingNormalCompletion() {
        IllegalStateException upstreamError = new IllegalStateException("connection broken");
        List<UpstreamEvent> seen = new ArrayList<>();
        Flux<UpstreamEvent> source = Flux.concat(Flux.just(UpstreamEvent.body(CONTENT)), Flux.error(upstreamError));

        assertThatThrownBy(() -> translator.translateStream(source, "upstream-model", CONTEXT)
                .doOnNext(seen::add).collectList().block(Duration.ofSeconds(3))).isSameAs(upstreamError);
        assertThat(seen).hasSize(3).noneMatch(UpstreamEvent::isTerminal);
        assertThat(seen).noneMatch(event -> event.data().contains("\"message_delta\""));
    }

    @Test
    void translationFailureIsVisibleAndCannotBeTurnedIntoSuccess() {
        List<UpstreamEvent> seen = new ArrayList<>();

        assertThatThrownBy(() -> translator.translateStream(Flux.just(UpstreamEvent.body(CONTENT),
                        UpstreamEvent.body("{\"error\":{\"message\":\"boom\"}}")), "upstream-model", CONTEXT)
                .doOnNext(seen::add).collectList().block(Duration.ofSeconds(3)))
                .isInstanceOf(ResponseTranslationException.class).hasMessageContaining("boom");
        assertThat(seen).hasSize(3).noneMatch(UpstreamEvent::isTerminal);
    }

    @Test
    void cancellationDoesNotInjectFinalizeFrames() {
        Flux<UpstreamEvent> source = Flux.concat(Flux.just(UpstreamEvent.body(CONTENT)), Flux.never());
        List<UpstreamEvent> output = translator.translateStream(source, "upstream-model", CONTEXT)
                .take(3).collectList().block(Duration.ofSeconds(3));

        assertThat(output).hasSize(3).noneMatch(UpstreamEvent::isTerminal);
    }

    @Test
    void nonStreamWithoutSlotStillDoesNotParseAccountingFromUsage() {
        UpstreamEvent output = translator.translateResponse(Mono.just(UpstreamEvent.body("""
                {"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":100,"completion_tokens":7}}
                """))).block(Duration.ofSeconds(3));

        assertThat(output).isNotNull();
        assertThat(output.usage()).isNull();
        assertThat(output.data()).contains("\"input_tokens\":100");
    }

    private List<UpstreamEvent> collect(Flux<UpstreamEvent> source) {
        return translator.translateStream(source, "upstream-model", CONTEXT)
                .collectList().block(Duration.ofSeconds(3));
    }
}