CREATE TABLE IF NOT EXISTS users (
    username VARCHAR(50) PRIMARY KEY,
    password VARCHAR(100) NOT NULL,
    enabled INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS authorities (
    username VARCHAR(50) NOT NULL,
    authority VARCHAR(50) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ix_authorities_username_authority
    ON authorities (username, authority);

-- ==================== 服务商配置表 ====================

CREATE TABLE IF NOT EXISTS provider_config (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_key  VARCHAR(30)  NOT NULL UNIQUE,   -- 服务商标识，如 longcat / mimo
    enabled       INTEGER      NOT NULL DEFAULT 0, -- 是否启用（0=禁用，1=启用）
    base_url      TEXT         NOT NULL DEFAULT '', -- API 基础 URL
    api_key       TEXT         NOT NULL DEFAULT '', -- API Key
    api_format    VARCHAR(20)  NOT NULL DEFAULT 'openai', -- API 格式：openai
    updated_at    TEXT         NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

-- ==================== 服务商模型表 ====================

CREATE TABLE IF NOT EXISTS provider_model (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_id   INTEGER      NOT NULL,            -- 关联 provider_config.id
    model_name    VARCHAR(100) NOT NULL,            -- 模型名称
    enabled       INTEGER      NOT NULL DEFAULT 1,  -- 是否启用（0=禁用，1=启用，默认启用）
    context_size  INTEGER      NOT NULL DEFAULT 0,  -- 上下文大小（token 数）
    caps_tools    INTEGER      NOT NULL DEFAULT 0,  -- 是否支持工具调用（0=否，1=是）
    caps_vision   INTEGER      NOT NULL DEFAULT 0,  -- 是否支持视觉（0=否，1=是）
    sort_order    INTEGER      NOT NULL DEFAULT 0,  -- 排序权重
    FOREIGN KEY (provider_id) REFERENCES provider_config(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_provider_model_provider_id ON provider_model(provider_id);

-- ==================== 应用运行配置表（键值对） ====================

CREATE TABLE IF NOT EXISTS app_config (
    config_key   VARCHAR(50) NOT NULL PRIMARY KEY,  -- 配置键，如 fake_version
    config_value TEXT        NOT NULL DEFAULT '',   -- 配置值
    updated_at   TEXT        NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

-- 默认运行配置
INSERT OR IGNORE INTO app_config (config_key, config_value) VALUES ('fake_version', '0.6.4');

-- ==================== API 调用按天汇总表 ====================

CREATE TABLE IF NOT EXISTS api_usage_daily (
    usage_date    TEXT    NOT NULL PRIMARY KEY,   -- 日期，格式 YYYY-MM-DD
    call_count    INTEGER NOT NULL DEFAULT 0,     -- 当天调用次数
    input_tokens  INTEGER NOT NULL DEFAULT 0,     -- 当天输入 token 总量
    output_tokens INTEGER NOT NULL DEFAULT 0,     -- 当天输出 token 总量
    updated_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);