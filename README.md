# Copilot Ollama Proxy

一个基于 Spring Boot 实现的 Ollama API 代理/中转服务，让 Copilot 或其他 Ollama 客户端能够无缝对接多种 AI 服务提供商。

## 项目简介

Copilot Ollama Proxy 是一个协议适配层服务，它模拟 Ollama API 接口，将 Ollama 格式的请求转换为不同 AI 服务提供商（如 MiMo、LongCat）的 API 格式。通过这个代理，开发者可以使用 Ollama 客户端工具来访问各种 AI 服务，实现协议兼容和无缝切换。

## 现有支持第三方模型供应商

| 供应商 | 说明 | 默认模型 |
|--------|------|----------|
| **MiMo** | 小米 AI 服务平台 | `mimo-v2.5-pro` |
| **LongCat** | 美团 LongCat 系列模型 | `LongCat-Flash-Chat` |

## 核心功能

### 🎯 协议兼容
- 完全模拟 Ollama API 接口规范
- 支持 Ollama 客户端无缝对接
- 兼容 Copilot 等 Ollama 兼容工具

### 🔌 多提供商支持
- **MiMo**：小米 AI 服务平台
- **LongCat**：美团 LongCat 系列模型
- 可扩展的提供商架构，便于添加新的 AI 服务

### 🔄 智能转译
- 自动将 Ollama 请求格式转换为目标提供商格式
- 支持模型映射和参数适配
- 处理不同提供商之间的 API 差异

### 📡 流式输出
- 支持 SSE（Server-Sent Events）流式响应
- 实时推送 AI 生成内容
- 优化大模型响应体验

## 技术架构

### 技术栈
- **编程语言**：Java 21
- **开发框架**：Spring Boot 3.5.14
- **Web 框架**：Spring Web + WebFlux（响应式编程）
- **构建工具**：Maven
- **默认端口**：11434（Ollama 默认端口）

### 项目结构
```
src/main/java/com/kaixuan/copilot_ollama_proxy/
├── api/           # API 接口层 - 处理 HTTP 请求和响应
├── application/   # 应用服务层 - 业务逻辑处理
├── protocol/      # 协议处理 - Ollama 协议解析和构建
├── provider/      # 提供商实现 - 不同 AI 服务的具体实现
└── infrastructure # 基础设施 - 配置、工具类等
```

## 快速开始

### 环境要求
- Java 21 或更高版本
- Maven 3.6+

### 本地运行

1. **克隆项目**
```bash
git clone <repository-url>
cd copilot-ollama-proxy
```

2. **配置 API Key**
编辑 `src/main/resources/application.yml`，配置相应的 API Key：

```yaml
mimo:
  api-key: ${MIMO_API_KEY:your-mimo-api-key}

longcat:
  api-key: ${LONGCAT_API_KEY:your-longcat-api-key}
```

3. **启动服务**
```bash
./mvnw spring-boot:run
```

或者使用 Maven 打包后运行：
```bash
./mvnw clean package
java -jar target/copilot-ollama-proxy-0.0.1-SNAPSHOT.jar
```

4. **测试服务**
```bash
curl http://localhost:11434/api/tags
```

## 配置说明

### 核心配置

编辑 `application.yml` 或使用环境变量进行配置：

```yaml
# 服务器配置
server:
  port: ${SERVER_PORT:11434}  # 服务端口，默认 11434

# 代理配置
proxy:
  provider: ${PROXY_PROVIDER:longcat}  # 提供商：mimo 或 longcat
  upstream-chat-service: ${PROXY_UPSTREAM_CHAT_SERVICE:openai}  # 上游服务类型

# Ollama 伪装配置
ollama:
  version: ${OLLAMA_VERSION:0.6.4}  # 模拟的 Ollama 版本

# MiMo 配置
mimo:
  api-key: ${MIMO_API_KEY:}  # MiMo API Key
  base-url: ${MIMO_BASE_URL:https://token-plan-cn.xiaomimimo.com/anthropic}
  default-model: ${MIMO_DEFAULT_MODEL:mimo-v2.5-pro}

# LongCat 配置
longcat:
  api-key: ${LONGCAT_API_KEY:ak_2Up1S46T504s45t7lE36I0EJ1km6h}
  base-url: ${LONGCAT_BASE_URL:https://api.longcat.chat}
  default-model: ${LONGCAT_DEFAULT_MODEL:LongCat-Flash-Chat}
```

### 环境变量配置

所有配置都支持通过环境变量覆盖：

| 环境变量 | 说明 | 默认值 |
|---------|------|--------|
| `SERVER_PORT` | 服务端口 | `11434` |
| `PROXY_PROVIDER` | 提供商 (mimo/longcat) | `longcat` |
| `PROXY_UPSTREAM_CHAT_SERVICE` | 上游服务类型 | `openai` |
| `OLLAMA_VERSION` | 模拟 Ollama 版本 | `0.6.4` |
| `MIMO_API_KEY` | MiMo API Key | - |
| `MIMO_BASE_URL` | MiMo 基础 URL | `https://token-plan-cn.xiaomimimo.com/anthropic` |
| `MIMO_DEFAULT_MODEL` | MiMo 默认模型 | `mimo-v2.5-pro` |
| `LONGCAT_API_KEY` | LongCat API Key | `ak_2Up1S46T504s45t7lE36I0EJ1km6h` |
| `LONGCAT_BASE_URL` | LongCat 基础 URL | `https://api.longcat.chat` |
| `LONGCAT_DEFAULT_MODEL` | LongCat 默认模型 | `LongCat-Flash-Chat` |

## API 接口

### 支持的 Ollama API 端点

- `GET /api/tags` - 获取可用模型列表
- `POST /api/generate` - 生成文本内容
- `POST /api/chat` - 聊天对话
- `POST /api/embeddings` - 生成向量嵌入
- `GET /api/version` - 获取 Ollama 版本信息

### 使用示例

#### 1. 获取模型列表
```bash
curl http://localhost:11434/api/tags
```

#### 2. 生成文本
```bash
curl http://localhost:11434/api/generate \
  -H "Content-Type: application/json" \
  -d '{
    "model": "LongCat-Flash-Chat",
    "prompt": "你好，请介绍一下你自己",
    "stream": false
  }'
```

#### 3. 聊天对话
```bash
curl http://localhost:11434/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "model": "LongCat-Flash-Chat",
    "messages": [
      {"role": "user", "content": "你好"}
    ],
    "stream": false
  }'
```

#### 4. 流式输出
```bash
curl http://localhost:11434/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "model": "LongCat-Flash-Chat",
    "messages": [
      {"role": "user", "content": "写一首诗"}
    ],
    "stream": true
  }'
```

## 开发指南

### 添加新的提供商

1. 实现 `Provider` 接口
2. 实现请求转换逻辑
3. 实现响应处理逻辑
4. 在配置中添加新提供商信息

### 扩展 API 支持

1. 在 `api/` 层添加新的控制器
2. 实现协议解析逻辑
3. 添加对应的请求/响应模型

## 测试

### 运行测试
```bash
./mvnw test
```

### 集成测试
```bash
./mvnw verify
```

## 性能优化

### 配置建议

1. **连接池配置** - 优化上游 API 连接
2. **缓存配置** - 启用模型列表缓存
3. **日志级别** - 生产环境建议使用 INFO 级别

### 监控

服务启动后可以访问以下端点：
- `/actuator/health` - 健康检查
- `/actuator/info` - 应用信息

## 常见问题

### 1. API Key 配置
确保在环境变量或配置文件中正确设置 API Key。

### 2. 端口冲突
如果 11434 端口被占用，可以通过环境变量修改：
```bash
export SERVER_PORT=8080
```

### 3. 模型不可用
检查提供商配置和 API Key 是否正确，以及模型名称是否有效。

## 贡献指南

欢迎提交 Issue 和 Pull Request 来改进这个项目！

## 许可证

本项目采用 MIT 许可证。

## 联系方式

如有问题或建议，请提交 Issue 或联系项目维护者。

---

**注意**：本项目仍在开发中，API 可能会发生变化。请密切关注版本更新。