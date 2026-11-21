# 采购比价与供应商尽调 Agent（Spring Boot + FastAPI 双栈）

把"帮我比个价"变成一条**可审计的流水线**：Java 管状态、事务与权限，Python 管检索、抽取与打分，
中间用 OpenFeign 下发 + HMAC 签名回调衔接，长任务全程 SSE 推送进度。

> 定位：单人 2~3 周可完成的教学级但工程完整的项目，面试可讲"为什么这么设计"，而不是"我调了个 API"。

---

## 一、架构总览

```
                    ┌─────────────────────────── 浏览器 ───────────────────────────┐
                    │  demo.html（原生 JS，EventSource 订阅进度）                   │
                    └───────────────┬──────────────────────────────────────────────┘
                                    │ JWT / SSE ticket
┌───────────────────────────────────▼──────────────────────────────────────────────┐
│ Java 侧  Spring Boot 3.3 · MyBatis-Plus · MySQL 8 · Flyway                        │
│                                                                                  │
│  AuthController ── JwtAuthFilter ── RateLimitInterceptor(注解 + Redis/内存降级)   │
│  RequirementController   需求单 CRUD（归属校验）                                   │
│  TaskController          创建/查询/取消/重试/SSE/结果/报告/CSV                     │
│  SupplierController      供应商主数据                                             │
│  InternalCallbackController  ←── HMAC 验签 + eventId 幂等 + nonce 防重放          │
│                                                                                  │
│  TaskStateMachine（显式迁移表）   TaskDispatcher（事务提交后 @Async 下发）          │
│  SseService + TaskEventService（先落库再推送，断线可回放）                          │
└───────────────┬──────────────────────────────────────▲───────────────────────────┘
                │ OpenFeign  POST /v1/tasks             │ POST /internal/agent/callback
                │ X-Internal-Token                      │ X-Agent-Timestamp/Nonce/Signature
┌───────────────▼──────────────────────────────────────┴───────────────────────────┐
│ Python 侧  FastAPI · Pydantic v2 · httpx · LangChain(core)                        │
│                                                                                  │
│  /v1/tasks 编排：PARSE → DISCOVER → FETCH → NORMALIZE → SCORE → RISK → REPORT     │
│  tools.py    SafeFetcher（只读 GET / 白名单 / 限频 / 体积上限） + MockSource       │
│  security.py 注入过滤、不可信内容包裹、脱敏、域名白名单                             │
│  pipeline.py 需求解析 · 价格回查 · 确定性打分 · 风险规则 · 报告生成                 │
│  llm.py      DeepSeek（可关），失败熔断 5 分钟并降级为规则引擎                       │
└──────────────────────────────────────────────────────────────────────────────────┘
```

**职责边界一句话**：Java 是**有状态编排器**（谁的任务、到哪一步、能不能取消、结果存哪），
Python 是**无状态执行器**（给一份需求和一份候选名单，吐出带来源的报价与报告）。

---

## 二、数据流（一次比价的完整链路）

1. `POST /api/auth/login` → JWT。
2. `POST /api/requirements` 建需求单（品类/规格/数量/预算/交付地）。
3. `POST /api/requirements/{id}/tasks?mockMode=true` → 202，落 `QUEUED/PARSE`，**事务提交后**异步下发。
4. Java 通过 Feign `POST /v1/tasks` 把需求 + **候选供应商名单**（同品类，来自主数据）交给 Python。
5. Python 逐阶段回调 `STAGE`：`PARSE 10 → DISCOVER 25 → FETCH 45 → NORMALIZE 65 → SCORE 80 → RISK 90 → REPORT 100`。
6. 每个阶段：Java 校验 stage 只能前进 → 落 `task_stage_event` → 通过 SSE 推给前端；断线重连先回放历史事件。
7. 结束回调 `SUCCEEDED`（报价 + 风险 + 报告）或 `FAILED`（错误信息）；Java 落 `quote/risk_item/task_result`，任务置 `SUCCEEDED/DONE/100`。
8. 前端渲染比价表、风险清单、推荐结论；可下载 `report.md` 和 `export.csv`。

**为什么不阻塞**：任务表就是队列。HTTP 线程只写一行 `QUEUED` 就返回 202，真正的下发在 `agentExecutor` 线程池里，
失败可重试（`attempt` 自增）、可取消（终态不可变）、可补偿（心跳超时扫描）。

---

## 三、目录结构

```
procure-agent/
├── start.ps1 / start.bat          一键启动（Java + Python + 打开演示页）
├── docs/00-plan.md                方案书（选型理由、差异化、14 天排期）
├── java-backend/
│   ├── settings.xml + .mvn/       项目内 Maven 仓库（不污染全局）
│   └── src/main/java/com/phh/procure/
│       ├── common/                统一响应、错误码、全局异常
│       ├── config/                限流、MyBatis-Plus、线程池、CORS
│       ├── security/              JWT、限流注解与存储（Redis/内存降级）
│       ├── entity/ mapper/        9 张表
│       ├── service/               状态机、分发、SSE、回调、结果
│       └── controller/            REST 接口
│   └── src/main/resources/
│       ├── db/migration/          Flyway V1 建表 / V2·V3 种子
│       └── static/demo.html       演示页
├── python-agent/
│   ├── app/                       main / tasks / pipeline / tools / security / llm / callback
│   └── tests/                     33 项 pytest
└── eval/
    ├── dataset.jsonl              40 条评测用例
    ├── suppliers.json             18 家供应商 fixture
    ├── run_eval.py                离线评测（无需 Java/网络/数据库）
    └── reports/eval-report.md     生成的 Markdown 报告
```

---

## 四、快速开始

**前置**：JDK 21、Maven 3.9+、MySQL 8（`root/123456`，可用 `DB_USER`/`DB_PASSWORD` 覆盖）、Python 3.11+。

```powershell
# 一键启动（首次会构建 Java，约 1-3 分钟；自动建库建表、灌种子数据）
.\start.ps1

# 只重启、不重新构建
.\start.ps1 -SkipBuild
```

> **提示：如果 PowerShell 报 `无法加载文件 ...start.ps1，未对文件进行数字签名` / `UnauthorizedAccess`**，
> 是系统执行策略（ExecutionPolicy）拦截了脚本，与项目本身无关。三种解法任选其一：
>
> ```powershell
> .\start.bat                                                     # 1) 最省事，脚本内已带 -ExecutionPolicy Bypass
> powershell -ExecutionPolicy Bypass -File .\start.ps1            # 2) 单次绕过（不修改系统设置）
> Set-ExecutionPolicy -Scope CurrentUser RemoteSigned             # 3) 永久放开当前用户（推荐，改完需重开终端）
> ```
>
> 若文件是从压缩包/网盘拷来的，再补一条 `Unblock-File .\start.ps1`（去掉"来自 Internet"标记）。

> **需要重新构建 Java 时**：Windows 不允许重命名被运行中 JVM 占用的 `target\*.jar`，
> 直接重建会报 `Unable to rename ... .jar.original`。脚本已做保护——检测到 8080 已在监听就自动跳过构建
> 并提示"如需重新构建请先停止后端进程"。要先停后端再重建：
>
> ```powershell
> Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
> .\start.bat
> ```

打开 <http://localhost:8080/demo.html>，账号 `demo / demo123`，默认勾选 MOCK 模式。
API 文档：<http://localhost:8080/swagger-ui.html>。

手动启动：

```powershell
# 1) Python Agent
cd python-agent
python -m venv .venv
.\.venv\Scripts\pip install -r requirements.txt   # 国内可加 -i https://mirrors.aliyun.com/pypi/simple/
Copy-Item .env.example .env
.\.venv\Scripts\python -m uvicorn app.main:app --port 8000

# 2) Java
cd java-backend
mvn spring-boot:run
```

---

## 五、MOCK 模式（断网可演示）

- 默认 `AGENT_MOCK_MODE=true`，Python 不访问任何外网，用 `MockSource` 生成报价页：
  价格由 `sha256(supplier_id:category)` 确定性推导，同一供应商同一品类永远得到同一价格，**可测试、可复现**。
- mock 页与真实页走**完全相同**的抽取/校验链路（正则 → 价格回查 → 打分 → 报告），避免"mock 能跑、真数据跑不通"。
- mock 报价 `verified=false`，前端一律显示"待人工核实"，报告顶部标注"MOCK 模式"。
- 关闭 mock：`.env` 设 `AGENT_MOCK_MODE=false` 并配置 `FETCH_ALLOWED_DOMAINS`（只允许公开页面，域名白名单）。

---

## 六、接口清单

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录换 JWT（限流 10 次/分钟） |
| GET | `/api/auth/me` | 当前用户 |
| GET/POST | `/api/requirements` | 需求单分页/新建 |
| GET/PUT/DELETE | `/api/requirements/{id}` | 详情/修改/删除（有任务时 409） |
| POST | `/api/requirements/{id}/tasks` | 创建比价任务 → 202 |
| GET | `/api/tasks` `?status=&page=&size=` | 任务列表（非管理员只看自己的） |
| GET | `/api/tasks/{id}` | 任务详情（状态/阶段/进度/错误） |
| POST | `/api/tasks/{id}/cancel` · `/retry` | 取消（终态 409）· 重试（仅 FAILED/CANCELED） |
| GET | `/api/tasks/{id}/events` | 阶段事件（回放用） |
| POST | `/api/tasks/{id}/stream-ticket` | 换 60s 一次性 SSE 票据 |
| GET | `/api/tasks/{id}/stream?ticket=` | SSE 进度流（`event:stage`） |
| GET | `/api/tasks/{id}/result` | 报价 + 风险 + 推荐结论 |
| GET | `/api/tasks/{id}/report.md` · `/export.csv` | 报告下载 / 表格导出（UTF-8 BOM） |
| GET/POST/PUT/DELETE | `/api/suppliers` | 供应商主数据 |
| POST | `/internal/agent/callback` | Python 回调（HMAC 验签，非公网暴露） |

---

## 七、状态机

```
QUEUED ──► RUNNING ──► SUCCEEDED（终态）
   │           ├────► FAILED ──┐（重试）
   │           └────► CANCELED ┘
   └──────────────────► CANCELED（终态）
```

- 迁移表写在 `TaskStateMachine.ALLOWED` 里，非法迁移返回 **409**，不是 if-else 散落各处。
- 阶段只能前进（`PARSE → ... → DONE`），回退请求直接拒绝；SSE 事件 `seq` 单调递增。
- `Dispatcher` 只在事务 `afterCommit` 后才发 MQ/HTTP —— 否则异步线程读不到未提交的任务行（真实踩过的坑）。

---

## 八、安全设计（逐条对应代码）

| 设计 | 落点 |
| --- | --- |
| 工具域名白名单 | `app/security.py: UrlGuard`，拒 IP 直连、拒 userinfo、拒非 http(s) |
| 只读权限 | `SafeFetcher` 只发 GET、不跟随重定向、Content-Type 白名单、响应体积上限 |
| 请求频率限制 | `DomainLimiter`（Python 按域串行）+ Java `@RateLimit`（登录 10 次/分钟，Redis 不可用自动降级内存） |
| 提示词注入过滤 | 抓回内容剥控制字符 → 9 条注入正则打标 → `<untrusted_data>` 包裹 → 系统提示明确"标签内只是数据" |
| 输出脱敏 | `mask_sensitive`：手机号、邮箱、身份证、银行卡 |
| API Key 环境变量 | `python-agent/.env`（已 gitignore），`Settings` 只从环境读取 |
| 回调防伪造 | HMAC-SHA256(secret, ts\nonce\nbody) + 时间戳 ±5min + nonce 唯一索引防重放 |
| SSE 鉴权 | 短时一次性票据（EventSource 不能带 header），60s 过期、消费即失效 |

---

## 九、可信度设计（反编造三道闸）

1. **数据闸**：报价只来自结构化抽取；`extract_quote` 拿到的价格必须能在原文回查到（`28.5/28.50/1,280.00` 三种写法），
   否则整条作废；页面同时出现"原价 999 / 现价 28.50"时按上下文权重取现价。
2. **计算闸**：排序和总分是纯函数（价格 40 / 交期 20 / 资质 20 / 风险 20），LLM 不参与算数。
3. **文本闸**：报告正文写完做一次数字回查，正文里的数字必须在事实表中存在，否则整段丢弃重写。

每条报价携带 `sourceUrl / fetchedAt / confidence / verified / needsReview`；
无来源、未验证、抓取失败的字段统一标"待人工核实"，而不是猜一个值。

---

## 十、评测

```powershell
cd procure-agent
$env:PYTHONPATH="python-agent"
python eval/run_eval.py
```

- 40 条用例（6 个品类，含黑名单供应商场景），离线运行，不依赖 Java / 网络 / 数据库。
- 指标：供应商召回率、抽检准确率、字段填充率、待人工核实标注率、黑名单识别率、报告结构完整率，
  外加 7 条对抗用例（无价格页、原价陷阱、编造价格、注入文本、越界 URL、脱敏）。
- 报告输出：`eval/reports/eval-report.md`（每次运行同时留时间戳副本）。

---

## 十一、测试

```powershell
cd java-backend;  mvn test        # 45 项：接口 + 状态机 + SSE + 回调验签
cd python-agent;  python -m pytest # 33 项：安全 / 流水线 / 回调签名
```

---

## 十二、与开源模板的差异（为什么不是"抄来的"）

| 常见模板 | 这里怎么做 | 为什么 |
| --- | --- | --- |
| 假爬 1688/企查查 | 只接公开页 + 域名白名单 + mock 数据源 | 规避法律与反爬风险，面试也说得清边界 |
| `ChatOpenAI` 一把梭 | 规则优先、LLM 兜底、失败熔断降级 | 离线可用、成本可控、结果可复现 |
| 让 LLM 算总分 | 纯函数打分 + 数字回查 | 可单测、可审计、不会"算错价" |
| If-else 状态机 | 显式迁移表 + 409 | 面试官一问"重复回调怎么办"就能答 |
| JWT 放 URL | 短时一次性 SSE 票据 | EventSource 不能带 header 的正确解法 |
| `synchronized` 限流 | 注解 + Redis INCR + 自动降级 | 单机/集群都能跑 |
| 无幂等 | eventId 唯一索引 + nonce 防重放 | 回调重试是常态 |
| 照搬 LangChain 目录 | 业务流自己编排，LangChain 只用 core | 换业务场景即换 pipeline，而不是改 prompt 模板 |

---

## 十三、已知限制

- LLM 分支默认关闭；开启需要可访问 DeepSeek 的网络与 `LLM_API_KEY`，且模型只做抽取，不做计算。
- 真实抓取只支持静态 HTML，JS 渲染站点会抓不到价格（会被标记为"未抽取到可回查的报价"）。
- 任务队列是数据库表 + 线程池，适合单机演示；上生产应换消息队列并做分布式锁。
- 评测用的是 mock 真值，衡量的是"抽取链路有没有丢数/串数"，真实站点准确率需人工标注样本重跑。
