# 项目介绍：Procure Agent（采购比价与供应商尽调 Agent）

> 一句话：把采购员"帮我比个价"的一句话，变成一条**可审计、可回放、不编数**的流水线。

## 1. 它叫什么

| 项 | 内容 |
|---|---|
| 项目名 | **Procure Agent** |
| 中文名 | 采购比价与供应商尽调 Agent |
| 定位 | 面向中小采购场景的"比价 + 尽调"自动化工作台 |
| 形态 | 双栈 Web 应用：Java 业务中台 + Python Agent 执行器 + 单页演示前端 |
| 仓库 | https://github.com/Phh1235/- （`-` 是仓库名的字面值） |

## 2. 三个版本的自述

**15 秒（电梯版）**
> 输入一条采购需求（品类、规格、数量、预算、交付地），系统自动找候选供应商、抓报价页、清洗归一化、按确定性公式打分、输出风险提示和推荐结论，全程进度实时推送，每条数据都带来源 URL 和时间戳。

**1 分钟（面试开场）**
> 这是一个双栈项目。Java 侧用 Spring Boot 做业务中台，管需求单、供应商主数据、任务状态机、JWT 鉴权、限流和 SSE 进度推送；Python 侧用 FastAPI + LangChain 做无状态的 Agent 执行器，负责需求解析、按白名单抓取公开报价页、正则+大模型混合抽取、归一化和打分、生成带来源标注的报告。两边用 OpenFeign 下行、HMAC 签名回调上行，长任务异步执行不阻塞请求。
>
> 设计上最核心的一条是**"模型只读文本，不参与算钱"**：价格必须来自原文抽取并回查，打分是确定性公式，报告里的数字要能对回事实表——所以它不会编报价。

**3 分钟（完整版）**
> 见 `docs/00-plan.md` 的方案书，以及下面第 4~6 节。

## 3. 技术栈

| 层 | 技术 | 用途 |
|---|---|---|
| 语言/运行时 | Java 21、Python 3.11+ | 双栈 |
| Web 框架 | Spring Boot 3.3.5、FastAPI | REST + SSE |
| 持久层 | MyBatis-Plus 3.5.7、MySQL 8、Flyway | 9 张表、版本化迁移 |
| 鉴权 | Spring Security + JJWT 0.12.6 | 无状态 JWT、过滤器链 |
| 服务间通信 | Spring Cloud OpenFeign 2023.0.3 | 下行下发任务 |
| 回调安全 | HMAC-SHA256 + nonce + 时间戳窗口 | 防伪造、防重放、幂等 |
| 实时推送 | SseEmitter + 一次性票据 | 七阶段进度 |
| 限流 | 自定义注解 + 拦截器 + Store 抽象 | Redis / 内存自动降级 |
| Agent 编排 | LangChain Core（LCEL）、OpenAI SDK（DeepSeek 兼容） | `prompt \| llm \| parser` |
| 抓取 | httpx（流式、限大小、限频、白名单） | 只读公开页 |
| 评测 | 纯 Python 离线 harness | 40 条测试集 → Markdown 报告 |
| 前端 | 单文件 HTML + 原生 JS + ECharts(CDN) | 驾驶舱式演示页 |
| 文档 | springdoc-openapi | Swagger UI |

## 4. 架构

```
┌──────────────────────────── 浏览器 ────────────────────────────┐
│  demo.html（单文件）：向导 → 七阶段 stepper → 结果看板          │
└───────────────┬───────────────────────────────▲────────────────┘
        REST+JWT │                               │ SSE(stage 事件)
┌───────────────▼───────────────────────────────┴────────────────┐
│ Java 业务中台 (8080)  —— 有状态编排器                           │
│  Auth/JWT · 限流 · 需求单 · 供应商主数据 · 任务状态机            │
│  task_stage_event（事件溯源）· quote/risk/result 持久化          │
│  定时补偿 · CSV/报告导出                                        │
└───────────────┬───────────────────────────────▲────────────────┘
      Feign 下发 │ (X-Internal-Token)             │ HMAC 回调
                ▼                                │ (ts+nonce+body)
┌────────────────────────────────────────────────────────────────┐
│ Python Agent (8000) —— 无状态执行器                             │
│  PARSE 需求解析 → DISCOVER 候选 → FETCH 白名单抓取               │
│  → NORMALIZE 归一化 → SCORE 确定性打分 → RISK 风险规则           │
│  → REPORT 报告生成（数字回查）                                   │
│  安全：UrlGuard/SafeFetcher/注入过滤/脱敏/域名限频               │
└────────────────────────────────────────────────────────────────┘
                     MySQL 8（Flyway 管理 9 张表）
```

## 5. 一条需求的旅程（8 步）

1. 用户填需求单（品类/规格/数量/预算/交付地）→ `POST /api/requirements`
2. 创建任务 → `POST /api/requirements/{id}/tasks?mockMode=true` 返回 **202 + taskId**（`QUEUED`）
3. 事务提交后（`afterCommit`）异步 Feign 下发，任务转 `RUNNING`
4. Java 从供应商主数据里挑同品类候选（最多 20 家）随请求下发
5. Python 逐家取证：mock 走确定性模拟页，真实模式走白名单抓取
6. 每个阶段回调一次进度：Java 先落 `task_stage_event` 再 SSE 推送
7. 终态回调携带 quotes/risks/report：Java 校验状态迁移、落库、置 `SUCCEEDED`
8. 前端拿 `GET /api/tasks/{id}/result` 渲染看板；`/report.md`、`/export.csv` 可直接下载

## 6. 五个关键设计（面试重点）

**① 反编造三道闸**
- 数据闸：模型输出的 schema 里**没有价格字段**，价格只能来自原文正则抽取；
- 计算闸：总分 = 价格 40 + 交期 20 + 资质 20 + 风险 20，确定性公式，模型不参与；
- 文本闸：报告正文的数字必须能在事实表里找到，找不到整段丢弃（`narrative_guard`）。

**② 状态机 + 事件溯源**
`QUEUED → RUNNING → SUCCEEDED/FAILED/CANCELED`，终态不可变，非法迁移 409；阶段只准前进。每次阶段变化都落一条 `task_stage_event`（带 seq），SSE 断线可回放。

**③ afterCommit 下发（真 bug 修出来的）**
`@Async` 线程读不到未提交事务 → 任务永久 `QUEUED`。改成注册 `TransactionSynchronization.afterCommit` 再下发；同时下发回写改成"带条件的字段更新"，避免覆盖用户刚点的取消。

**④ 跨栈安全边界**
内部接口用 `X-Internal-Token`；回调用 `HMAC_SHA256(secret, ts\nnonce\nbody)`，±5 分钟时间窗、nonce 唯一索引、eventId 幂等、常量时间比较。抓取侧 SSRF 四校验 + 禁重定向 + 大小/频率上限。

**⑤ 可信度设计**
每个字段带 `{value, currency, unit, source_url, fetched_at, confidence, verified, needs_review}`；无法验证的标"待人工核实"；mock 数据统一标 `[MOCK]`。

## 7. 工程数据

| 指标 | 数值 |
|---|---|
| Java 主代码 / 测试 | ~2,590 行 / ~1,090 行 |
| Python Agent / 测试 / 评测 | ~1,380 行 / 242 行 / 386 行 |
| 演示页 | 单文件 1,482 行（零构建） |
| 单元 & 接口测试 | Java 45 项、Python 33 项，全绿 |
| 评测集 | 40 条需求 × 6 品类，另 7 条对抗用例 |
| 评测结果 | 召回/填充/抽检/待核实/黑名单/报告结构 均 100%，对抗 7/7 |
| 数据表 | 9 张（Flyway V1~V3） |
| 供应商主数据 | 18 家 mock（含 1 家黑名单、1 家已注销） |

> 100% 的口径要主动说明：mock 模式下真值与页面同源，只证明流水线正确，不代表真实网页抽取准确率。

## 8. 与常见开源模板的差异

| 常见做法 | 本项目 |
|---|---|
| AutoGPT/多智能体互相聊 | 单 Agent + 确定性打分器，决策可复现 |
| ChatOpenAI 一把梭 | 正则优先、LLM 兜底、原文回查、失败熔断 300s |
| 让模型算总分 | 公式算分，模型只抽取 |
| 爬电商站 | 只抓白名单公开页 + mock，可断网演示 |
| if-else 改状态 | 状态机表 + 事件溯源 + 409 |
| JWT 拼在 URL | 60 秒一次性 SSE 票据 |
| synchronized 限流 | 注解 + 拦截器 + Redis/内存双实现 |
| 回调裸奔 | HMAC + 时间窗 + nonce + 幂等 |
| 只说"准确率 95%" | 40 条离线评测 + 明确定义与口径 |

## 9. 边界（不做什么）

- 不做多智能体协作、不做模型微调、不做私有化部署；
- 不爬需要登录/验证码/付费的站点（1688、企查查等一律不碰）；
- 不做真实支付、下单、合同流转；
- Python 侧不持久化任务状态，进程重启由 Java 侧重试/补偿兜底。

## 10. 怎么跑

```powershell
# 一键：起 Python(8000) + Java(8080) + 打开浏览器
.\start.ps1
# 只起 Java（已有 Python 在跑）
.\start.ps1 -SkipBuild
```

- 演示页：http://localhost:8080/demo.html ，账号 `demo / demo123`，默认 mock 模式（不访问外网）
- 接口文档：http://localhost:8080/swagger-ui.html
- 评测：`python eval/run_eval.py` → `eval/reports/eval-report.md`

## 11. 简历里怎么写这一句

> **Procure Agent｜采购比价与供应商尽调 Agent（双栈）**
> Java 21 + Spring Boot 3 + MyBatis-Plus + MySQL/Flyway 构建业务中台，Python FastAPI + LangChain(LCEL) 实现无状态 Agent；OpenFeign 下发 + HMAC 签名回调打通双栈，SSE 实时推送七阶段进度；设计"数据/计算/文本"三道闸防止模型编造报价，配合域名白名单、只读抓取、注入过滤与输出脱敏；自建 40 条离线评测集，产出召回率/字段填充率/抽检准确率报告。

关键词：**状态机、事件溯源、幂等与防重放、SSRF 防护、反编造设计、离线可评测**。
