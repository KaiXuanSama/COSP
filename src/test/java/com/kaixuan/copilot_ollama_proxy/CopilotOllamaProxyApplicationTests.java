package com.kaixuan.copilot_ollama_proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.kaixuan.copilot_ollama_proxy.proxy.OpenAiChatService;
import com.kaixuan.copilot_ollama_proxy.proxy.UpstreamChatService;

@SpringBootTest
class CopilotOllamaProxyApplicationTests {

	@Autowired
	private UpstreamChatService upstreamChatService;

	@Test
	void contextLoads() {
	}

	@Test
	void usesOpenAiUpstreamImplementationByDefault() {
		assertThat(upstreamChatService).isInstanceOf(OpenAiChatService.class);
	}

}
