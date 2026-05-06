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

-- ==================== API 调用按天汇总表 ====================

CREATE TABLE IF NOT EXISTS api_usage_daily (
    usage_date    TEXT    NOT NULL PRIMARY KEY,   -- 日期，格式 YYYY-MM-DD
    call_count    INTEGER NOT NULL DEFAULT 0,     -- 当天调用次数
    input_tokens  INTEGER NOT NULL DEFAULT 0,     -- 当天输入 token 总量
    output_tokens INTEGER NOT NULL DEFAULT 0,     -- 当天输出 token 总量
    updated_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);