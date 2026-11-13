# 采购比价与供应商尽调 Agent —— 方案确认书（阶段①）

> 版本：v0.1 · 状态：**待确认** · 交付人：AI 结对开发 · 目标仓库：`https://github.com/Phh1235/-`

---

## 0. 一页速览

| 项 | 内容 |
|---|---|
| 项目名 | `procure-agent`（采购比价与供应商尽调 Agent） |
| 形态 | 双栈单体仓库：Java 业务中枢 + Python 智能执行器 |
| 端口 | Java `8080` / Python `8000` / MySQL `3306` |
| 演示 | 浏览器打开 `http://localhost:8080/demo.html`，账号 `demo / demo123`，默认 **mock 数据源**，断网可跑 |
| 工作量 | 单人 13~14 个工作日（每天 4~6h），见 §13 |
| 技术栈 | Spring Boot 3.3.5 + MyBatis-Plus 3.5.7 + MySQL 8 + JWT + OpenFeign + SSE / FastAPI + Pydantic v2 + LangChain(LCEL) + httpx |
| 核心卖点 | ①Java 与 Python 的**职责边界**清晰（不是"Python 调个接口"）②**来源可追溯**的数据账本 ③**确定性打分**、大模型不碰数字 ④自带 **40 条评测集**与报告 |

---

## 1. 范围界定

### 1.1 做什么（5 个能力闭环）

1. **需求单管理**：录入采购需求（品类、规格、数量、预算、交付地、交付时间），草稿/启用/归档。
2. **一键比价任务**：提交后异步跑完整流水线，前端实时看到阶段进度。
3. **候选供应商 + 结构化比价表**：单价、含税、运费、MOQ、交期、有效期，字段级来源。
4. **资质与风险提示**：营业执照、体系认证、失信/经营异常、历史中标 —— 拿不到的一律"待人工核实"。
5. **推荐结论 + Markdown 报告**：确定性打分排序 + 大模型写"人话"解释，可下载。

### 1.2 不做什么（写进 README，避免面试被追问翻车）

| 不做 | 理由 |
|---|---|
| 多智能体编排 / LangGraph 多角色 | 单人 2~3 周做不完，且本题是**线性流水线**，多智能体是过度设计 |
| 微调 / 私有化部署 | 与目标岗位无关，成本高收益低 |
| 真实电商爬虫（1688/京东/企查查） | 需登录态 + 反爬 + 验证码，**演示必挂**，且合规风险高 |
| 前端框架（Vue/React） | 只做演示用的原生 HTML + SSE，省 3 天 |

> 数据源策略：**mock 为默认、真实源为可选**。mock 用本地 fixture（10 家供应商 / 6 个品类），真实源只接"公开可访问、无需登录"的页面，并在白名单里显式登记。演示永远走 mock，答辩现场断网也能跑。

---

## 2. 架构总览

### 2.1 分层图（文字版）

```
                      浏览器 / curl / Swagger UI
                                 │  HTTP + JWT
┌────────────────────────────────▼─────────────────────────────────┐
│ Java 侧  Spring Boot :8080  ——  业务与治理层（有状态）             │
│                                                                  │
│  Controller ── Service ── Mapper ── MySQL 8                       │
│      │            │                                              │
│      │            ├── TaskStateMachine   任务状态机（唯一事实源）  │
│      │            ├── RateLimitInterceptor 限流（Redis/本地降级） │
│      │            ├── JwtAuthFilter      认证                      │
│      │            └── SseService         SSE 广播（含事件回放）    │
│      │                                                           │
│      └── OpenFeign ──────────────┐                               │
└──────────────────────────────────┼───────────────────────────────┘
                                   │ ① 提交任务（同步 202 Accepted）
                                   │ ③ 内部回调（HMAC-SHA256 签名）
┌──────────────────────────────────▼───────────────────────────────┐
│ Python 侧  FastAPI :8000  ——  智能执行层（无状态，可水平扩容）     │
│                                                                  │
│  POST /v1/tasks → 线程池执行 → LangChain LCEL 流水线               │
│                                                                  │
│  ①解析需求 → ②发现供应商 → ③抓取报价 → ④清洗归一化                │
│  → ⑤确定性打分 → ⑥资质尽调 → ⑦生成报告 → ⑧回调 Java             │
│                                                                  │
│  ┌ 安全网关（所有出网请求必经）─────────────────────────┐          │
│  │ 域名白名单 · 仅 GET · DNS/IP 校验(防 SSRF) · 限速    │          │
│  │ 响应体大小上限 · 超时 · 提示词注入过滤 · 输出脱敏    │          │
│  └──────────────────────────────────────────────────┘          │
└──────────────────────────────────┬───────────────────────────────┘
                                   │ 只读 GET（白名单域名）
                        公开页面 / 本地 mock fixture
```

### 2.2 职责边界（面试必问，先想清楚）

| 关注点 | Java | Python | 为什么这么切 |
|---|---|---|---|
| 任务状态 | **唯一事实源**，状态机 + 非法迁移拦截 | 只上报阶段，不改状态 | 状态一旦两边都能改，必出双写不一致 |
| 事务/幂等 | 事务边界、唯一索引、幂等键 | 无数据库 | 业务一致性属于业务层 |
| 鉴权/限流 | JWT、用户级限流 | 内部令牌校验 | 对外入口只有一个 |
| 智能能力 | 无 | LLM、解析、打分、报告 | 生态在 Python |
| 失败重试 | 补偿扫描（回调丢失兜底） | 单任务超时自毁 | 谁拥有状态谁负责兜底 |

> 一句话总结给面试官：**"Python 是无状态执行器，Java 是有状态编排器；把状态机和事务关在一个进程里，才敢说这个系统可靠。"**

---

## 3. 目录结构

```
procure-agent/
├─ README.md                     # 架构图、启动步骤、mock 说明、FAQ
├─ .gitignore · .env.example
├─ docker-compose.yml            # 可选：mysql + redis（本机没装 Redis 时用）
├─ scripts/
│  ├─ start.ps1                  # 一键启动（Windows，主推）
│  ├─ start.sh                   # 一键启动（macOS/Linux）
│  ├─ stop.ps1
│  └─ smoke-test.ps1             # 8 个接口冒烟，输出 PASS/FAIL
├─ docs/
│  ├─ 00-plan.md                 # 本文档
│  ├─ 01-architecture.md         # 架构与数据流细节
│  ├─ 02-api.md                  # 接口契约
│  ├─ 03-security.md             # 安全设计逐条对照
│  └─ 04-eval-report.md          # 评测报告（脚本生成）
│
├─ java-backend/
│  ├─ pom.xml
│  └─ src/main/
│     ├─ java/com/phh/procure/
│     │  ├─ ProcureApplication.java
│     │  ├─ common/              # ApiResult / BizException / ErrorCode / 全局异常 / 分页
│     │  ├─ config/              # Security / Jwt / Feign / MyBatisPlus / Web / OpenAPI
│     │  ├─ security/            # JwtService / JwtAuthFilter / LoginUser / @CurrentUser
│     │  ├─ ratelimit/           # @RateLimit / 拦截器 / Redis 与内存两种实现
│     │  ├─ module/
│     │  │  ├─ user/             # 登录、用户
│     │  │  ├─ requirement/      # 需求单 CRUD
│     │  │  ├─ supplier/         # 供应商主数据
│     │  │  ├─ task/             # 任务创建/查询/取消/重试 + 状态机
│     │  │  └─ result/           # 比价结果、报告、CSV 导出
│     │  ├─ integration/agent/   # AgentClient(Feign) / 回调接口 / HMAC 校验 / DTO
│     │  ├─ sse/                 # SseService（事件回放 + 心跳）
│     │  └─ scheduler/           # TaskReconcileJob（回调丢失补偿）
│     ├─ resources/
│     │  ├─ application.yml · application-local.yml
│     │  ├─ db/migration/V1__init.sql · V2__seed.sql   # Flyway
│     │  ├─ static/demo.html     # 演示页（原生 JS + EventSource）
│     │  └─ logback-spring.xml   # 日志脱敏
│     └─ test/java/...           # MockMvc 接口测试 + 状态机单测
│
├─ python-agent/
│  ├─ requirements.txt
│  ├─ app/
│  │  ├─ main.py                 # FastAPI 入口
│  │  ├─ config.py               # pydantic-settings，读 .env
│  │  ├─ schemas.py              # 请求/响应/内部模型
│  │  ├─ api/                    # tasks.py(提交/查询/取消) · health.py
│  │  ├─ core/                   # 日志、错误、httpx 客户端池
│  │  ├─ security/               # url_guard / sanitizer / masking / budget
│  │  ├─ datasource/             # base 抽象 + mock_source + web_source + fixtures/*.json
│  │  ├─ agent/
│  │  │  ├─ pipeline.py          # LCEL 编排 + 阶段回调 + 超时
│  │  │  ├─ llm.py               # real/mock 双实现，统一接口
│  │  │  ├─ prompts.py           # 全部提示词集中管理（含注入防护声明）
│  │  │  └─ steps/               # parse / discover / fetch / normalize / score / risk / report
│  │  ├─ normalize/              # 规格解析、单位换算、含税价换算
│  │  ├─ callback/               # 回调客户端 + HMAC 签名
│  │  └─ store/                  # 内存任务表 + 幂等
│  └─ tests/                     # pytest：单测 + 静态源端到端
│
├─ eval/
│  ├─ dataset/cases.jsonl        # 40 条测试集
│  ├─ gold/suppliers.json        # 每个 case 的期望供应商集合
│  ├─ run_eval.py                # 跑批 + 指标 + 生成 Markdown
│  └─ reports/
└─ .m2repo/                      # 本机 Maven 本地仓库（.gitignore）
```

---

## 4. 技术选型与理由

| 层 | 选型 | 版本 | 为什么 | 备选与否决理由 |
|---|---|---|---|---|
| 语言 | Java 21 | 21 LTS | 虚拟线程 + record，本机已装 | 17 也行，但 21 是现代写法 |
| 框架 | Spring Boot | **3.3.5** | 生态稳定，MyBatis-Plus / Spring Cloud 2023.0.x 完全兼容 | Spring Boot 4.x 需要 Spring Framework 7，MyBatis-Plus 尚不兼容，**不用** |
| ORM | MyBatis-Plus | 3.5.7 | 单表 CRUD 零 SQL，复杂查询仍可写 XML | JPA 也行，但国内团队 MyBatis 更常见，面试更对口 |
| DB | MySQL 8.0 | 8.x | 本机已运行；`createDatabaseIfNotExist=true` 免手工建库 | H2 只用于单测 |
| 迁移 | Flyway | 10.x | 建表脚本进版本库，"一键启动"自动建表 | 手写 SQL 容易和实体不一致 |
| 缓存/限流 | Redis（**可选**） | 7.x | 分布式限流令牌桶 + 缓存；不可用时自动降级为本地实现 | 本机无 Redis，所以**做成可降级**，不是硬依赖 |
| 鉴权 | JJWT | 0.12.6 | 轻量、API 清晰 | Spring Security 全家桶对实习生项目过重 |
| 服务调用 | OpenFeign | 4.1.3 | 声明式、可配超时/拦截器，简历关键词 | RestClient 更轻，但题目要求 Feign |
| 推送 | SseEmitter | 内置 | 单向进度推送，比 WebSocket 简单，HTTP 友好 | WebSocket 需额外握手/心跳/网关配置 |
| 文档 | springdoc-openapi | 2.6.0 | Swagger UI 直接演示 8 个接口 | 手写文档易过期 |
| Python | 3.11+ | 本机 3.14 | 本机已装 | — |
| 框架 | FastAPI | 0.115+ | 异步 + Pydantic 校验 + 自动 OpenAPI | Flask 无异步，抓取场景吃亏 |
| 编排 | LangChain **LCEL** | langchain-core 1.x | 题目要求 LangChain；LCEL 可组合、可单测、可 trace | **不用 AgentExecutor**：本流程是确定性流水线，Agent 反而不可控 |
| 模型 | DeepSeek / OpenAI 兼容 | — | 便宜、OpenAI 协议兼容 | 无 Key 时自动 `mock` 模式，规则解析 + 模板报告 |
| 抓取 | httpx | 0.28 | 同步/异步统一、超时与重定向可控 | requests 不支持异步；scrapy 过重 |
| 测试 | pytest / JUnit5 | — | 接口逐条测试，对应"每做一个接口测一次" | — |
| 前端 | 原生 HTML+JS | — | 演示用，0 构建 | Vue 需要 node 构建链，浪费时间 |

**关键取舍说明（面试可直接说）**

- **为什么不用向量库 / RAG？** 这个任务不是"从文档里找答案"，而是"从结构化字段里抽取 + 规则计算"。硬上 RAG 会把可校验的数字变成不可校验的语义检索，反而降低可信度。**不用**是设计决策，不是能力不足。
- **为什么 Python 不写数据库？** 保持无状态才能水平扩容；也让"状态机"只有一个主人。
- **为什么打分不用 LLM？** LLM 给不出可复现的排序，且无法解释"为什么 A 比 B 贵却排前面"。打分用公式，LLM 只负责把结果写成自然语言。

---

## 5. 数据流（文字版）

### 5.1 主链路时序

```
用户           Java(8080)                MySQL            Python(8000)         数据源
 │  登录          │                        │                  │                  │
 │──────────────▶│ 校验密码→签发JWT        │                  │                  │
 │◀──token────────│                        │                  │                  │
 │  建需求单       │                        │                  │                  │
 │──────────────▶│ INSERT requirement      │                  │                  │
 │  提交比价       │                        │                  │                  │
 │──────────────▶│ ①限流校验 ②建 task(QUEUED) ③落 event     │                  │
 │               │─────── Feign POST /v1/tasks (202) ────────▶│ 建内存任务         │
 │◀──taskId───────│                        │                  │ 线程池开始         │
 │  打开 SSE      │                        │                  │                  │
 │──────────────▶│ 订阅 emitter            │                  │                  │
 │               │                        │  ②回调 stage=PARSE│                  │
 │               │◀────── HMAC 回调 ───────┼──────────────────│                  │
 │◀──event────────│ 校验签名→状态机迁移→落库→推 SSE            │                  │
 │               │                        │  ③检索供应商       │──── mock/GET ───▶│
 │               │                        │  ④抓报价           │──── mock/GET ───▶│
 │               │                        │  ⑤归一化(单位/含税) │                  │
 │               │                        │  ⑥打分(确定性)     │                  │
 │               │                        │  ⑦尽调风险         │                  │
 │               │                        │  ⑧生成报告(LLM)    │                  │
 │               │◀────── 回调 result ────┼──────────────────│                  │
 │               │ 事务写 quote/risk/result→状态 SUCCEEDED→推 SSE            │
 │◀──最终事件─────│                        │                  │                  │
 │  查结果        │                        │                  │                  │
 │──────────────▶│ SELECT 比价表/风险/推荐  │                  │                  │
```

### 5.2 任务状态机（Java 侧唯一事实源）

```
                 ┌──────────┐
      创建 ─────▶ │  QUEUED  │
                 └────┬─────┘
                      │ 回调 stage=PARSE
                 ┌────▼─────┐
                 │  RUNNING │◀──┐ 阶段: PARSE→DISCOVER→FETCH
                 └────┬─────┘   │        →NORMALIZE→SCORE→RISK→REPORT
        ┌─────────────┼─────────┘
        │             │
   ┌────▼────┐   ┌────▼─────┐    ┌────────────┐
   │ FAILED  │   │SUCCEEDED │    │  CANCELED  │◀── 用户取消 / 超时补偿
   └────┬────┘   └──────────┘    └────────────┘
        │ 重试（新 task，老 task 不动）
        └──────▶ QUEUED
```

| 当前状态 | 允许事件 | 目标状态 | 说明 |
|---|---|---|---|
| QUEUED | STAGE_REPORTED | RUNNING | 首个阶段回调 |
| QUEUED | CANCEL | CANCELED | 还没开始就取消 |
| RUNNING | STAGE_REPORTED | RUNNING | 阶段只许**单调前进**，回退即拒绝 |
| RUNNING | SUCCEEDED | SUCCEEDED | 必须已有 RESULT 事件 |
| RUNNING | FAILED | FAILED | 记录 error_code |
| RUNNING | CANCEL | CANCELED | 通知 Python 终止 |
| RUNNING | TIMEOUT | FAILED | 补偿任务扫描 |
| SUCCEEDED / FAILED / CANCELED | 任何 | 拒绝 | 终态不可变 |

**不变量**：`stage` 序号严格递增；`progress` 单调不减；同一 `event_id` 只落库一次；终态后任何回调返回 409。

### 5.3 SSE 事件协议

```
GET /api/tasks/{id}/stream?ticket=xxx      (text/event-stream)

event: progress
data: {"taskId":"T1","status":"RUNNING","stage":"FETCH","progress":45,"msg":"正在抓取 6/10 家报价","ts":1731500000}

event: done
data: {"taskId":"T1","status":"SUCCEEDED","stage":"REPORT","progress":100,"resultUrl":"/api/tasks/T1/result"}
```

- 15s 心跳注释帧 `:keep-alive` 防代理断链；断线自动重连并**从 `task_stage_event` 回放**已发生事件（`Last-Event-ID`）。
- 鉴权：`EventSource` 不能带 Header → 先 `POST /api/tasks/{id}/stream-ticket` 拿 **60 秒一次性票据**，再 `?ticket=` 连接。票据存在 Redis/本地缓存，用后即焚。

### 5.4 回调协议（Python → Java）

```
POST /internal/agent/callback
X-Agent-Timestamp: 1731500000
X-Agent-Nonce: 8f3c...
X-Agent-Signature: hex(HMAC_SHA256(secret, timestamp + "\n" + nonce + "\n" + rawBody))

{"eventId":"E-...","taskId":"T1","seq":3,"type":"STAGE","payload":{...}}
```

校验顺序：时间戳 ±5 min → nonce 未用过（`agent_callback_log` 唯一索引）→ 签名 → `taskId` 存在 → 状态机迁移。任一步失败返回对应 4xx，Python 侧不重试永久性错误。

### 5.5 失败与补偿

| 场景 | 处理 |
|---|---|
| Python 挂了/重启 | Java `TaskReconcileJob` 每 60s 扫 RUNNING 且 5min 无事件的任务 → 调 `GET /v1/tasks/{id}` 对账；对不上则置 FAILED(timeout) |
| 回调丢失 | 同上；同时 Python 每阶段重发最近一次事件（最多 3 次，指数退避） |
| 单任务超时 | Python 120s 硬超时，自行回调 FAILED 并清理线程 |
| 重复提交 | 前端带 `Idempotency-Key`，`agent_task` 唯一索引兜底，重复返回同一 taskId |
| 取消 | Java 置 CANCELED → Feign `DELETE /v1/tasks/{id}` → Python 协作式检查取消位 |

---

## 6. 数据模型（9 张表）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `sys_user` | id, username(uk), password_hash(BCrypt), role, status | 登录 |
| `supplier` | id, name, uscc(统一社会信用代码, uk), region, categories, contact_masked, rating, status | 主数据，**联系方式入库即脱敏** |
| `purchase_requirement` | id, user_id, title, category, spec_json, quantity, unit, budget_amount, currency, delivery_city, delivery_deadline, status, version | 需求单，`version` 乐观锁 |
| `agent_task` | id, requirement_id, user_id, status, stage, progress, error_code, error_msg, idem_key(uk), started_at, finished_at, version | 任务主表 |
| `task_stage_event` | id, task_id, seq(uk 与 task_id), event_type, payload_json, created_at | SSE 回放 + 阶段留痕 |
| `agent_callback_log` | id, event_id(uk), task_id, received_at | 回调幂等 |
| `quote` | id, task_id, supplier_id, supplier_name, unit_price, currency, tax_included, shipping_fee, moq, lead_time_days, valid_until, source_url, fetched_at, confidence, needs_review | 比价行，**每行都有来源** |
| `risk_item` | id, task_id, supplier_id, risk_type, level, description, source_url, fetched_at, needs_review | 尽调项 |
| `task_result` | id, task_id(uk), report_md, recommendation_json, metrics_json, created_at | 报告与推荐 |

索引：`agent_task(user_id, created_at)`、`quote(task_id)`、`risk_item(task_id)`、`task_stage_event(task_id, seq)`。

---

## 7. 接口清单

### 7.1 Java 对外（前缀 `/api`，除登录外均需 JWT）

| 方法 | 路径 | 说明 | 限流 |
|---|---|---|---|
| POST | `/auth/login` | 登录换 token | 10 次/分/IP |
| GET | `/auth/me` | 当前用户 | — |
| POST | `/requirements` | 新建需求单 | 30 次/分 |
| GET | `/requirements?page=&size=` | 分页 | — |
| GET/PUT/DELETE | `/requirements/{id}` | 详情/改/软删 | — |
| POST | `/requirements/{id}/tasks` | **提交比价任务**（幂等键） | 5 次/分/用户 |
| GET | `/tasks/{id}` | 任务状态 | — |
| GET | `/tasks?requirementId=` | 任务列表 | — |
| POST | `/tasks/{id}/cancel` | 取消 | — |
| POST | `/tasks/{id}/retry` | 重试（生成新任务） | 3 次/分 |
| POST | `/tasks/{id}/stream-ticket` | 换 SSE 票据 | 10 次/分 |
| GET | `/tasks/{id}/stream?ticket=` | SSE | — |
| GET | `/tasks/{id}/result` | 比价表 + 风险 + 推荐 | — |
| GET | `/tasks/{id}/report.md` | 下载报告 | — |
| GET | `/tasks/{id}/export.csv` | 导出比价表 | — |
| CRUD | `/suppliers` | 供应商主数据 | 20 次/分 |

### 7.2 Java → Python（`/v1`，`X-Internal-Token`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/tasks` | 提交，返回 202 + taskId（已存在则幂等返回） |
| GET | `/v1/tasks/{id}` | 状态对账（补偿用） |
| DELETE | `/v1/tasks/{id}` | 取消 |

### 7.3 Python → Java（`/internal`，HMAC）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/agent/callback` | 阶段/结果/错误回调 |
| GET | `/internal/health` | 存活探针（给 start 脚本用） |

---

## 8. 安全设计（对应 5 条硬性约束）

| 约束 | 落点 | 做法 | 怎么证明 |
|---|---|---|---|
| 工具域名白名单 | `python-agent/app/security/url_guard.py` | 配置文件声明允许域名（含子域规则）；请求前解析 host→IP，**拒绝内网/回环/云元数据段**（10/172.16/192.168/127/169.254/::1），仅 https，禁重定向到白名单外 | 单测：10 个恶意 URL 全部拦截 |
| 只读权限 | `datasource/web_source.py` | 只用 GET；不实现 POST/PUT；下载体积上限 1MB；超时 5s；不落盘 | 代码审查 + 单测 |
| 请求频率限制 | `security/budget.py` + Java `RateLimitInterceptor` | 每域名 1 req/s、单任务总请求 ≤ 30、对外接口按用户/按 IP 令牌桶（Redis 不可用则本地降级） | 单测 + 并发压测日志 |
| 提示词注入过滤 | `security/sanitizer.py` + `prompts.py` | ①剥 script/style/注释 ②长度截断 2000 字 ③正则命中"忽略以上指令/ignore previous/system:"等 → 替换为 `[REDACTED]` ④内容包在 `<untrusted_data>` 里并声明"仅作数据" ⑤输出必须过 Pydantic schema，多余字段丢弃 | 数据集里放 3 条注入样本，报告统计拦截率 |
| 输出脱敏 | `security/masking.py` + `logback-spring.xml` | 手机/邮箱/身份证/银行卡正则掩码；日志里 token、Authorization、apiKey 打码 | 单测 + 抽一份日志人工看 |
| API Key 走环境变量 | `.env` + `.env.example` | 代码零硬编码；`.env` 在 .gitignore；启动时缺 Key 自动回落 mock 模式并 WARN | `git grep -i "sk-"` 为空 |

**补充两条（加分项，面试可讲）**

- **SSE 票据机制**：解决 `EventSource` 无法带 Authorization 头的问题，避免把长效 JWT 拼进 URL（会进浏览器历史/网关日志）。
- **回调 HMAC + nonce**：Python 到 Java 的内部接口不暴露公网，但仍然签名 + 防重放，因为"内网可信"是最常见的错误假设。

---

## 9. 可信度设计

### 9.1 字段级来源账本（核心）

每个数据字段不是裸值，而是：

```json
{
  "value": 128.50,
  "currency": "CNY",
  "unit": "件",
  "source_url": "https://example.gov.cn/notice/2024/123.html",
  "fetched_at": "2026-11-14T09:12:33+08:00",
  "confidence": 0.86,
  "verified": true,
  "needs_review": false
}
```

规则：

1. **无来源不入库**：`source_url` 或 `mock://fixture/xxx` 二者必居其一。
2. **mock 数据在 UI 和报告上显著标注** `[MOCK]`，绝不假装是真实抓取。
3. **拿不到就标"待人工核实"**（`needs_review=true`），例如企业失信记录、银行账户、历史诉讼。
4. **时间戳**：抓取时间 + 页面发布时间（若有）都存，报告里显示"数据截至"。

### 9.2 禁止大模型编造报价 —— 三道闸

1. **数据闸**：报价只能来自 `datasource` 的结构化抽取结果（正则/选择器/表格解析），LLM **没有**生成报价的能力，它的输出 schema 里根本没有价格字段。
2. **计算闸**：排序与推荐由 Python 确定性函数计算，公式与权重写死在配置里，单测覆盖。
3. **文本闸**：LLM 只写"推荐理由"文案，生成后用正则抽出所有数字，逐个比对是否出现在事实表中；出现表外数字 → 丢弃该句并重写（最多 2 次，仍失败则用模板兜底）。

### 9.3 打分公式（可解释、可复现）

```
total = 0.40 * priceScore      # 含运费含税落地价，最低价得 100
      + 0.20 * leadTimeScore   # 交期越短越高，超过需求交期直接 0
      + 0.20 * qualificationScore  # 资质齐全度（营业执照/体系认证/授权代理）
      + 0.20 * riskScore       # 风险项扣分（失信=0，经营异常=-30，无数据=待核实不扣分但标注）

硬性淘汰：报价缺失 / 交期不满足 / 严重失信 → 进"不合格"分组，仍展示但不参与推荐
```

---

## 10. 评测设计

### 10.1 数据集（40 条）

`eval/dataset/cases.jsonl`，每条：

```json
{"id":"C01","category":"办公用品","spec":"A4 复印纸 70g 500张/包","qty":200,"unit":"包","budget":6000,
 "delivery_city":"杭州","expect":{"min_suppliers":3,"must_fields":["unit_price","lead_time_days","moq"],
 "gold_suppliers":["S001","S004","S007"],"noise":"inject_prompt"}}
```

覆盖：6 个品类 × 6 条 + 4 条脏数据（缺价/缺规格/乱码）+ 2 条负样本（无供应商）+ 2 条注入样本。

### 10.2 四个指标

| 指标 | 口径 | 目标 |
|---|---|---|
| 字段填充率 | 必填字段非空数 / 必填字段总数（按 case 平均） | ≥ 90% |
| 供应商召回率 | \|命中 gold ∩ 返回\| / \|gold\| | ≥ 85% |
| 抽检准确率 | 抽 30 条报价，与 fixture/gold 值完全一致的比例 | ≥ 95% |
| 来源覆盖率 | 带 source_url + fetched_at 的字段数 / 总字段数 | 100% |

附加：注入拦截率、平均耗时、P95 耗时、幻觉数字检出数（应为 0）。

### 10.3 输出

`python eval/run_eval.py --mode mock` → `docs/04-eval-report.md`，含指标表、失败 case 明细、逐条耗时。这份报告本身就是简历附件。

---

## 11. 一键启动 + 本机环境审计

### 11.1 本机实测结论

| 项 | 状态 | 影响与对策 |
|---|---|---|
| JDK 21 / Maven 3.10 | ✅ 可用 | 共享仓库 `D:\biancheng\Maven\repository` **只读** → 项目内用 `.m2repo`（已 gitignore），`start.ps1` 自动传 `-Dmaven.repo.local` |
| Maven 中央仓库 | ⚠️ 需镜像 | 阿里云 `maven.aliyun.com` 可达，项目自带 `settings.xml` 片段（不改全局配置） |
| Python 3.14 | ✅ 可用 | 已装 fastapi/uvicorn/langchain-core/pydantic/httpx/pandas；缺的用阿里云 PyPI 镜像装 |
| PyPI 官方源 | ❌ 不可达 | 默认走 `mirrors.aliyun.com/pypi/simple`（写进 start 脚本，可用 `PIP_INDEX_URL` 覆盖） |
| MySQL 8 | ✅ 运行中，`root/123456` | 默认连它；`createDatabaseIfNotExist=true` 自动建库；密码放 `.env` |
| Redis | ❌ 未安装 | 限流/票据**自动降级为本地实现**；`docker-compose.yml` 提供可选项 |
| Docker | ⚠️ 已装但守护进程未启动 | 不作为一键启动的硬依赖 |
| GitHub | ⚠️ `github.com` DNS 被污染（解析到 `57.155.223.240`），`api.github.com` 与 `ssh.github.com:443` 正常 | 推送方案见 §14 待确认项 |

### 11.2 `start.ps1` 流程（幂等，可重复执行）

```
1. 读 .env（不存在则从 .env.example 生成并提示）
2. 检查 java / mvn / python 版本，不满足直接报错退出
3. mvn -Dmaven.repo.local=.m2repo -f java-backend/pom.xml -q package -DskipTests
4. python -m venv python-agent/.venv（已存在则跳过）
   .venv/Scripts/pip install -r requirements.txt -i <镜像>
5. 后台起 uvicorn :8000 → 轮询 /health 直到 ready（最多 60s）
6. 后台起 java -jar → 轮询 /actuator/health 或 /api/health
7. 打印：演示页 http://localhost:8080/demo.html · Swagger http://localhost:8080/swagger-ui.html
8. 失败时打印最近 50 行日志 + 端口占用提示
```

`stop.ps1` 按 pid 文件杀进程。**验收标准**：新克隆仓库 → 双击 `start.ps1` → 5 分钟内看到演示页能登录、能跑完一次比价任务。

---

## 12. 差异化 & "容易被问穿"的坑位

### 12.1 与常见开源项目的差异

| 常见开源形态 | 我们的差异 |
|---|---|
| `langchain-chatchat` 类：RAG 知识库问答 | 不做问答，做**结构化抽取 + 规则打分**；不用向量库并说明理由 |
| `MetaGPT/ChatDev`：多智能体角色扮演 | **单流水线**，强调可复现与可审计，符合 2~3 周约束 |
| 各种 Streamlit 单文件 demo | **双栈 + 服务契约 + 状态机 + 补偿**，有真实工程边界 |
| 只调 API 的"AI 比价" | 有**来源账本**、**评测集**、**安全网关**，可信度可量化 |
| 必须联网才能跑 | **mock 优先**，断网可完整演示 |

### 12.2 容易"照抄被问穿"的 8 个点（重点看）

| # | 坑位 | 为什么露馅 | 改造建议 |
|---|---|---|---|
| 1 | 声称爬了 1688 / 企查查 / 天眼查 | 面试官问"登录态怎么维持""验证码怎么过""频率限制多少"立刻穿帮 | 明确写 **mock + 1 个公开源**，把"为什么不做"讲成合规与稳定性决策 |
| 2 | LangChain 只用了 `ChatOpenAI.invoke` | 会被问"这跟直接 requests 调 API 有啥区别" | 用 LCEL 组合 + Pydantic 结构化输出 + 失败重试 + mock 替换，讲清每层职责 |
| 3 | "AI 自动比价" 实际 LLM 在算数 | 复现性差、数字对不上 | 公式化打分 + 单测；LLM 只写文案（本文档 §9.3） |
| 4 | 状态机是一堆 if-else | 问"并发下状态错乱怎么办" | 迁移表 + 乐观锁 + 非法迁移单测 + 补偿 Job |
| 5 | SSE 直接 `?token=` 传 JWT | 问"token 泄漏到日志怎么办" | 一次性票据（§5.3） |
| 6 | 限流是 `synchronized` 计数器 | 问"多实例怎么办" | Redis 令牌桶 + 本地降级，讲清降级边界 |
| 7 | 没有幂等 | 问"用户连点两次会怎样" | 幂等键 + 唯一索引 + 重复请求返回同一 taskId |
| 8 | 目录照搬某开源项目 | 面试官见过 | **换业务场景 + 换数据源 + 换向量库（不用）+ 换编排方式（LCEL）**，README 里写"参考了什么、改了什么" |

> 差异化改造一句话：**把"AI 能力"降级成可验证的一个环节，把"工程可靠性"（状态机/幂等/补偿/可观测）做成主线** —— 这恰好是 Java 后端岗最想听的。

---

## 13. 排期（14 个工作日）

| 天 | 产出 | 验收 |
|---|---|---|
| D1 | 仓库骨架、.env、Flyway 建表、健康检查、start 脚本雏形 | `start.ps1` 能起空壳 |
| D2 | 登录 + JWT + 全局异常 + 统一返回 | 登录接口测试通过并提交 |
| D3 | 限流（含降级）+ 参数校验 | 限流单测通过 |
| D4 | 供应商 CRUD | 接口测试通过 |
| D5-6 | 需求单 CRUD + 分页 + 乐观锁 | 接口测试通过 |
| D7 | 任务创建 + 状态机 + 事件表 | 状态机单测 8 条 |
| D8 | Feign + 回调 + HMAC + SSE + 补偿 Job | 端到端 mock 跑通 |
| D9-10 | Python 骨架 + 解析 + 数据源抽象 + mock fixture | `/v1/tasks` 跑通 |
| D11 | 抓取网关（白名单/SSRF/限速/注入过滤/脱敏） | 安全单测 15 条 |
| D12 | 归一化 + 打分 + 尽调 + 报告 | 结果结构正确 |
| D13 | 评测脚本 + 40 条数据集 | 生成评测报告 |
| D14 | README + 架构图 + 简历材料 + 演示录屏脚本 | 全链路演示 |

每个接口完成即：写测试 → 跑通 → `git commit`（提交信息规范见 README）。

---

## 14. 待你确认（确认后进入阶段②）

1. **范围**：以上 §1 的"不做什么"是否同意？（尤其：不做真实电商爬虫、不做前端框架）
2. **GitHub 推送方式**：本机 `github.com` 被 DNS 污染，直连推送会失败。三选一：
   - A. 我生成 SSH Key（放在工作区，不碰你的用户目录），你把公钥贴到 GitHub → 之后 `git push` 走 `ssh.github.com:443`；
   - B. 你开代理（检测到你配过 `127.0.0.1:7890`），我用代理推送；
   - C. 先只做本地提交，最后一次性推送。
3. **大模型 Key**：有 DeepSeek/OpenAI Key 吗？没有也没关系 —— 默认 mock 模式可完整演示，有 Key 时切 `LLM_MODE=real` 增强报告文案。

> 你确认后，我按阶段②开始写 Java 侧代码：**每完成一个接口 → 跑测试 → 通过后 commit → 再做下一个**，并在每轮汇报里附上测试结果与提交号。
