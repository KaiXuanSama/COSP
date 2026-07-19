package com.kaixuan.copilot_ollama_proxy.api.ollama;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.catalog.AvailableModel;
import com.kaixuan.copilot_ollama_proxy.application.catalog.ModelCatalogService;
import com.kaixuan.copilot_ollama_proxy.application.config.AppConfigService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;

import static org.mockito.BDDMockito.given;

@SpringBootTest(classes = CopilotOllamaProxyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OllamaApiControllerTests {

    @LocalServerPort
    private int port;

    @SuppressWarnings("removal") @MockBean
    private AppConfigService appConfigService;

    @SuppressWarnings("removal") @MockBean
    private ModelCatalogService modelCatalogService;

    @SuppressWarnings("removal") @MockBean
    private RuntimeProviderCatalog runtimeProviderCatalog;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(2))
                .build();
        given(appConfigService.findValue("fake_version")).willReturn("");
        given(modelCatalogService.listAvailableModels()).willReturn(List.of());
        given(runtimeProviderCatalog.getActiveProviders()).willReturn(List.of());
    }

    @Test
    void versionUsesPersistedFakeVersionWhenConfigured() {
        given(appConfigService.findValue("fake_version")).willReturn("0.9.1");

        webTestClient.get().uri("/api/version")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.version").isEqualTo("0.9.1");
    }

    @Test
    void tagsSerializesDatabaseCapabilitiesAndContextMetadata() {
        given(modelCatalogService.listAvailableModels()).willReturn(List.of(new AvailableModel(
                "demo", "Demo", "model-a", "[demo] model-a", true, true, 32768, 8192)));

        webTestClient.get().uri("/api/tags")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.models.length()").isEqualTo(1)
                .jsonPath("$.models[0].name").isEqualTo("[demo] model-a")
                .jsonPath("$.models[0].model").isEqualTo("[demo] model-a")
                .jsonPath("$.models[0].modified_at").exists()
                .jsonPath("$.models[0].details.format").isEqualTo("demo")
                .jsonPath("$.models[0].capabilities[0]").isEqualTo("completion")
                .jsonPath("$.models[0].capabilities[1]").isEqualTo("tools")
                .jsonPath("$.models[0].capabilities[2]").isEqualTo("vision")
                .jsonPath("$.models[0].context_length").isEqualTo(32768)
                .jsonPath("$.models[0].max_output_tokens").isEqualTo(8192);
    }

    @Test
    void showResolvesPrefixedModelAndUsesConfiguredCapabilitiesAndContext() {
        ProviderRuntimeConfiguration provider = new ProviderRuntimeConfiguration(
                "demo", "https://api.example/v1", "test-key", List.of(
                new ProviderRuntimeModel("model-a", 32768, true, true, "Medium")));
        given(runtimeProviderCatalog.getActiveProviders()).willReturn(List.of(provider));
        given(runtimeProviderCatalog.getActiveProvider("demo")).willReturn(provider);

        webTestClient.post().uri("/api/show")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"[demo] model-a\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.parameters").value(value -> org.assertj.core.api.Assertions.assertThat(value)
                        .asString().contains("num_ctx 32768"))
                .jsonPath("$.capabilities[0]").isEqualTo("completion")
                .jsonPath("$.capabilities[1]").isEqualTo("tools")
                .jsonPath("$.capabilities[2]").isEqualTo("vision")
                .jsonPath("$.model_info['demo.context_length']").isEqualTo(32768)
                .jsonPath("$.model_info['demo.embedding_length']").isEqualTo(8192);
    }

    @Test
    void emptyCatalogReturnsNanoLlmWithCompatibleContextForTagsAndShow() {
        webTestClient.get().uri("/api/tags")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.models.length()").isEqualTo(1)
                .jsonPath("$.models[0].name").isEqualTo("nano_llm")
                .jsonPath("$.models[0].context_length").isEqualTo(8192)
                .jsonPath("$.models[0].max_output_tokens").isEqualTo(4096)
                .jsonPath("$.models[0].capabilities[1]").isEqualTo("tools");

        webTestClient.post().uri("/api/show")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"nano_llm\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.parameters").value(value -> org.assertj.core.api.Assertions.assertThat(value)
                        .asString().contains("num_ctx 8192"))
                .jsonPath("$.model_info['nano.context_length']").isEqualTo(8192)
                .jsonPath("$.capabilities[0]").isEqualTo("completion")
                .jsonPath("$.capabilities[1]").isEqualTo("tools");
    }
}