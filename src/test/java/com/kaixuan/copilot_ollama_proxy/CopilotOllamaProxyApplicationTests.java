package com.kaixuan.copilot_ollama_proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaixuan.copilot_ollama_proxy.application.ollama.ModelDiscoveryService;
import com.kaixuan.copilot_ollama_proxy.application.openai.ChatCompletionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CopilotOllamaProxyApplicationTests {

    @Autowired
    private ModelDiscoveryService modelDiscoveryService;

    @Autowired
    private ChatCompletionService chatCompletionService;

    @Test
    void contextLoads() {
    }

    @Test
    void modelDiscoveryServiceRegistered() {
        assertThat(modelDiscoveryService).isNotNull();
    }

    @Test
    void chatCompletionServiceRegistered() {
        assertThat(chatCompletionService).isNotNull();
    }
}
