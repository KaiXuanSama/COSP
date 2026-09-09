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
    display_name     TEXT         NOT NULL DEFAULT '', -- 前端完整显示名，独立于路由用 provider_key
    enabled          INTEGER      NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)), -- 是否启用（0=禁用，1=启用）
    base_url         TEXT         NOT NULL DEFAULT '', -- OpenAI 协议的 API 基础 URL
    -- 该供应商支持的线路协议集合（JSON 字符串数组，元素取值同 WireProtocol 枚举名）。
    -- 空数组表示「一种都不支持」，是显式的非法配置：调度器会明确报错而非静默回退。
    supported_protocols TEXT      NOT NULL DEFAULT '["OPENAI","ANTHROPIC"]' CHECK (json_valid(supported_protocols)),
    -- Anthropic 协议的独立 API 基础 URL；为空时回退到 base_url。
    -- 独立成列而非从 base_url 推导：中转站的 Anthropic 端点位置不可预测（有的在 /v1/messages，
    -- 有的在根路径），继续猜只会让「配了却调不通」这类问题无从排查。
    anthropic_base_url  TEXT      NOT NULL DEFAULT '',
    updated_at       TEXT         NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

-- ==================== 服务商模型表 ====================

CREATE TABLE IF NOT EXISTS provider_model (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_id     INTEGER      NOT NULL,            -- 关联 provider_config.id
    model_name      VARCHAR(100) NOT NULL,            -- 模型名称
    enabled         INTEGER      NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)), -- 是否启用（0=禁用，1=启用，默认启用）
    context_size    INTEGER      NOT NULL DEFAULT 0 CHECK (context_size >= 0), -- 上下文大小（token 数）
    -- 最大输出配置（V9 JSON）：{"max_output_tokens":4000,"overwrite_mode":"override|fallback"}
    -- 只有两档模式：override 一律用配置值；fallback 用下游的、没带才补。
    -- 没有 passthrough / delete：Anthropic 侧 max_tokens 缺失会 400，「不补」在那条线路上等于必然失败。
    max_output_tokens TEXT       NOT NULL DEFAULT '{"max_output_tokens":4000,"overwrite_mode":"fallback"}'
        CHECK (json_valid(max_output_tokens)),
    caps_tools      INTEGER      NOT NULL DEFAULT 0 CHECK (caps_tools IN (0, 1)), -- 是否支持工具调用（0=否，1=是）
    caps_vision     INTEGER      NOT NULL DEFAULT 0 CHECK (caps_vision IN (0, 1)), -- 是否支持视觉（0=否，1=是）
    -- 思考深度配置（V2 JSON）：{"reasoning_effort":"medium","overwrite_mode":"override|fallback|passthrough|delete"}
    -- 四种模式的区别只在「下游带了值时用谁的」与「下游没带时是否补」：
    -- override 一律用配置值；fallback 用下游的、没带才补；passthrough 用下游的、没带也不补；
    -- delete 连下游自带的也移除（某些上游收到该字段会 400，必须能强制剥离）。
    reasoning_effort TEXT        NOT NULL DEFAULT '{"reasoning_effort":"medium","overwrite_mode":"fallback"}'
        CHECK (json_valid(reasoning_effort)),
    -- 思考深度配置的结构版本。存在的意义是让 V8.9 那次「内容形态变更」成为可判定的结构事实，
    -- 否则基线判定只能靠翻数据，而空库没有行可翻。
    -- 注意：这是当时基线判定要求结构证据的产物，现已不需要，V9 与 V10 都没有再加同类列。
    reasoning_effort_schema INTEGER NOT NULL DEFAULT 2 CHECK (reasoning_effort_schema >= 1),
    -- Anthropic 思考方式（V10 JSON）：{"thinking_type":"adaptive|enabled","overwrite_mode":"override|fallback|passthrough"}
    -- 与思考深度正交：深度回答「想多深」，这里回答「预算怎么算」，两者可并存。
    -- 没有 disabled 形态：关闭思考由思考深度的 Off 档表达，两处都给会产出自相矛盾的请求体。
    -- 没有 delete 模式：强制剥离 thinking 用仅适用于 ANTHROPIC 的请求体规则表达。
    thinking_mode   TEXT         NOT NULL DEFAULT '{"thinking_type":"adaptive","overwrite_mode":"fallback"}'
        CHECK (json_valid(thinking_mode)),
    -- 思考预算（token 数）。只在 thinking_type = enabled 时有意义，adaptive 形态不接受它。
    -- -1 是「未设置」哨兵，不是可出站的值；enabled 但仍为 -1 时出站退化为 adaptive。
    -- 约束只放行这一个负值：其余负数没有约定含义，一律是脏数据。
    -- 预算没有自己的注入模式 —— 它是 enabled 形态的附属参数，跟着 thinking_mode 的模式走。
    thinking_budget_tokens INTEGER NOT NULL DEFAULT -1
        CHECK (thinking_budget_tokens > 0 OR thinking_budget_tokens = -1),
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

-- ==================== 供应商请求转换配置表 ====================
-- 请求头运行时读取本表的 header_rules_json；请求体运行时读取本表 body_rules_json。
-- body_rules_json 自 V8.7 起是 V2 规则组格式：{"version":2,"groups":[...]}，
-- 每组自带 protocols（适用线路）、templateKeys 与 previewBody（该组专属调试样本）。
-- body_template_keys_json 与 body_preview_json 是 legacy 列：内容已下沉进第一个规则组，
-- 不再是配置来源，仅为旧版本回滚时仍能读到一份有意义的样本而保留。

CREATE TABLE IF NOT EXISTS provider_request_transform (
    provider_id             INTEGER PRIMARY KEY,       -- 与供应商一对一关联
    header_rules_version    INTEGER NOT NULL DEFAULT 1 CHECK (header_rules_version >= 1),
    header_rules_json       TEXT    NOT NULL DEFAULT '[]' CHECK (json_valid(header_rules_json)),
    body_template_keys_json TEXT    NOT NULL DEFAULT '["custom"]' CHECK (json_valid(body_template_keys_json)), -- legacy
    body_preview_json       TEXT    NOT NULL DEFAULT '{}' CHECK (json_valid(body_preview_json)),               -- legacy
    body_rules_version      INTEGER NOT NULL DEFAULT 2 CHECK (body_rules_version >= 1),
    body_rules_json         TEXT    NOT NULL DEFAULT '{"version":2,"groups":[]}' CHECK (json_valid(body_rules_json)),
    body_rules_schema       INTEGER NOT NULL DEFAULT 2 CHECK (body_rules_schema >= 1),  -- 规则集结构版本，V8.7 迁移的结构性标记
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

CREATE TABLE IF NOT EXISTS api_usage_daily (
    usage_date    TEXT    NOT NULL PRIMARY KEY,   -- 日期，格式 YYYY-MM-DD
    call_count    INTEGER NOT NULL DEFAULT 0 CHECK (call_count >= 0), -- 当天调用次数
    input_tokens  INTEGER NOT NULL DEFAULT 0 CHECK (input_tokens >= 0), -- 当天输入 token 总量
    output_tokens INTEGER NOT NULL DEFAULT 0 CHECK (output_tokens >= 0), -- 当天输出 token 总量
    updated_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

-- ==================== API 调用详细日志表 ====================
-- 记录每次调用上游供应商 API 的完整请求/响应信息。
-- 行永久保留、只增不减；超出保留条数的旧行只清空大载荷列（请求头/体、响应头/体、chunks）
-- 并置 payload_trimmed = 1，调用元信息（状态码、耗时、供应商、模型、时刻）始终留存。
-- 因此本表在时间维度上是 api_call_usage 的超集：每条用量行都能反查到对应的日志行。

CREATE TABLE IF NOT EXISTS api_call_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_key    VARCHAR(30),                   -- 服务商标识，如 deepseek / mimo
    model_name      VARCHAR(100),                  -- 模型名称
    is_stream       INTEGER      NOT NULL DEFAULT 0 CHECK (is_stream IN (0, 1)), -- 是否流式（0=否，1=是）
    downstream_protocol TEXT     NOT NULL DEFAULT 'OPENAI' CHECK (downstream_protocol IN ('OPENAI', 'ANTHROPIC')), -- 下游请求线路协议
    upstream_protocol   TEXT     NOT NULL DEFAULT 'OPENAI' CHECK (upstream_protocol IN ('OPENAI', 'ANTHROPIC')), -- 实际上游线路协议
    status_code     INTEGER,                        -- HTTP 响应状态码
    request_headers TEXT,                           -- JSON 格式的请求头
    request_body    TEXT,                           -- JSON 格式的请求体
    response_headers TEXT,                          -- JSON 格式的响应头
    response_body   TEXT,                           -- 非流式时的响应体
    chunks          TEXT,                           -- 流式时的响应 chunks JSON 数组
    duration_ms     INTEGER CHECK (duration_ms IS NULL OR duration_ms >= 0), -- 耗时（毫秒）
    payload_trimmed INTEGER   NOT NULL DEFAULT 0 CHECK (payload_trimmed IN (0, 1)), -- 载荷是否已被保留任务清空
    created_at      TEXT      NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_api_call_log_created_id
    ON api_call_log(created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_api_call_log_provider_created_id
    ON api_call_log(provider_key, created_at DESC, id DESC);

-- ==================== API 调用 token 用量表 ====================
-- 独立于 api_call_log 记录每次成功调用的 token 用量与首字响应时长。
-- 因 api_call_log 会按条数裁剪（含完整 chunks，易膨胀），token 记录必须独立存活，
-- 作为长期用量统计与概览可视化的稳定数据源。
-- log_id 为软链接（无 FK 约束，允许悬空）：日志在时可跳详情看 chunk，日志裁剪后成孤儿亦无妨。
-- 表自给自足：冗余 provider_key / model_name / created_at，不依赖日志行存活即可解读。
-- token 列允许 NULL：null = 上游未提供该字段；0 = 上游报告了但值为零（区分对缓存命中率至关重要）。

CREATE TABLE IF NOT EXISTS api_call_usage (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    log_id            INTEGER,                        -- 软链接 → api_call_log.id，无 FK，允许悬空
    provider_key      VARCHAR(30),                    -- 冗余副本，日志删除后仍可解读
    model_name        VARCHAR(100),                   -- 冗余副本
    is_stream         INTEGER      NOT NULL DEFAULT 0 CHECK (is_stream IN (0, 1)), -- 是否流式
    usage_raw         TEXT,                           -- 上游 usage 对象原始 JSON，零损失兜底
    prompt_tokens     INTEGER CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0),         -- 输入；缺失为 NULL
    completion_tokens INTEGER CHECK (completion_tokens IS NULL OR completion_tokens >= 0), -- 输出；缺失为 NULL
    cached_tokens     INTEGER CHECK (cached_tokens IS NULL OR cached_tokens >= 0),         -- 缓存命中；按存在性优先级链取，全缺失 NULL
    ttfb_ms           INTEGER CHECK (ttfb_ms IS NULL OR ttfb_ms >= 0),                     -- 首字响应时长（毫秒）；非流式为 NULL
    created_at        TEXT      NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_api_call_usage_provider_created
    ON api_call_usage(provider_key, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_api_call_usage_log
    ON api_call_usage(log_id);
-- 纯 created_at 前导索引：概览的两条聚合查询只按时间范围过滤，没有 provider_key 等值条件，
-- 因此用不上上面那个复合索引（前导列不匹配）。升序而非 DESC：两条查询都是 >= start 的
-- 范围扫描，与升序同向；DESC 只对「取最近 N 条」有利，那是 provider_created 的场景。
CREATE INDEX IF NOT EXISTS idx_api_call_usage_created
    ON api_call_usage(created_at);

-- ==================== Schema 版本表 ====================

CREATE TABLE IF NOT EXISTS schema_version (
    id           INTEGER PRIMARY KEY CHECK (id = 1),
    version      INTEGER NOT NULL,
    description  TEXT NOT NULL,
    applied_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);