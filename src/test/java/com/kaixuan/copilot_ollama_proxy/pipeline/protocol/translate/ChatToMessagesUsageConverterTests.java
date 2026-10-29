package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.after.send.chat.OpenAiUsageParser;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatToMessagesUsageConverterTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesNativeCacheFallbackAndDoesNotModifySourceOrMetrics() throws Exception {
        JsonNode source = mapper.readTree("""
                {"prompt_tokens":100,"completion_tokens":7,"prompt_cache_hit_tokens":40,
                 "cached_tokens":999,"cache_creation_input_tokens":10}
                """);
        JsonNode original = source.deepCopy();
        UsageTokens nativeTokens = OpenAiUsageParser.parse(source);

        Map<String, Object> usage = ChatToMessagesUsageConverter.toAnthropicUsage(source);

        assertThat(usage).containsEntry("input_tokens", 50L).containsEntry("output_tokens", 7)
                .containsEntry("cache_read_input_tokens", 40).containsEntry("cache_creation_input_tokens", 10L);
        assertThat(source).isEqualTo(original);
        assertThat(nativeTokens).isEqualTo(new UsageTokens(100, 7, 40));
        assertThat(OpenAiUsageParser.parse(source)).isEqualTo(nativeTokens);
    }

    @Test
    void presentZeroWinsOverOtherCacheAliases() throws Exception {
        Map<String, Object> usage = convert("""
                {"prompt_tokens":100,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":0,
                 "cache_write_tokens":0,"cache_creation_tokens":20},"prompt_cache_hit_tokens":40,
                 "cache_creation_input_tokens":30}
                """);

        assertThat(usage).containsEntry("input_tokens", 100L).containsEntry("cache_read_input_tokens", 0)
                .containsEntry("cache_creation_input_tokens", 0L);
    }

    @Test
    void privateEffectiveCachedTokensFallbackIsReused() throws Exception {
        assertThat(convert("{\"prompt_tokens\":10,\"effectiveCachedTokens\":3}"))
                .containsEntry("input_tokens", 7L).containsEntry("cache_read_input_tokens", 3);
    }

    @Test
    void cacheCreationAliasAndClampAreDisplayOnly() throws Exception {
        JsonNode source = mapper.readTree("""
                {"prompt_tokens":10,"prompt_tokens_details":{"cached_tokens":8,"cache_creation_tokens":5}}
                """);

        assertThat(ChatToMessagesUsageConverter.toAnthropicUsage(source))
                .containsEntry("input_tokens", 0L).containsEntry("cache_creation_input_tokens", 5L);
        assertThat(OpenAiUsageParser.parse(source).promptTokens()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"credit\":5}", "{\"cached_tokens\":99}"})
    void missingCountersGiveDisplayPlaceholdersButNotCacheSupport(String json) throws Exception {
        JsonNode source = mapper.readTree(json);

        assertThat(ChatToMessagesUsageConverter.toAnthropicUsage(source))
                .containsOnlyKeys("input_tokens", "output_tokens").containsEntry("input_tokens", 0L);
        assertThat(ChatToMessagesUsageConverter.hasKnownCounters(source)).isFalse();
        assertThat(OpenAiUsageParser.parse(source).isEmpty()).isTrue();
    }

    @Test
    void usageSnapshotWithRealZeroIsNotTreatedAsAbsent() throws Exception {
        JsonNode source = mapper.readTree("{\"prompt_tokens\":0,\"completion_tokens\":0}");

        assertThat(ChatToMessagesUsageConverter.hasKnownCounters(source)).isTrue();
        assertThat(ChatToMessagesUsageConverter.toAnthropicUsage(source)).containsEntry("input_tokens", 0L);
    }

    private Map<String, Object> convert(String json) throws Exception {
        return ChatToMessagesUsageConverter.toAnthropicUsage(mapper.readTree(json));
    }
}