-- ============================================================
-- 采购比价与供应商尽调 Agent —— 初始化表结构
-- 方言：MySQL 8 / H2(MySQL 模式) 通用（不使用 ENGINE、COMMENT、ON UPDATE）
-- ============================================================

CREATE TABLE sys_user (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL,
    password     VARCHAR(100) NOT NULL,
    display_name VARCHAR(64)  NOT NULL,
    role         VARCHAR(32)  NOT NULL DEFAULT 'USER',
    enabled      TINYINT      NOT NULL DEFAULT 1,
    created_at   DATETIME     NOT NULL,
    updated_at   DATETIME     NOT NULL,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE TABLE supplier (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    code           VARCHAR(64)  NOT NULL,
    name           VARCHAR(128) NOT NULL,
    category       VARCHAR(64)  NOT NULL,
    region         VARCHAR(64),
    website        VARCHAR(256),
    contact_name   VARCHAR(64),
    contact_phone  VARCHAR(32),
    qualifications VARCHAR(512),
    rating         DECIMAL(3,2) NOT NULL DEFAULT 0,
    blacklisted    TINYINT      NOT NULL DEFAULT 0,
    note           VARCHAR(512),
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,
    CONSTRAINT uk_supplier_code UNIQUE (code)
);
CREATE INDEX idx_supplier_category ON supplier (category);

CREATE TABLE purchase_requirement (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    title          VARCHAR(128) NOT NULL,
    category       VARCHAR(64)  NOT NULL,
    spec           VARCHAR(256),
    quantity       DECIMAL(12,2) NOT NULL,
    unit           VARCHAR(16)  NOT NULL,
    budget_amount  DECIMAL(14,2),
    currency       VARCHAR(8)   NOT NULL DEFAULT 'CNY',
    delivery_city  VARCHAR(64),
    deadline       DATE,
    description    VARCHAR(1024),
    created_by     BIGINT       NOT NULL,
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL
);
CREATE INDEX idx_requirement_created_by ON purchase_requirement (created_by);

CREATE TABLE agent_task (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    requirement_id    BIGINT       NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    stage             VARCHAR(16)  NOT NULL,
    progress          INT          NOT NULL DEFAULT 0,
    attempt           INT          NOT NULL DEFAULT 1,
    mock_mode         TINYINT      NOT NULL DEFAULT 1,
    external_task_id  VARCHAR(64),
    error_message     VARCHAR(500),
    request_payload   TEXT,
    started_at        DATETIME,
    finished_at       DATETIME,
    last_heartbeat_at DATETIME,
    created_by        BIGINT       NOT NULL,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL
);
CREATE INDEX idx_agent_task_requirement ON agent_task (requirement_id);
CREATE INDEX idx_agent_task_status ON agent_task (status);

CREATE TABLE task_stage_event (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id      BIGINT       NOT NULL,
    stage        VARCHAR(16)  NOT NULL,
    seq          INT          NOT NULL,
    event_status VARCHAR(16)  NOT NULL,
    message      VARCHAR(500),
    payload      TEXT,
    created_at   DATETIME     NOT NULL,
    CONSTRAINT uk_stage_event UNIQUE (task_id, seq)
);

CREATE TABLE agent_callback_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id    VARCHAR(64) NOT NULL,
    nonce       VARCHAR(64) NOT NULL,
    task_id     BIGINT,
    event_type  VARCHAR(16) NOT NULL,
    body_hash   VARCHAR(64) NOT NULL,
    received_at DATETIME    NOT NULL,
    CONSTRAINT uk_callback_event UNIQUE (event_id),
    CONSTRAINT uk_callback_nonce UNIQUE (nonce)
);

CREATE TABLE quote (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id        BIGINT       NOT NULL,
    supplier_id    BIGINT,
    supplier_name  VARCHAR(128) NOT NULL,
    unit_price     DECIMAL(14,2),
    currency       VARCHAR(8)   NOT NULL DEFAULT 'CNY',
    unit           VARCHAR(16),
    moq            DECIMAL(12,2),
    lead_time_days INT,
    tax_included   TINYINT,
    source_url     VARCHAR(512) NOT NULL,
    fetched_at     DATETIME     NOT NULL,
    confidence     DECIMAL(3,2) NOT NULL DEFAULT 0,
    verified       TINYINT      NOT NULL DEFAULT 0,
    needs_review   TINYINT      NOT NULL DEFAULT 1,
    raw_snippet    VARCHAR(1024),
    created_at     DATETIME     NOT NULL
);
CREATE INDEX idx_quote_task ON quote (task_id);

CREATE TABLE risk_item (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id      BIGINT       NOT NULL,
    supplier_name VARCHAR(128),
    type         VARCHAR(32)  NOT NULL,
    level        VARCHAR(8)   NOT NULL,
    title        VARCHAR(256) NOT NULL,
    detail       VARCHAR(1024),
    source_url   VARCHAR(512),
    needs_review TINYINT      NOT NULL DEFAULT 1,
    created_at   DATETIME     NOT NULL
);
CREATE INDEX idx_risk_task ON risk_item (task_id);

CREATE TABLE task_result (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id              BIGINT       NOT NULL,
    recommended_supplier VARCHAR(128),
    recommendation       VARCHAR(1024),
    summary_json         TEXT,
    report_md            TEXT,
    total_candidates     INT          NOT NULL DEFAULT 0,
    created_at           DATETIME     NOT NULL,
    CONSTRAINT uk_task_result_task UNIQUE (task_id)
);
