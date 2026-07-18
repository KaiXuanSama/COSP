CREATE TABLE IF NOT EXISTS users (
    username VARCHAR(50) PRIMARY KEY,
    password VARCHAR(100) NOT NULL,
    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1))
);

CREATE TABLE IF NOT EXISTS authorities (
    username VARCHAR(50) NOT NULL,
    authority VARCHAR(50) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ix_authorities_username_authority
    ON authorities (username, authority);

-- ==================== 服务商配置表 ====================

CREATE TABLE IF NOT EXISTS provider_config (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_key     VARCHAR(30)  NOT NULL UNIQUE,   -- 服务商标识，如 longcat / mimo
    enabled          INTEGER      NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)), -- 是否启用（0=禁用，1=启用）
    base_url         TEXT         NOT NULL DEFAULT '', -- API 基础 URL
    api_key          TEXT         NOT NULL DEFAULT '', -- 【V4 后废弃】API Key 已迁移到 provider_api_key 表，此列保留但恒为 '[]'
    active_api_key_index INTEGER  NOT NULL DEFAULT 0 CHECK (active_api_key_index >= 0), -- 【V4 后废弃】激活状态改由 provider_api_key.is_active 表示，此列恒为 0
    api_format       VARCHAR(20)  NOT NULL DEFAULT 'openai', -- API 格式（仅支持 openai）
    custom_transforms TEXT        NOT NULL DEFAULT '{}', -- 自定义供应商的请求转换配置（JSON）
    updated_at       TEXT         NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

-- ==================== 服务商模型表 ====================

CREATE TABLE IF NOT EXISTS provider_model (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_id     INTEGER      NOT NULL,            -- 关联 provider_config.id
    model_name      VARCHAR(100) NOT NULL,            -- 模型名称
    enabled         INTEGER      NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)), -- 是否启用（0=禁用，1=启用，默认启用）
    context_size    INTEGER      NOT NULL DEFAULT 0 CHECK (context_size >= 0), -- 上下文大小（token 数）
    max_output_tokens INTEGER    NOT NULL DEFAULT 128000 CHECK (max_output_tokens >= 0), -- 最大输出 token 数
    caps_tools      INTEGER      NOT NULL DEFAULT 0 CHECK (caps_tools IN (0, 1)), -- 是否支持工具调用（0=否，1=是）
    caps_vision     INTEGER      NOT NULL DEFAULT 0 CHECK (caps_vision IN (0, 1)), -- 是否支持视觉（0=否，1=是）
    reasoning_effort TEXT        NOT NULL DEFAULT 'Medium', -- 思考深度（逗号分隔，如 Low,Medium）
    sort_order      INTEGER      NOT NULL DEFAULT 0 CHECK (sort_order >= 0), -- 排序权重
    FOREIGN KEY (provider_id) REFERENCES provider_config(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_model_provider_name
    ON provider_model(provider_id, model_name);

-- ==================== 服务商 API Key 表（V4 加密存储） ====================
-- 每个供应商可配置多个 API Key，使用 AES-256-GCM 加密存储。
-- 主密钥来自环境变量 COSP_MASTER_KEY，未配置时服务拒绝启动。

CREATE TABLE IF NOT EXISTS provider_api_key (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    key_uuid           TEXT    NOT NULL UNIQUE,          -- 不可变 UUID，供前端回写与未来密钥轮换使用
    provider_id        INTEGER NOT NULL,                 -- 关联 provider_config.id
    key_name           TEXT    NOT NULL DEFAULT '',      -- Key 名称（明文，便于识别）
    encrypted_api_key  TEXT    NOT NULL,                 -- AES-256-GCM 密文（Base64）
    nonce              TEXT    NOT NULL,                 -- 每条记录独立的随机 nonce（Base64）
    encryption_version INTEGER NOT NULL DEFAULT 1 CHECK (encryption_version >= 1), -- 加密格式版本
    is_active          INTEGER NOT NULL DEFAULT 0 CHECK (is_active IN (0, 1)), -- 是否为当前激活 Key
    sort_order         INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0), -- 展示顺序
    created_at         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    updated_at         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    UNIQUE (provider_id, key_name),
    FOREIGN KEY (provider_id) REFERENCES provider_config(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_provider_api_key_provider_id ON provider_api_key(provider_id);
-- 部分唯一索引：每个供应商最多一个激活 Key
CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_api_key_active
    ON provider_api_key(provider_id) WHERE is_active = 1;

-- ==================== 供应商请求转换配置表（V5） ====================
-- 请求头运行时读取本表的 header_rules_json；provider_config.custom_transforms 中的旧请求头仅为迁移期双写副本。
-- 请求体运行时读取本表 body_rules_json；provider_config.custom_transforms 中的旧 body_transforms 仅为迁移期兼容副本。

CREATE TABLE IF NOT EXISTS provider_request_transform (
    provider_id             INTEGER PRIMARY KEY,       -- 与供应商一对一关联
    header_rules_version    INTEGER NOT NULL DEFAULT 1 CHECK (header_rules_version >= 1),
    header_rules_json       TEXT    NOT NULL DEFAULT '[]' CHECK (json_valid(header_rules_json)),
    body_template_keys_json TEXT    NOT NULL DEFAULT '["custom"]' CHECK (json_valid(body_template_keys_json)),
    body_preview_json       TEXT    NOT NULL DEFAULT '{}' CHECK (json_valid(body_preview_json)),
    body_rules_version      INTEGER NOT NULL DEFAULT 1 CHECK (body_rules_version >= 1),
    body_rules_json         TEXT    NOT NULL DEFAULT '{"version":1,"rules":[]}' CHECK (json_valid(body_rules_json)),
    created_at              TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    updated_at              TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    FOREIGN KEY (provider_id) REFERENCES provider_config(id) ON DELETE CASCADE
);

-- ==================== 应用运行配置表（键值对） ====================

CREATE TABLE IF NOT EXISTS app_config (
    config_key   VARCHAR(50) NOT NULL PRIMARY KEY,  -- 配置键，如 fake_version
    config_value TEXT        NOT NULL DEFAULT '',   -- 配置值
    updated_at   TEXT        NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

-- 默认运行配置
INSERT OR IGNORE INTO app_config (config_key, config_value) VALUES ('fake_version', '0.6.4');

-- ==================== API 调用按天汇总表 ====================

-- ==================== 工具调用思考链缓存表 ====================
-- 用于存储 DeepSeek 等模型在工具调用时的 reasoning_content，
-- 以便在下一轮请求中回填，满足上游对工具调用历史的校验要求。

CREATE TABLE IF NOT EXISTS reasoning_cache (
    tool_call_id      TEXT    NOT NULL PRIMARY KEY,   -- 工具调用 ID（messages[x].tool_calls[y].id）
    reasoning_content TEXT    NOT NULL,                -- 本次工具调用对应的思考链文本
    created_at        TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_reasoning_cache_created_at ON reasoning_cache(created_at);

-- ==================== API 调用按天汇总表 ====================

CREATE TABLE IF NOT EXISTS api_usage_daily (
    usage_date    TEXT    NOT NULL PRIMARY KEY,   -- 日期，格式 YYYY-MM-DD
    call_count    INTEGER NOT NULL DEFAULT 0 CHECK (call_count >= 0), -- 当天调用次数
    input_tokens  INTEGER NOT NULL DEFAULT 0 CHECK (input_tokens >= 0), -- 当天输入 token 总量
    output_tokens INTEGER NOT NULL DEFAULT 0 CHECK (output_tokens >= 0), -- 当天输出 token 总量
    updated_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

-- ==================== API 调用详细日志表 ====================
-- 记录每次调用上游供应商 API 的完整请求/响应信息

CREATE TABLE IF NOT EXISTS api_call_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_key    VARCHAR(30),                   -- 服务商标识，如 deepseek / mimo
    model_name      VARCHAR(100),                  -- 模型名称
    is_stream       INTEGER      NOT NULL DEFAULT 0 CHECK (is_stream IN (0, 1)), -- 是否流式（0=否，1=是）
    status_code     INTEGER,                        -- HTTP 响应状态码
    request_headers TEXT,                           -- JSON 格式的请求头
    request_body    TEXT,                           -- JSON 格式的请求体
    response_headers TEXT,                          -- JSON 格式的响应头
    response_body   TEXT,                           -- 非流式时的响应体
    chunks          TEXT,                           -- 流式时的响应 chunks JSON 数组
    duration_ms     INTEGER CHECK (duration_ms IS NULL OR duration_ms >= 0), -- 耗时（毫秒）
    created_at      TEXT      NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_api_call_log_created_id
    ON api_call_log(created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_api_call_log_provider_created_id
    ON api_call_log(provider_key, created_at DESC, id DESC);

-- ==================== Schema 版本表 ====================

CREATE TABLE IF NOT EXISTS schema_version (
    version      INTEGER PRIMARY KEY,
    description  TEXT NOT NULL,
    applied_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);