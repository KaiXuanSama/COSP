<p align="center">
  <img src="docs/images/cosp-banner.png" alt="COSP Banner" width="60%">
</p>

<h1 align="center">Copilot Ollama SpringBoot Proxy (COSP)</h1>

<p align="center">
  一个基于 Spring Boot 实现的 Ollama API 代理/中转服务，专为 GitHub Copilot 设计，使其能够无缝对接多种 AI 服务提供商。
</p>

## 项目简介

COSP（Copilot Ollama SpringBoot Proxy）是一个专为 GitHub Copilot 设计的协议适配层服务。它模拟 Ollama API 接口，将 Copilot 的请求转换为不同 AI 服务提供商的 API 格式，让 Copilot 能够直接使用多种第三方 AI 模型。

## 现有支持第三方模型供应商

| 供应商 | 说明 | 默认模型 | 支持格式 |
|--------|------|----------|---------|
| **DeepSeek** | DeepSeek 官方 API | `deepseek-v4-flash` | OpenAI |
| **MiMo** | 小米 AI 服务平台 | `mimo-v2.5-pro` | OpenAI / Anthropic |
| **SenseNova** | 商汤 AI 服务平台 | `sensenova-6.7-flash-lite` | OpenAI |
| **LongCat** | 美团 LongCat 系列模型 | `LongCat-Flash-Chat` | OpenAI |

## 核心功能

### 🎯 专为 Copilot 优化
- 完全模拟 Ollama API 接口规范，让 Copilot 原生支持
- 无需额外配置，Copilot 开箱即用
- 兼容 Copilot 的模型切换与流式输出

### 🔌 多提供商支持
- **DeepSeek**：DeepSeek 官方 API
- **MiMo**：小米 AI 服务平台，支持 OpenAI 和 Anthropic 双协议
- **SenseNova**：商汤 AI 服务平台
- **LongCat**：美团 LongCat 系列模型
- 可扩展的提供商架构，便于添加新的 AI 服务

### 🔄 智能转译
- 自动将 Copilot 的 Ollama 格式请求转换为目标提供商格式
- 支持模型映射和参数适配
- 处理不同提供商之间的 API 差异

### 📡 流式输出
- 支持 SSE（Server-Sent Events）流式响应
- 实时推送 AI 生成内容
- 优化大模型响应体验

### 🖥️ 管理后台
- 基于 Thymeleaf 的 Web 管理界面
- 可视化配置服务商 API Key、Base URL、模型列表
- 实时 API 调用统计与热力图
- 账号管理

## 技术架构

### 技术栈
- **编程语言**：Java 21
- **开发框架**：Spring Boot 3.5.14
- **Web 框架**：Spring Web + WebFlux（响应式编程）
- **模板引擎**：Thymeleaf 3.1
- **安全框架**：Spring Security
- **数据库**：SQLite（嵌入式，无需额外安装）
- **构建工具**：Maven

### 端口说明
| 端口 | 用途 | 默认值 | 环境变量 |
|------|------|--------|---------|
| 11434 | 主端口 — Ollama API 代理 | `11434` | `SERVER_PORT` |
| 80 | 管理端口 — Web 管理后台 | `80` | `ADMIN_SERVER_PORT` |

### 项目结构
```
src/main/java/com/kaixuan/copilot_ollama_proxy/
├── api/                    # API 接口层
│   ├── ollama/             #   Ollama 兼容 API（/api/*）
│   └── openai/             #   OpenAI 兼容 API（/v1/*）
├── application/            # 应用服务层
│   └── ollama/             #   Ollama 服务编排与路由
├── infrastructure/         # 基础设施
│   ├── config/             #   配置（端口、安全、过滤器）
│   ├── persistence/        #   数据库访问（Repository）
│   └── web/logging/        #   请求/响应日志
├── protocol/               # 协议处理
│   └── ollama/             #   Ollama 协议模型与序列化
└── provider/               # 提供商实现
    ├── deepseek/           #   DeepSeek（OpenAI 协议）
    ├── longcat/            #   LongCat（OpenAI 协议）
    ├── mimo/               #   MiMo（OpenAI + Anthropic 协议）
    └── sensenova/          #   SenseNova（OpenAI 协议）
```

## 快速开始

### 环境要求
- Java 21 或更高版本
- Maven 3.6+

### 本地运行

1. **克隆项目**
```bash
git clone https://github.com/KaiXuanSama/COSP.git
cd copilot-ollama-proxy
```

2. **启动服务**
```bash
./mvnw spring-boot:run
```

或者使用 Maven 打包后运行：
```bash
./mvnw clean package
java -jar target/copilot-ollama-proxy-0.0.1-SNAPSHOT.jar
```

3. **登录管理后台**
打开浏览器访问 `http://localhost:80`（或你配置的 `ADMIN_SERVER_PORT`），使用默认账号登录：
- 用户名：`root`
- 密码：`root`

4. **配置服务商**
在管理后台的「配置」页面，为各服务商填写 API Key 和 Base URL，添加模型后即可使用。

5. **配置 Copilot**
在 VS Code 中设置 Copilot 使用 Ollama 模式，指向本服务地址即可。

### 测试服务
```bash
# 获取模型列表
curl http://localhost:11434/api/tags

# 获取版本信息
curl http://localhost:11434/api/version
```

## 配置说明

### 核心配置

编辑 `application.yml` 或使用环境变量进行配置：

```yaml
# 服务器配置
server:
  port: ${SERVER_PORT:11434}          # 主服务端口，默认 11434

# 管理后台配置
admin:
  username: ${ADMIN_USERNAME:root}     # 管理后台用户名
  password: ${ADMIN_PASSWORD:root}     # 管理后台密码
  server:
    port: ${ADMIN_SERVER_PORT:80}      # 管理端口，默认 80

# Ollama 伪装配置
ollama:
  version: ${OLLAMA_VERSION:0.6.4}     # 模拟的 Ollama 版本号

# 数据库
spring:
  datasource:
    url: ${SQLITE_URL:jdbc:sqlite:./admin.db}
```

> **注意**：服务商配置（API Key、Base URL、模型列表等）已迁移至管理后台可视化配置，不再需要手写 application.yml。首次启动后登录管理后台即可完成配置。

### 环境变量配置

| 环境变量 | 说明 | 默认值 |
|---------|------|--------|
| `SERVER_PORT` | 主服务端口 | `11434` |
| `ADMIN_SERVER_PORT` | 管理后台端口 | `80` |
| `ADMIN_USERNAME` | 管理后台用户名 | `root` |
| `ADMIN_PASSWORD` | 管理后台密码 | `root` |
| `OLLAMA_VERSION` | 模拟 Ollama 版本 | `0.6.4` |
| `SQLITE_URL` | SQLite 数据库路径 | `jdbc:sqlite:./admin.db` |

## API 接口

### Ollama 兼容 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/tags` | 获取可用模型列表（聚合所有已启用服务商的模型） |
| `POST` | `/api/show` | 获取指定模型的详细信息 |
| `POST` | `/api/chat` | 聊天对话（支持流式 NDJSON 和非流式） |
| `GET` | `/api/version` | 获取 Ollama 版本信息 |

### OpenAI 兼容 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/v1/chat/completions` | OpenAI 格式聊天完成（支持流式 SSE 和非流式） |

### 使用示例

#### 1. 获取模型列表
```bash
curl http://localhost:11434/api/tags
```

#### 2. 聊天对话
```bash
curl http://localhost:11434/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-v4-flash",
    "messages": [
      {"role": "user", "content": "你好"}
    ],
    "stream": false
  }'
```

#### 3. 流式输出
```bash
curl http://localhost:11434/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-v4-flash",
    "messages": [
      {"role": "user", "content": "你好"}
    ],
    "stream": true
  }'
```

## 管理后台

启动服务后，访问 `http://localhost:80`（或自定义的管理端口）即可进入管理后台。

### 页面功能

| 页面 | 路径 | 功能 |
|------|------|------|
| 概览 | `/config` | API 调用统计、热力图 |
| 配置 | `/config/settings` | 服务商配置（启用/禁用、API Key、Base URL、模型列表） |
| 账号 | `/config/account` | 修改用户名和密码 |

### 默认登录账号
- 用户名：`root`
- 密码：`root`

## 数据库

项目使用 SQLite 嵌入式数据库，无需额外安装数据库服务。数据库文件默认生成在项目根目录的 `admin.db`。

### 数据表

| 表名 | 说明 |
|------|------|
| `provider_config` | 服务商配置（API Key、Base URL、API 格式等） |
| `provider_model` | 服务商模型列表（模型名、上下文大小、能力标记） |
| `app_config` | 应用运行配置（键值对） |
| `api_usage_daily` | API 调用按天汇总统计 |
| `users` | 管理后台用户 |
| `authorities` | 用户权限 |

## 测试

### 运行测试
```bash
./mvnw test
```

## 常见问题

### 1. 端口冲突
如果默认端口被占用，可以通过环境变量修改：
```bash
set SERVER_PORT=8080       # Windows PowerShell
$env:SERVER_PORT=8080      # Windows PowerShell
export SERVER_PORT=8080    # Linux / macOS
```

### 2. 管理后台无法访问
确保 `ADMIN_SERVER_PORT`（默认 80）未被其他程序占用。

### 3. 模型不可用
在管理后台「配置」页面检查服务商的 API Key 和 Base URL 是否正确填写，以及模型列表是否已添加。

## 许可证

本项目采用 MIT 许可证。

---

**注意**：本项目仍在开发中，API 可能会发生变化。请密切关注版本更新。