package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.application.openai.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.DatabaseRuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericOpenAiChatService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderRequestBodyTransformationIntegrationTests {

    private static final String RULES = """
            {"version":1,"rules":[{
              "id":"rewrite-temperature","order":0,"field":"temperature","array":false,
              "conditional":false,"conditionMode":"all","conditions":[],
              "operations":[{"type":"set_value","value":0.2}]},
              {"id":"remove-reasoning","order":1,"field":"reasoning_effort","array":false,
              "conditional":false,"conditionMode":"all","conditions":[],
              "operations":[{"type":"delete"}]}
            ]}
            """;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer upstream;
    private final AtomicReference<String> capturedRequest = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> capturedRequestHeaders = new AtomicReference<>();

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name, String.join(", ", values)));
            capturedRequestHeaders.set(headers);
            capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    void savedRuleSetIsLoadedAtRuntimeAndAppliedToTheUpstreamRequest() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("request-body-transform.db"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createTables(jdbcTemplate);

        ProviderApiKeyRepository apiKeyRepository = mock(ProviderApiKeyRepository.class);
        ProviderConfigRepository providerConfigRepository = new ProviderConfigRepository(jdbcTemplate, apiKeyRepository);
        ProviderRequestTransformRepository transformRepository = new ProviderRequestTransformRepository(jdbcTemplate);
        ProviderRequestTransformService transformService = new ProviderRequestTransformService(
                providerConfigRepository, transformRepository, objectMapper);
        int providerId = transformService.createProvider("alpha", "Alpha", upstreamBaseUrl(), "[]",
                "[\"base\"]", "{\"model\":\"<string>\"}", RULES);
        providerConfigRepository.saveModels(providerId, List.of(Map.of(
                "modelName", "model-a", "enabled", true, "contextSize", 8192,
                "maxOutputTokens", 4096, "capsTools", false, "capsVision", false,
                "reasoningEffort", "Medium")));
        when(apiKeyRepository.resolveActiveApiKey(providerId)).thenReturn("test-api-key");

        DatabaseRuntimeProviderCatalog catalog = new DatabaseRuntimeProviderCatalog(
                providerConfigRepository, apiKeyRepository, transformRepository);
        GenericOpenAiChatService genericChatService = new GenericOpenAiChatService(
                objectMapper, new ProviderRequestHeaderService(objectMapper));
        genericChatService.setWebClientBuilder(WebClient.builder());
        AtomicReference<Map<String, String>> loggedRequestHeaders = new AtomicReference<>();
        ApiCallLogService callLogService = mock(ApiCallLogService.class);
        doAnswer(invocation -> {
            Map<String, String> headers = invocation.getArgument(2);
            loggedRequestHeaders.set(new LinkedHashMap<>(headers));
            return null;
        }).when(callLogService).saveNonStream(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        genericChatService.setApiCallLog(callLogService);
        ChatCompletionService chatCompletionService = new ChatCompletionService(
                new ProviderRouteResolver(catalog), genericChatService);

        HttpHeaders downstreamHeaders = new HttpHeaders();
        downstreamHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer downstream-token");
        downstreamHeaders.set(HttpHeaders.COOKIE, "session=downstream-cookie");
        downstreamHeaders.set(HttpHeaders.USER_AGENT, "DownstreamClient/1.0");
        downstreamHeaders.set("X-Trace-Id", "trace-123");
        downstreamHeaders.set(HttpHeaders.HOST, "localhost:11434");
        downstreamHeaders.set(HttpHeaders.CONTENT_LENGTH, "99999");
        downstreamHeaders.set(HttpHeaders.CONNECTION, "keep-alive");

        String response = chatCompletionService.chatCompletion(Map.of(
                "model", "[alpha] model-a",
                "messages", List.of(Map.of("role", "user", "content", "hello")),
                "temperature", 0.8,
                "reasoning_effort", "high"), "[alpha] model-a", downstreamHeaders, null).block(Duration.ofSeconds(3));

        assertThat(response).contains("chatcmpl-test");
        JsonNode upstreamBody = objectMapper.readTree(capturedRequest.get());
        assertThat(upstreamBody.path("model").asText()).isEqualTo("model-a");
        assertThat(upstreamBody.path("stream").asBoolean()).isFalse();
        assertThat(upstreamBody.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(upstreamBody.has("reasoning_effort")).isFalse();
        assertThat(upstreamBody.path("messages").get(0).path("content").asText()).isEqualTo("hello");
        assertThat(capturedRequestHeaders.get().get("User-agent")).isEqualTo("DownstreamClient/1.0");
        assertThat(loggedRequestHeaders.get()).containsEntry(
                "User-Agent", capturedRequestHeaders.get().get("User-agent"));
        assertThat(loggedRequestHeaders.get()).containsEntry("Authorization", "****");
        assertThat(loggedRequestHeaders.get()).containsKeys("Host", "Content-Type", "Accept");
        assertThat(loggedRequestHeaders.get().containsKey("Content-Length")
                || loggedRequestHeaders.get().containsKey("Transfer-Encoding")).isTrue();
        assertThat(capturedRequestHeaders.get()).containsEntry("X-trace-id", "trace-123");
        assertThat(capturedRequestHeaders.get()).containsEntry("Cookie", "session=downstream-cookie");
        assertThat(capturedRequestHeaders.get()).containsEntry("Authorization", "Bearer test-api-key");
        assertThat(capturedRequestHeaders.get()).containsEntry("User-agent", "DownstreamClient/1.0");
        assertThat(capturedRequestHeaders.get().get("Host")).doesNotContain("11434");
        assertThat(loggedRequestHeaders.get()).containsEntry("X-Trace-Id", "trace-123");
        assertThat(loggedRequestHeaders.get()).containsEntry("Cookie", "****");
        assertThat(loggedRequestHeaders.get()).containsEntry("User-Agent", "DownstreamClient/1.0");
    }

    private String upstreamBaseUrl() {
        return "http://localhost:" + upstream.getAddress().getPort() + "/v1";
    }

    private void createTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("CREATE TABLE provider_config ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT NOT NULL UNIQUE, "
                + "display_name TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 0, "
                + "base_url TEXT NOT NULL DEFAULT '', updated_at TEXT)");
        jdbcTemplate.execute("CREATE TABLE provider_model ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id INTEGER NOT NULL, model_name TEXT NOT NULL, "
                + "enabled INTEGER NOT NULL DEFAULT 0, context_size INTEGER NOT NULL DEFAULT 8192, "
                + "max_output_tokens INTEGER NOT NULL DEFAULT 4096, caps_tools INTEGER NOT NULL DEFAULT 0, "
                + "caps_vision INTEGER NOT NULL DEFAULT 0, reasoning_effort TEXT NOT NULL DEFAULT 'Medium', "
                + "sort_order INTEGER NOT NULL DEFAULT 0)");
        jdbcTemplate.execute("CREATE TABLE provider_request_transform ("
                + "provider_id INTEGER PRIMARY KEY, header_rules_version INTEGER NOT NULL, "
                + "header_rules_json TEXT NOT NULL, body_template_keys_json TEXT NOT NULL, "
                + "body_preview_json TEXT NOT NULL, body_rules_version INTEGER NOT NULL, "
                + "body_rules_json TEXT NOT NULL, created_at TEXT, updated_at TEXT)");
    }
}