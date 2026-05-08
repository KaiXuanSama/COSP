package com.kaixuan.copilot_ollama_proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaixuan.copilot_ollama_proxy.application.ollama.CompositeOllamaService;
import com.kaixuan.copilot_ollama_proxy.application.openai.CompositeUpstreamChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "admin.server.port:0")
class CopilotOllamaProxyApplicationTests {

    @Autowired
    private CompositeOllamaService ollamaService;

    @Autowired
    private CompositeUpstreamChatService upstreamChatService;

    @Test
    void contextLoads() {
    }

    @Test
    void compositeOllamaServiceRegistered() {
        assertThat(ollamaService).isNotNull();
    }

    @Test
    void compositeUpstreamChatServiceRegistered() {
        assertThat(upstreamChatService).isNotNull();
    }
}
