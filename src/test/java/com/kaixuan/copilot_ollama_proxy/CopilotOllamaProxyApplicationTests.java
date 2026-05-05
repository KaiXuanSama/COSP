package com.kaixuan.copilot_ollama_proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.kaixuan.copilot_ollama_proxy.application.openai.UpstreamChatService;
import com.kaixuan.copilot_ollama_proxy.provider.mimo.openai.MimoOpenAiChatService;

@SpringBootTest
class CopilotOllamaProxyApplicationTests {

	@Autowired
	private UpstreamChatService upstreamChatService;

	@Test
	void contextLoads() {
	}

	@Test
	void usesOpenAiUpstreamImplementationByDefault() {
		assertThat(upstreamChatService).isInstanceOf(MimoOpenAiChatService.class);
	}

}
