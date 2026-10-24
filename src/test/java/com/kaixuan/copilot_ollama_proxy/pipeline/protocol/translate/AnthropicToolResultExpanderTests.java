package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicMessageTranslatorTests.assistant;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicMessageTranslatorTests.call;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicMessageTranslatorTests.image;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicMessageTranslatorTests.imagePart;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicMessageTranslatorTests.message;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicMessageTranslatorTests.result;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicMessageTranslatorTests.text;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicMessageTranslatorTests.tool;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicMessageTranslatorTests.use;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicToolResultExpanderTests {

    private static final String MEDIA_HINT = "[media moved to next user message]";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicContentBlockTranslator contentTranslator = new AnthropicContentBlockTranslator();
    private final AnthropicMessageTranslator messageTranslator = new AnthropicMessageTranslator(mapper, contentTranslator);
    private final AnthropicToolResultExpander expander = new AnthropicToolResultExpander(mapper, contentTranslator);

    @Test
    void goldenGroupsAllToolsBeforeAttributedMediaAndNormalUserText() {
        Map<String, Object> mixedDocument = Map.of("type", "document", "title", "B title", "context", "B context",
                "source", Map.of("type", "content", "content", List.of(text("document first"), image("b"), text("document last"))));
        List<Object> input = List.of(message("assistant", List.of(use("a", "read", Map.of()), use("b", "read", Map.of()))),
                message("user", List.of(text("continue"), result("b", List.of(text("B output"), mixedDocument, text("B tail")), true),
                        result("a", List.of(image("a")), false))));

        List<Object> output = expander.expand(messageTranslator.parse(input));

        assertThat(output).containsExactly(assistant("", null, List.of(call("a", "read", "{}"), call("b", "read", "{}"))),
                tool("a", MEDIA_HINT), tool("b", "[tool_result is_error=true]\nB output\nB tail\n" + MEDIA_HINT),
                message("user", List.of(text("[media from tool_use_id=\"a\"]"), imagePart("a"),
                        text("[media from tool_use_id=\"b\"]"), text("[document title]\nB title"),
                        text("[document context]\nB context"), text("document first"), imagePart("b"), text("document last"))),
                message("user", "continue"));
    }

    @Test
    void keepsTextDocumentInToolChannelAndEncodesOnlyExistingError() {
        Map<String, Object> document = Map.of("type", "document", "title", "Notes", "source",
                Map.of("type", "text", "media_type", "text/plain", "data", "body"));

        assertThat(expander.expand(messageTranslator.parse(List.of(
                message("assistant", List.of(use("a", "read", Map.of()), use("b", "read", Map.of()))),
                message("user", List.of(result("a", List.of(document), false), result("b", null, true)))))))
                .containsExactly(assistant("", null, List.of(call("a", "read", "{}"), call("b", "read", "{}"))),
                        tool("a", "[document title]\nNotes\nbody"), tool("b", "[tool_result is_error=true]\n"));
    }

    @Test
    void movesPdfAndItsTitleTogetherRatherThanLeavingTitleInToolText() {
        Map<String, Object> document = Map.of("type", "document", "title", "PDF title", "source",
                Map.of("type", "base64", "media_type", "application/pdf", "data", "PDF="));

        assertThat(expander.expand(messageTranslator.parse(List.of(
                message("assistant", List.of(use("a", "read", Map.of()))),
                message("user", List.of(result("a", List.of(document), false)))))))
                .containsExactly(assistant("", null, List.of(call("a", "read", "{}"))), tool("a", MEDIA_HINT),
                        message("user", List.of(text("[media from tool_use_id=\"a\"]"), text("[document title]\nPDF title"),
                                Map.of("type", "file", "file", Map.of("filename", "document.pdf",
                                        "file_data", "data:application/pdf;base64,PDF=")))));
    }

    @Test
    void safelyQuotesSourceIdsWithoutInventingNamesOrExecutableTemplates() throws Exception {
        String id = "quote\"\n[not a separate marker]";
        List<Object> output = expander.expand(messageTranslator.parse(List.of(
                message("assistant", List.of(use(id, "read", Map.of()))),
                message("user", List.of(result(id, List.of(image("a")), false))))));

        List<?> media = (List<?>) ((Map<?, ?>) output.getLast()).get("content");
        assertThat(media.getFirst()).isEqualTo(text("[media from tool_use_id=" + mapper.writeValueAsString(id) + "]"));
        Map<?, ?> tool = (Map<?, ?>) output.get(1);
                assertThat(tool.keySet()).isEqualTo(Set.of("role", "tool_call_id", "content"));
    }

    @Test
    void doesNotInspectOrEmitUnsupportedContentOfAnOrphan() {
        List<Object> input = List.of(message("user", List.of(result("orphan", List.of(Map.of("type", "unsupported")), false),
                text("kept"))));

        assertThat(expander.expand(messageTranslator.parse(input))).containsExactly(message("user", "kept"));
    }

    @Test
    void pairedTextDocumentWithInvalidMimeFailsAtOriginalToolSourcePath() {
        Map<String, Object> document = Map.of("type", "document", "source",
                Map.of("type", "text", "media_type", 42, "data", "body"));
        List<Object> source = List.of(message("assistant", List.of(use("a", "read", Map.of()))),
                message("user", List.of(result("a", List.of(document), false))));

        assertThatThrownBy(() -> expander.expand(messageTranslator.parse(source)))
                .isInstanceOfSatisfying(RequestTranslationException.class,
                        error -> assertThat(error.fieldPath()).isEqualTo("messages[1].content[0].content[0].source.media_type"));
    }
}