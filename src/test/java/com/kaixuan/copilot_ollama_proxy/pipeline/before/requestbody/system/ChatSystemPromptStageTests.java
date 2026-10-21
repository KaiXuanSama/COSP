package com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.system;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CHAT 的 system 归一化：补齐 M2C 所需的中间形态，同时守住标准 Chat 直连 no-op。
 *
 * <p>新支线会被所有 CHAT 装配查到，故不仅要验「能落回消息」，还要验
 * 「不误删普通提示词、不改原始嵌套容器、不把非法内容静默变合法」。
 */
class ChatSystemPromptStageTests {

    private static final String BILLING = "x-anthropic-billing-header: cc_version=test; cch=rotating;";
    private final ChatSystemPromptStage stage = new ChatSystemPromptStage();

    @Test
    void declaresChatProtocol() {
        assertThat(stage.protocol()).isEqualTo(WireProtocol.CHAT);
    }

    @Nested
    class MessagePlacement {

        @Test
        void prependsSystemStringWithoutChangingOtherMessages() {
            Map<String, Object> user = message("user", "hi");
            List<Object> messages = List.of(user);
            Map<String, Object> body = bodyWithSystem("你是助手\n保留段落。", messages);
            body.put("stream", true);

            stage.apply(body);

            assertThat(body).doesNotContainKey("system").containsEntry("stream", true);
            assertThat(messagesOf(body)).containsExactly(
                    message("system", "你是助手\n保留段落。"), user);
            assertThat(messagesOf(body).get(1)).isSameAs(user);
            assertThat(messages).containsExactly(user);
        }

        @Test
        void foldsTextBlocksWithBlankLinesAndDropsOnlyTheirProtocolWrapper() {
            Map<String, Object> first = Map.of("type", "text", "text", "  first\nparagraph  ",
                    "cache_control", Map.of("type", "ephemeral", "ttl", "1h"));
            Map<String, Object> second = textBlock("second");
            List<Object> blocks = List.of(first, second);
            Map<String, Object> body = bodyWithSystem(blocks, List.of(message("user", "hi")));

            stage.apply(body);

            assertThat(firstMessage(body)).containsEntry("content", "  first\nparagraph  \n\nsecond")
                    .doesNotContainKey("cache_control");
            assertThat(blocks).containsExactly(first, second);
            assertThat(first).containsKey("cache_control");
        }

        @Test
        void mergesOnlyTheLeadingStringSystemAndPreservesItsOtherFields() {
            Map<String, Object> leading = Map.of("role", "system", "content", "original", "name", "guide");
            Map<String, Object> later = message("system", "later");
            Map<String, Object> user = message("user", "hi");
            List<Object> input = List.of(leading, user, later);
            Map<String, Object> body = bodyWithSystem("top", input);

            stage.apply(body);

            assertThat(messagesOf(body)).hasSize(3);
            assertThat(firstMessage(body)).containsEntry("content", "top\n\noriginal")
                    .containsEntry("name", "guide").isNotSameAs(leading);
            assertThat(messagesOf(body).get(1)).isSameAs(user);
            assertThat(messagesOf(body).get(2)).isSameAs(later);
            assertThat(leading).containsEntry("content", "original");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " \t\r\n"})
        void replacesBlankLeadingSystemWithoutAddingAPhantomSeparator(String existing) {
            Map<String, Object> body = bodyWithSystem("top", List.of(message("system", existing)));

            stage.apply(body);

            assertThat(messagesOf(body)).containsExactly(message("system", "top"));
        }

        @Test
        void preservesLeadingArraySystemInsteadOfFlatteningItsParts() {
            List<Object> existingContent = List.of(textBlock("original"));
            Map<String, Object> leading = message("system", existingContent);
            Map<String, Object> body = bodyWithSystem("top", List.of(leading));

            stage.apply(body);

            assertThat(messagesOf(body)).containsExactly(message("system", "top"), leading);
            assertThat(((Map<?, ?>) messagesOf(body).get(1)).get("content")).isSameAs(existingContent);
        }

        @Test
        void doesNotMergeDeveloperOrMoveLaterSystemMessages() {
            Map<String, Object> developer = message("developer", "developer");
            Map<String, Object> later = message("system", "later");
            Map<String, Object> body = bodyWithSystem("top", List.of(developer, later));

            stage.apply(body);

            assertThat(messagesOf(body)).containsExactly(message("system", "top"), developer, later);
        }

        @Test
        void acceptsAnEmptyMessagesArrayWithoutInventingUserContent() {
            Map<String, Object> body = bodyWithSystem("top", List.of());

            stage.apply(body);

            assertThat(messagesOf(body)).containsExactly(message("system", "top"));
        }
    }

    @Nested
    class BillingHeader {

        @ParameterizedTest
        @ValueSource(strings = {"\n", "\r\n", "\r"})
        void stripsOnlyTheLeadingAttributionLineAndOneOptionalBlankLine(String newline) {
            Map<String, Object> body = bodyWithSystem(BILLING + newline + newline + "prompt\nkeep",
                    List.of(message("user", "hi")));

            stage.apply(body);

            assertThat(firstMessage(body)).containsEntry("content", "prompt\nkeep");
        }

        @Test
        void preservesPromptInTheSameBlockAfterTheBillingLine() {
            Map<String, Object> body = bodyWithSystem(List.of(
                    textBlock(BILLING + "\r\n  prompt stays  "), textBlock("second")),
                    List.of(message("user", "hi")));

            stage.apply(body);

            assertThat(firstMessage(body)).containsEntry("content", "  prompt stays  \n\nsecond");
        }

        @Test
        void leavesAdditionalBlankLinesAndOrdinaryIdentityTextUntouched() {
            Map<String, Object> body = bodyWithSystem(BILLING + "\r\n\r\n\r\nYou are Claude.\nKeep this.",
                    List.of(message("user", "hi")));

            stage.apply(body);

            assertThat(firstMessage(body)).containsEntry("content", "\r\nYou are Claude.\nKeep this.");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "prompt\nx-anthropic-billing-header: user-authored",
                " x-anthropic-billing-header: user-authored",
                "x-anthropic-billing-header-other: not attribution",
                "You are Claude. Keep this identity instruction."
        })
        void keepsNonLeadingAndUnrelatedText(String text) {
            Map<String, Object> body = bodyWithSystem(text, List.of(message("user", "hi")));

            stage.apply(body);

            assertThat(firstMessage(body)).containsEntry("content", text);
        }

        @Test
        void changingOnlyBillingMetadataProducesTheSameSystemMessage() {
            List<Object> messages = List.of(message("user", "hi"));
            Map<String, Object> first = bodyWithSystem(List.of(textBlock(BILLING), textBlock("stable")), messages);
            Map<String, Object> second = bodyWithSystem(List.of(
                    textBlock("x-anthropic-billing-header: cc_version=other; cch=other;"),
                    textBlock("stable")), messages);

            stage.apply(first);
            stage.apply(second);

            assertThat(first).isEqualTo(second);
            assertThat(firstMessage(first)).containsEntry("content", "stable");
        }

        @Test
        void keepsBillingTextInABlockAfterOrdinaryPromptContent() {
            String quotedHeader = "x-anthropic-billing-header: keep this user-authored example";
            Map<String, Object> body = bodyWithSystem(List.of(
                    textBlock("ordinary prompt"), textBlock(quotedHeader)),
                    List.of(message("user", "hi")));

            stage.apply(body);

            assertThat(firstMessage(body)).containsEntry("content", "ordinary prompt\n\n" + quotedHeader);
        }
    }

    @Test
    void standardChatWithoutTopLevelSystemIsACompleteNoOp() {
        List<Object> messages = List.of(message("system", "keep"), message("user", "hi"));
        Map<String, Object> body = Map.of("model", "m", "messages", messages);

        assertThatCode(() -> stage.apply(body)).doesNotThrowAnyException();

        assertThat(body).containsEntry("messages", messages);
        assertThat(body.get("messages")).isSameAs(messages);
        assertThatCode(() -> stage.apply(Map.of())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("emptySystems")
    void removesEmptySystemWithoutRebuildingMessages(Object system) {
        List<Object> messages = List.of(message("system", "existing"), message("user", "hi"));
        Map<String, Object> body = bodyWithSystem(system, messages);

        stage.apply(body);

        assertThat(body).doesNotContainKey("system");
        assertThat(body.get("messages")).isSameAs(messages);
    }

    @Test
    void removingNullSystemDoesNotInventAMessagesArray() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system", null);

        stage.apply(body);

        assertThat(body).isEmpty();
    }

    @Test
    void onlyCopiesChangedContainersAndIsIdempotent() {
        Map<String, Object> existing = message("system", "existing");
        List<Object> input = List.of(existing, message("user", "hi"));
        Map<String, Object> body = bodyWithSystem(List.of(textBlock("top")), input);

        stage.apply(body);
        Map<String, Object> snapshot = new LinkedHashMap<>(body);
        Object rewrittenMessages = body.get("messages");
        stage.apply(body);

        assertThat(body).isEqualTo(snapshot);
        assertThat(body.get("messages")).isSameAs(rewrittenMessages).isNotSameAs(input);
        assertThat(existing).containsEntry("content", "existing");
        assertThatCode(() -> {
            firstMessage(body).put("name", "new");
            messagesOf(body).add(message("user", "next"));
        }).doesNotThrowAnyException();
        assertThat(input).hasSize(2);
        assertThat(existing).doesNotContainKey("name");
    }

    @Test
    void wrapperAndPureLogicProduceTheSameResult() {
        List<Object> messages = List.of(message("user", "hi"));
        Map<String, Object> wrapped = bodyWithSystem("top", messages);
        Map<String, Object> direct = bodyWithSystem("top", messages);

        stage.apply(wrapped);
        SystemPromptNormalizer.inlineSystemIntoMessages(direct);

        assertThat(wrapped).isEqualTo(direct);
    }

    @ParameterizedTest
    @MethodSource("invalidSystems")
    void rejectsUnrepresentableSystemWithItsSourcePathWithoutChangingBody(Object system, String path) {
        Map<String, Object> body = bodyWithSystem(system, List.of(message("user", "hi")));
        Map<String, Object> snapshot = new LinkedHashMap<>(body);

        assertThatThrownBy(() -> stage.apply(body))
                .isInstanceOfSatisfying(RequestTranslationException.class,
                        error -> assertThat(error.fieldPath()).isEqualTo(path));

        assertThat(body).isEqualTo(snapshot);
    }

    @ParameterizedTest
    @MethodSource("invalidMessages")
    void rejectsNonArrayMessagesRatherThanOverwritingThem(Object messages) {
        Map<String, Object> body = bodyWithSystem("top", messages);
        Map<String, Object> snapshot = new LinkedHashMap<>(body);

        assertThatThrownBy(() -> stage.apply(body))
                .isInstanceOfSatisfying(RequestTranslationException.class,
                        error -> assertThat(error.fieldPath()).isEqualTo("messages"));

        assertThat(body).isEqualTo(snapshot);
    }

    @ParameterizedTest
    @MethodSource("emptySystems")
    void emptySystemDoesNotHideAnExistingNonArrayMessagesField(Object system) {
        Map<String, Object> body = bodyWithSystem(system, "not an array");
        Map<String, Object> snapshot = new LinkedHashMap<>(body);

        assertThatThrownBy(() -> stage.apply(body))
                .isInstanceOfSatisfying(RequestTranslationException.class,
                        error -> assertThat(error.fieldPath()).isEqualTo("messages"));

        assertThat(body).isEqualTo(snapshot);
    }

    @Test
    void emptySystemDoesNotHideNullMessages() {
        Map<String, Object> body = bodyWithSystem("", null);

        assertThatThrownBy(() -> stage.apply(body))
                .isInstanceOfSatisfying(RequestTranslationException.class,
                        error -> assertThat(error.fieldPath()).isEqualTo("messages"));

        assertThat(body).containsEntry("system", "").containsEntry("messages", null);
    }

    @Test
    void rejectsMissingMessagesRatherThanInventingAConversation() {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("system", "top"));

        assertThatThrownBy(() -> stage.apply(body))
                .isInstanceOfSatisfying(RequestTranslationException.class,
                        error -> assertThat(error.fieldPath()).isEqualTo("messages"));

        assertThat(body).containsOnlyKeys("system");
    }

    private static Stream<Arguments> emptySystems() {
        return Stream.of(
                Arguments.of((Object) null), Arguments.of(""), Arguments.of(" \t\r\n"),
                Arguments.of(List.of()), Arguments.of(List.of(textBlock(""))),
                Arguments.of(List.of(textBlock(" \t"))), Arguments.of(BILLING),
                Arguments.of(BILLING + "\n"), Arguments.of(List.of(textBlock(BILLING))));
    }

    private static Stream<Arguments> invalidSystems() {
        Map<String, Object> nullText = new LinkedHashMap<>();
        nullText.put("type", "text");
        nullText.put("text", null);
        return Stream.of(
                Arguments.of(12, "system"), Arguments.of(Map.of("text", "x"), "system"),
                Arguments.of(List.of(textBlock("valid"), "invalid"), "system[1]"),
                Arguments.of(List.of(textBlock("valid"), Map.of("type", "image")), "system[1].type"),
                Arguments.of(List.of(Map.of("text", "missing type")), "system[0].type"),
                Arguments.of(List.of(Map.of("type", "text")), "system[0].text"),
                Arguments.of(List.of(nullText), "system[0].text"),
                Arguments.of(List.of(Map.of("type", "text", "text", 12)), "system[0].text"));
    }

    private static Stream<Arguments> invalidMessages() {
        return Stream.of(Arguments.of((Object) null), Arguments.of("not an array"),
                Arguments.of(Map.of("role", "user", "content", "hi")));
    }

    private static Map<String, Object> bodyWithSystem(Object system, Object messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system", system);
        body.put("messages", messages);
        return body;
    }

    private static Map<String, Object> message(String role, Object content) {
        return Map.of("role", role, "content", content);
    }

    private static Map<String, Object> textBlock(String text) {
        return Map.of("type", "text", "text", text);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> messagesOf(Map<String, Object> body) {
        return (List<Object>) body.get("messages");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstMessage(Map<String, Object> body) {
        return (Map<String, Object>) messagesOf(body).getFirst();
    }
}
