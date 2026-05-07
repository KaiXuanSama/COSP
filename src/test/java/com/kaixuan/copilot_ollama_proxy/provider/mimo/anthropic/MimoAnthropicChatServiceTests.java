package com.kaixuan.copilot_ollama_proxy.provider.mimo.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MimoAnthropicChatServiceTests {

        @Test
        void supportsModelOnlyWhenAnthropicFormatIsEnabled() {
                MimoAnthropicClient client = mock(MimoAnthropicClient.class);
                RuntimeProviderCatalog anthropicCatalog = () -> List
                                .of(new ProviderRuntimeConfiguration("mimo", "", "", "anthropic", List.of(new ProviderRuntimeModel("mimo-v2.5-pro", 0, false, false))));
                RuntimeProviderCatalog openAiCatalog = () -> List.of(new ProviderRuntimeConfiguration("mimo", "", "", "openai", List.of(new ProviderRuntimeModel("mimo-v2.5-pro", 0, false, false))));

                MimoAnthropicChatService anthropicService = new MimoAnthropicChatService(client, anthropicCatalog, new ObjectMapper());
                MimoAnthropicChatService openAiService = new MimoAnthropicChatService(client, openAiCatalog, new ObjectMapper());

                assertThat(anthropicService.supportsModel("mimo-v2.5-pro")).isTrue();
                assertThat(openAiService.supportsModel("mimo-v2.5-pro")).isFalse();
        }
}