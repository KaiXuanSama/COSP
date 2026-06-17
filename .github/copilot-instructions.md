# GitHub Copilot Instructions for COSP

本文件为 GitHub Copilot 提供项目特定的指令，帮助理解代码结构和开发约定。

## 项目概述

COSP (Copilot Ollama SpringBoot Proxy) 是一个协议适配层服务，将 Copilot 的请求转换为不同 AI 服务提供商的 API 格式。

## 核心原则

1. **双协议架构**：Copilot 通过 Ollama 协议发现模型，通过 OpenAI 协议进行聊天
2. **Provider 必须包含四个组件**：Transport Client、Protocol Converter、Stream Translator、Service
3. **能力声明从数据库读取**：禁止硬编码能力列表

## 代码审查要点

修改代码时请参考 [模型兼容性问题](../docs/MODEL_COMPATIBILITY.md) 中的已知问题。

### 关键陷阱

- **工具调用参数**：`OllamaStreamTranslator` 中增量 arguments 需要作为字符串累积
- **400 错误重试**：当前对所有 400 错误重试（含认证失败），需要改进
- **SSE 流式响应**：`ResponseLoggingFilter` 可能导致 OOM
- **SQLite 连接池**：建议使用 `maximum-pool-size: 1`
- **DeepSeek reasoning_content**：多轮工具调用需要缓存 `reasoning_content` 字段

## 开发规则

### 新增 Provider

使用 [新增供应商指南](./skills/cosp-add-provider-skill/SKILL.md) 或 [中文版](./skills/cosp-add-provider-skill/SKILL_ZH.md)。

### 测试规范

- 集成测试使用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `WebTestClient`
- 模拟依赖使用 `@MockBean`
- 测试方法名使用 camelCase 长名

### 前端开发

- 所有 API 调用通过 `frontend/src/api/index.ts` 封装
- 状态管理使用 Pinia
- UI 组件使用 Naive UI
- 样式参考 `frontend/src/styles/_variables.scss`

## 重要文档

- [AGENTS.md](../AGENTS.md) — 完整项目指南
- [README.md](../README.md) — 项目介绍和快速开始
- [编码规范](../copilot-ollama-proxy-springboot.wiki/Guides/Coding-Standards.md)
- [架构文档](../copilot-ollama-proxy-springboot.wiki/Architecture.md)
- [模型兼容性](../docs/MODEL_COMPATIBILITY.md) — 已知问题和解决方案
- [UI 设计系统](../copilot-ollama-proxy-springboot.wiki/Guides/UI-Design-System.md)
