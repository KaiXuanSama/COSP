package com.kaixuan.copilot_ollama_proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用程序入口。
 * 本项目是一个 Ollama API 兼容代理，核心作用是：
 * 让 GitHub Copilot 等客户端以为自己在和本地 Ollama 服务通信，
 * 实际上请求被转发到多个 AI 服务提供商的 API 接口。
 * 启动后监听 11434 端口（与 Ollama 默认端口一致），Copilot 无需任何额外配置即可连接。
 */
@SpringBootApplication
public class CopilotOllamaProxyApplication {

	public static void main(String[] args) {
		SpringApplication.run(CopilotOllamaProxyApplication.class, args);
	}

}
