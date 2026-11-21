-- 报价单导入：一批文件 = 一条 import_batch，撤销时按 batch 删除，供应商报价恢复上一版
CREATE TABLE import_batch (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    filename   VARCHAR(255) NOT NULL,
    row_total  INT          NOT NULL DEFAULT 0,
    row_ok     INT          NOT NULL DEFAULT 0,
    row_failed INT          NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at DATETIME     NOT NULL
);

-- 供应商级报价（跨任务复用）。与 quote 的区别：
-- quote 是"某次任务算出来的快照"，supplier_quote 是"供应商报给我们的原始报价"，可比价、可回溯、可画趋势。
CREATE TABLE supplier_quote (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id       BIGINT        NOT NULL,
    supplier_id    BIGINT        NOT NULL,
    supplier_name  VARCHAR(128)  NOT NULL,
    supplier_code  VARCHAR(64),
    category       VARCHAR(64)   NOT NULL,
    spec           VARCHAR(255)  NOT NULL,
    unit_price     DECIMAL(14,2) NOT NULL,
    currency       VARCHAR(8)    NOT NULL DEFAULT 'CNY',
    unit           VARCHAR(16)   NOT NULL DEFAULT '件',
    moq            DECIMAL(12,2),
    lead_time_days INT,
    tax_included   TINYINT       NOT NULL DEFAULT 1,
    quote_date     DATE          NOT NULL,
    valid_until    DATE,
    contact_name   VARCHAR(64),
    contact_phone  VARCHAR(32),
    remark         VARCHAR(255),
    row_no         INT,
    source_ref     VARCHAR(255),
    created_at     DATETIME      NOT NULL
);
CREATE INDEX idx_squote_supplier ON supplier_quote (supplier_id);
CREATE INDEX idx_squote_category ON supplier_quote (category);
CREATE INDEX idx_squote_batch ON supplier_quote (batch_id);

-- 任务的数据来源：MOCK（模拟）/ IMPORT（报价单）/ FETCH（公开页面抓取）
ALTER TABLE agent_task ADD COLUMN quote_source VARCHAR(16) NOT NULL DEFAULT 'MOCK';
