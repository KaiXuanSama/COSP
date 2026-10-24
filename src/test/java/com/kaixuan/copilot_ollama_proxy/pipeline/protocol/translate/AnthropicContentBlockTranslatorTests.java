package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicContentBlockTranslatorTests {

    private final AnthropicContentBlockTranslator translator = new AnthropicContentBlockTranslator();

    @Test
    void preservesStringsAndJoinsTextBlocksWithLfWithoutTrimming() {
        assertThat(translate("  original\nparagraph  ")).isEqualTo("  original\nparagraph  ");
        assertThat(translate(List.of(text("  first  "), text(""), text("second\nline"))))
                .isEqualTo("  first  \nsecond\nline");
        assertThat(translate(null)).isEqualTo("");
        assertThat(translate(List.of())).isEqualTo("");
    }

    @Test
    void keepsMixedPartOrderAndDropsOnlyProtocolWrappers() {
        List<Object> source = List.of(text("before"), image("https://example.com/a.png"),
                Map.of("type", "text", "text", "after", "cache_control", Map.of("type", "ephemeral")));

        assertThat(translate(source)).isEqualTo(List.of(
                text("before"), Map.of("type", "image_url", "image_url", Map.of("url", "https://example.com/a.png")),
                text("after")));
        assertThat(((Map<?, ?>) source.get(2)).containsKey("cache_control")).isTrue();
    }

    @Test
    void wrapsImageBase64WithoutDecodingOrDownloading() {
        Map<String, Object> image = Map.of("type", "image", "source",
                Map.of("type", "base64", "media_type", "image/png", "data", "original+bytes="));

        assertThat(translate(List.of(image))).isEqualTo(List.of(Map.of(
                "type", "image_url", "image_url", Map.of("url", "data:image/png;base64,original+bytes="))));
    }

        @ParameterizedTest
        @ValueSource(strings = {"HTTP", "HTTPS", "hTtPs"})
        void httpSchemeIsCaseInsensitiveWithoutChangingSignedPathOrQuery(String scheme) {
                String url = scheme + "://example.com/a.png?Token=AbC%2F";

                assertThat(translate(List.of(image(url)))).isEqualTo(List.of(
                                Map.of("type", "image_url", "image_url", Map.of("url", url))));
        }

    @Nested
    class Documents {

        @Test
        void convertsPdfToOfficialFilePartWithStableFilenameAndKeepsMetadataAdjacent() {
            Map<String, Object> document = Map.of("type", "document", "title", "Report", "context", "Read table",
                    "source", Map.of("type", "base64", "media_type", "application/pdf", "data", "PDF+raw="));

            assertThat(translate(List.of(text("before"), document, text("after")))).isEqualTo(List.of(
                    text("before"), text("[document title]\nReport"), text("[document context]\nRead table"),
                    Map.of("type", "file", "file", Map.of("filename", "document.pdf",
                            "file_data", "data:application/pdf;base64,PDF+raw=")), text("after")));
        }

        @Test
        void retainsProvidedFilenameRatherThanGuessingItFromTitle() {
            Map<String, Object> document = Map.of("type", "document", "title", "Different title",
                    "filename", "actual.pdf", "source",
                    Map.of("type", "base64", "media_type", "application/pdf", "data", "invalid-but-preserved"));

            List<?> parts = (List<?>) translate(List.of(document));

            assertThat(((Map<?, ?>) parts.getLast()).get("file"))
                    .isEqualTo(Map.of("filename", "actual.pdf", "file_data",
                            "data:application/pdf;base64,invalid-but-preserved"));
        }

        @Test
        void keepsTextDocumentInTextChannelIncludingTitleAndContext() {
            Map<String, Object> document = Map.of("type", "document", "title", "Notes", "context", "source context",
                    "source", Map.of("type", "text", "media_type", "text/plain", "data", "  document\nbody  "));

            assertThat(translate(List.of(document)))
                    .isEqualTo("[document title]\nNotes\n[document context]\nsource context\n  document\nbody  ");
        }

        @Test
        void expandsContentSourceStringAndTextImageArrayInsteadOfStringifyingJson() {
            assertThat(translate(List.of(Map.of("type", "document", "source",
                    Map.of("type", "content", "content", "source text"))))).isEqualTo("source text");
            Map<String, Object> document = Map.of("type", "document", "title", "Mixed", "source",
                    Map.of("type", "content", "content", List.of(text("first"), image("https://example.com/b.png"),
                            text("last"))));

            var groups = translator.translate(List.of(document), "content");

            assertThat(groups).hasSize(1);
            assertThat(groups.getFirst().hasMedia()).isTrue();
            assertThat(AnthropicContentBlockTranslator.messageContent(groups)).isEqualTo(List.of(
                    text("[document title]\nMixed"), text("first"),
                    Map.of("type", "image_url", "image_url", Map.of("url", "https://example.com/b.png")), text("last")));
        }

        @Test
        void doesNotInferCitationsFromTitleContextOrContentSource() {
            Map<String, Object> source = Map.of("type", "content", "content", "quoted");
            for (Object citations : new Object[]{null, Map.of(), Map.of("enabled", false)}) {
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("type", "document");
                document.put("source", source);
                document.put("citations", citations);

                assertThat(translate(List.of(document))).isEqualTo("quoted");
            }
        }

                @Test
                void explicitNullMimeIsAFormatErrorRatherThanAnImplicitTextSource() {
                        Map<String, Object> source = new LinkedHashMap<>();
                        source.put("type", "text");
                        source.put("media_type", null);
                        source.put("data", "document body");

                        assertThatThrownBy(() -> translator.translate(List.of(Map.of("type", "document", "source", source)), "content"))
                                        .isInstanceOfSatisfying(RequestTranslationException.class,
                                                        error -> assertThat(error.fieldPath()).isEqualTo("content[0].source.media_type"));
                }
    }

    @ParameterizedTest
    @MethodSource("invalidContent")
    void reportsSourcePathWithoutIncludingContentPayload(Object content, String path) {
        assertThatThrownBy(() -> translator.translate(content, "content"))
                .isInstanceOfSatisfying(RequestTranslationException.class,
                        error -> assertThat(error.fieldPath()).isEqualTo(path))
                .hasMessageNotContaining("SENSITIVE_PAYLOAD");
    }

    @Test
    @SuppressWarnings("unchecked")
    void outputContainersAreMutableAndCannotMutateImmutableInput() {
        List<Object> source = List.of(text("before"), image("https://example.com/a.png"));
        List<Object> output = (List<Object>) translate(source);

        ((Map<String, Object>) output.getFirst()).put("text", "changed");
        ((Map<String, Object>) ((Map<?, ?>) output.get(1)).get("image_url")).put("detail", "low");
        output.add(text("extra"));

        assertThat(source).containsExactly(text("before"), image("https://example.com/a.png"));
        assertThat(translate(source)).isEqualTo(List.of(text("before"),
                Map.of("type", "image_url", "image_url", Map.of("url", "https://example.com/a.png"))));
    }

    private static Stream<Arguments> invalidContent() {
        return Stream.of(
                Arguments.of(12, "content"),
                Arguments.of(List.of("not a block"), "content[0]"),
                Arguments.of(List.of(Map.of("text", "x")), "content[0].type"),
                Arguments.of(List.of(Map.of("type", "text", "text", 12)), "content[0].text"),
                Arguments.of(List.of(Map.of("type", "audio", "data", "SENSITIVE_PAYLOAD")), "content[0].type"),
                Arguments.of(List.of(Map.of("type", "image")), "content[0].source"),
                Arguments.of(List.of(image("file:///private.png")), "content[0].source.url"),
                Arguments.of(List.of(Map.of("type", "image", "source", Map.of("type", "file", "file_id", "x"))),
                        "content[0].source.type"),
                Arguments.of(List.of(Map.of("type", "document", "source", Map.of("type", "url", "url", "SENSITIVE_PAYLOAD"))),
                        "content[0].source.type"),
                Arguments.of(List.of(Map.of("type", "document", "source", Map.of("type", "file", "file_id", "SENSITIVE_PAYLOAD"))),
                        "content[0].source.type"),
                Arguments.of(List.of(Map.of("type", "document", "source", Map.of("type", "base64", "media_type", "text/html", "data", "x"))),
                        "content[0].source.media_type"),
                Arguments.of(List.of(Map.of("type", "document", "source", Map.of("type", "text", "media_type", "text/plain"))),
                        "content[0].source.data"),
                Arguments.of(List.of(Map.of("type", "document", "source", Map.of("type", "text", "data", "x"))),
                        "content[0].source.media_type"),
                Arguments.of(List.of(Map.of("type", "document", "source", Map.of("type", "text", "media_type", 42, "data", "x"))),
                        "content[0].source.media_type"),
                Arguments.of(List.of(Map.of("type", "document", "source", Map.of("type", "text", "media_type", "application/pdf", "data", "x"))),
                        "content[0].source.media_type"),
                Arguments.of(List.of(Map.of("type", "document", "source", Map.of("type", "content", "content", List.of(
                        Map.of("type", "document", "source", Map.of("type", "text", "data", "x")))))),
                        "content[0].source.content[0].type"),
                Arguments.of(List.of(Map.of("type", "document", "citations", Map.of("enabled", true),
                        "source", Map.of("type", "text", "data", "SENSITIVE_PAYLOAD"))),
                        "content[0].citations.enabled"),
                Arguments.of(List.of(Map.of("type", "document", "citations", Map.of("enabled", "true"),
                        "source", Map.of("type", "text", "data", "x"))), "content[0].citations.enabled"),
                Arguments.of(List.of(Map.of("type", "document", "title", 12,
                        "source", Map.of("type", "text", "data", "x"))), "content[0].title"));
    }

    private Object translate(Object content) {
        return AnthropicContentBlockTranslator.messageContent(translator.translate(content, "content"));
    }

    private static Map<String, Object> text(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static Map<String, Object> image(String url) {
        return Map.of("type", "image", "source", Map.of("type", "url", "url", url));
    }
}