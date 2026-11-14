# 学习指南：把这个项目当教材读

> 目标不是"跑通一个 demo"，而是**能从任意一行代码讲到设计动机**。
> 面试官问的从来不是"你用了什么"，而是"你为什么这么用、不这么用会怎样"。

## 0. 怎么用这份指南

三种用法，按你的时间选：

| 场景 | 用法 | 预计 |
|---|---|---|
| 面试前 3 天 | 只读第 7 节自测题 + 第 9 节速查卡，答不上来的回查对应小节 | 6~8 小时 |
| 两周跟练 | 按第 8 节路线，每天一个主题，读完就做第 6 节的动手练习 | 每天 2 小时 |
| 当教材系统学 | 第 2~6 节顺序读，每节都打开对应源码对照 | 3~4 周 |

一条铁律：**每读完一节，合上文档，用自己的话讲 60 秒**。讲不出来就是没学会，回去看代码。

---

## 1. 学习地图

| 层 | 模块 | 你要能讲清楚的 | 代码位置 | 高频追问 |
|---|---|---|---|---|
| 接入 | JWT 鉴权 | 无状态认证、过滤器链、为什么不用 Session | `security/JwtAuthFilter.java`、`security/SecurityConfig.java` | token 放 URL 行不行 |
| 接入 | 限流 | 注解 + 拦截器 + 存储抽象、Redis 挂了怎么办 | `security/ratelimit/` | 为什么不用 synchronized |
| 业务 | 状态机 | 合法迁移表、终态不可变、并发下谁赢 | `service/TaskStateMachine.java` | 用 if-else 不行吗 |
| 业务 | 事务 + 异步 | 为什么必须 afterCommit | `service/TaskService.java`、`service/TaskDispatcher.java` | @Async 读不到数据 |
| 业务 | SSE 推送 | EventSource 不能带 header → 一次性票据 | `service/StreamTicketService.java`、`controller/TaskController.java` | 为什么不用 WebSocket |
| 集成 | Feign + 回调 | HMAC 验签、时间戳窗口、nonce 防重放、幂等 | `client/CallbackSigner.java`、`service/AgentCallbackService.java` | 重放攻击怎么防 |
| 数据 | Flyway | 版本化建表、为什么不用 ddl-auto | `resources/db/migration/` | 生产怎么回滚 |
| Agent | LCEL 编排 | 七阶段流水线、为什么不用 AgentExecutor | `python-agent/app/tasks.py` | 为什么不做多智能体 |
| Agent | 安全抓取 | SSRF 四道校验、禁重定向、大小与频率上限 | `python-agent/app/security.py`、`app/tools.py` | 内网地址怎么防 |
| Agent | 反编造 | 数据闸/计算闸/文本闸 | `app/pipeline.py` | 怎么保证报价不是编的 |
| 评测 | 离线评测 | 40 条数据集、召回/填充/抽检口径 | `eval/run_eval.py` | 100% 是不是自欺欺人 |

---

## 2. Java 侧：10 个必须吃透的点

### 2.1 Spring Boot 启动与配置分层

- **为什么**：配置文件散落在 `@Value` 里，改一个参数要全局搜。
- **代码**：`config/AppProperties.java`（`@ConfigurationProperties(prefix="app")`，嵌套 `Jwt/Agent/RateLimit/Cors`）、`resources/application.yml`。
- **关键点**：所有外部可调参数（超时、密钥、白名单、mock 开关）都从配置来 → 才能做到"改配置不改代码"。
- **动手**：把 `app.agent.timeout-ms` 改成 1000，观察 Python 慢响应时任务如何失败。
- **会被问**：`@ConfigurationProperties` 和 `@Value` 区别？→ 批量绑定、类型安全、支持嵌套与校验、可被 IDE 提示。

### 2.2 统一响应与全局异常

- **代码**：`common/ApiResponse.java`（record）、`ErrorCode`、`BizException`、`GlobalExceptionHandler`。
- **关键点**：业务异常带错误码（40000 参数、40100 未认证、40300 越权、40900 冲突、42900 限流、50200 下游失败），HTTP 状态码与业务码分离但语义对齐。
- **动手**：故意触发一次非法状态迁移，看返回 409 + `code=40900`。
- **会被问**：为什么不直接把异常 message 返回给前端？→ 泄露内部实现；对外只给稳定错误码和可读文案。

### 2.3 MyBatis-Plus 的正确用法

- **代码**：`mapper/*.java`（只继承 `BaseMapper`，不写 XML）、`config/MybatisPlusConfig.java`（分页插件 + `maxLimit=200`）、`config/AuditMetaObjectHandler.java`（自动填充 created_at/updated_at）。
- **关键点**：能不用 XML 就不用；分页必须设上限，否则 `size=999999` 一次打爆内存。
- **动手**：请求 `/api/suppliers?size=10000`，确认被截断到 200。
- **会被问**：`LambdaQueryWrapper` 相比字符串列名好在哪？→ 编译期/重构安全，列名不会拼错。

### 2.4 Flyway 而不是 `ddl-auto=update`

- **代码**：`resources/db/migration/V1__init_schema.sql`、`V2__seed_supplier.sql`、`V3__seed_supplier_more.sql`。
- **关键点**：表结构是**版本化资产**，可 review、可回放、可在生产按序执行；`ddl-auto` 只能用于本地实验。
- **动手**：新建 `V4__add_supplier_index.sql` 加个索引，重启看 Flyway 如何只执行增量。
- **会被问**：Flyway 和 Liquibase 区别？→ 前者 SQL 优先、轻；后者 changelog 抽象、跨库强。
- **注**：DDL 写成 MySQL/H2 双兼容（不用 `ENGINE`、`COMMENT`、`ON UPDATE`），测试才能跑在 H2 上。

### 2.5 JWT + Spring Security 过滤器链

- **代码**：`security/JwtService.java`（HS256，密钥 < 32 字节直接启动失败）、`JwtAuthFilter.java`（解析失败静默匿名，交给后续 401）、`SecurityConfig.java`（无状态、放行白名单、401/403 返回统一 JSON）。
- **关键点**：认证失败**不要**在过滤器里直接写响应后继续放行；要么抛给 `AuthenticationEntryPoint`，要么不设置 SecurityContext。我们选后者 + 入口点统一处理。
- **动手**：把 token 里 `sub` 改成不存在的用户，观察 `/api/auth/me` 的行为。
- **会被问**：JWT 能撤销吗？→ 不能天然撤销；工程上用短过期 + 刷新令牌 / 黑名单 / token 版本号。

### 2.6 注解式限流 + 存储抽象 + 自动降级

- **代码**：`security/ratelimit/RateLimit.java`（注解）、`RateLimitInterceptor.java`、`RateLimitStore` 接口、`MemoryRateLimitStore`（`ConcurrentHashMap.compute` 固定窗口）、`RedisRateLimitStore`（INCR + expire）、`config/RateLimitConfig.java`（启动 ping Redis，失败降级内存）。
- **关键点**：**限流是横切关注点**，用注解声明在接口上，拦截器统一执行；存储换实现不改业务代码。
- **动手**：连错 10 次密码，第 11 次拿到 429；再把 `app.rate-limit.mode` 改成 `redis`（没装 Redis），确认能降级启动。
- **会被问**：为什么不用 `synchronized`？→ 只锁单机、阻塞线程、多实例无效；限流是"计数 + 时间窗"，该用原子计数。

### 2.7 状态机：把 if-else 变成一张表

- **代码**：`service/TaskStateMachine.java`。
  ```java
  QUEUED  -> {RUNNING, CANCELED}
  RUNNING -> {SUCCEEDED, FAILED, CANCELED}
  FAILED  -> {QUEUED}   // retry
  CANCELED-> {QUEUED}
  SUCCEEDED -> {}       // 终态不可变
  ```
- **关键点**：所有状态变更**只走这一个入口**；非法迁移抛 409；阶段（stage）只允许前进，回退一律拒绝——防止乱序回调把进度拉回去。
- **动手**：给已完成任务发 `cancel`，确认 409 而不是静默成功。
- **会被问**：并发下两个请求同时迁移怎么办？→ 靠"带条件的 UPDATE"（`WHERE status = 旧值`）+ 影响行数判断，或行锁；本项目在 Service 层先查后改，属于乐观假设，量级变大要换 `UPDATE ... WHERE status=?`。

### 2.8 事务 + 异步：afterCommit 这一课（**本项目最值钱的坑**）

- **现象**：任务一直卡在 `QUEUED`。根因：`TaskService.create()` 在事务里插入任务，然后 `@Async` 线程里 `selectById(taskId)` —— 异步线程用的是**另一个连接**，读不到未提交的数据，`task == null` 直接 return，异常被吞。
- **修法**（`service/TaskService.java`）：
  ```java
  if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override public void afterCommit() { dispatcher.dispatch(taskId); }
      });
  } else {
      dispatcher.dispatch(taskId);
  }
  ```
- **关键点**：**异步任务的下发时机必须是"事务提交之后"**；`@Async` 与 `@Transactional` 同时标注在同一个方法上时，异步线程里根本没有事务上下文。
- **动手**：把 `dispatchAfterCommit` 改回直接 `dispatcher.dispatch(id)`，跑 `TaskApiTest`，看它是不是又挂。
- **会被问**：为什么不在 `@Async` 方法上加 `@Transactional`？→ 两个注解作用在不同线程/代理上，语义不可叠加；异步方法里的"事务"是另一个独立事务。

### 2.9 SSE：EventSource 的鉴权死结与解法

- **问题**：浏览器 `EventSource` 不能自定义 Header，JWT 又不想拼在 URL 上（会进日志、Referer）。
- **解法**：`POST /api/tasks/{id}/stream-ticket` 换一张 **60 秒一次性票据**（`StreamTicketService`，内存 Map，`consume` 即删），再 `GET /stream?ticket=xxx`。
- **代码**：`service/SseService.java`（`Map<Long, List<SseEmitter>>`）、`service/TaskEventService.java`（**先落库 `task_stage_event` 再推送**）、`controller/TaskController.java`。
- **关键点**：先落库再推送 = 断线重连可以**回放**；推送失败要从注册表移除，否则内存泄漏。
- **动手**：打开 `/demo.html` 跑一次任务，中途刷新页面，看进度能否继续。
- **会被问**：为什么不用 WebSocket？→ 单向进度推送，SSE 更轻（HTTP/1.1、自动重连、无需协议升级）；代价是不能双工。

### 2.10 Feign + HMAC 回调：跨栈协作的安全边界

- **代码**：`client/AgentClient.java`（`url` 来自配置、`X-Internal-Token`）、`client/CallbackSigner.java`、`controller/InternalCallbackController.java`、`service/AgentCallbackService.java`。
- **签名算法**：`HMAC_SHA256(secret, timestamp + "\n" + nonce + "\n" + rawBody)`，头里带 `X-Agent-Timestamp/Nonce/Signature`。
- **四道防线**：① 时间戳 ±5 分钟；② nonce 唯一索引（重放直接 401）；③ eventId 幂等（重复投递直接丢弃）；④ `MessageDigest.isEqual` 常量时间比较（防时序侧信道）。
- **关键点**：验签必须对**原始字节**做，不能对反序列化后的对象做（字段顺序/空格会变）。
- **动手**：用 `CallbackSigner.sign` 造一个 6 分钟前的签名，确认被拒。
- **会被问**：为什么不直接用 JWT 做服务间认证？→ 回调是"服务端到服务端 + 需要防重放 + 需要绑定请求体"，HMAC 更贴合；JWT 适合"携带身份"。

---

## 3. Python 侧：7 个必须吃透的点

### 3.1 FastAPI 生命周期与内部鉴权

- **代码**：`app/main.py`（`lifespan` 里创建 `httpx.AsyncClient`、`LlmClient`、`TaskManager`，退出时关闭）、`require_internal_token`（`hmac.compare_digest`）。
- **关键点**：HTTP 客户端**全局复用**，不要每个请求 new 一个（连接池 + 握手开销）；内部接口用共享 token，公网不可达。
- **动手**：不带 `X-Internal-Token` 调 `/v1/tasks`，确认 401。

### 3.2 pydantic v2 与跨语言契约

- **代码**：`app/schemas.py`。
- **关键点**：Java 用 camelCase、Python 用 snake_case，靠 `alias` + `populate_by_name` 对齐；`Decimal` 出网转 `float`；`datetime` 统一 `"%Y-%m-%dT%H:%M:%S"`（Java `LocalDateTime` 能直接反序列化）。
- **动手**：给 `QuoteItem` 加一个字段但不给默认值，看 Java 回调会不会 400。

### 3.3 asyncio 任务编排

- **代码**：`app/tasks.py`（`TaskManager` 内存字典 + `asyncio.create_task`，七阶段各自回调进度）。
- **关键点**：`await` 是让出控制权的地方；阶段之间的 `sleep` 是为了让演示可见（`AGENT_STAGE_DELAY_MS`）；任务状态必须自己维护，FastAPI 不会替你记。
- **会被问**：进程重启任务丢了怎么办？→ 本项目承认"Python 是无状态执行器"，可靠性由 Java 的状态机 + 补偿/重试兜底。

### 3.4 安全抓取（SSRF 防护的正确姿势）

- **代码**：`app/security.py`（`UrlGuard`）、`app/tools.py`（`SafeFetcher`）。
- **四道校验**：协议只允许 http/https；拒绝 URL 里带 userinfo（`http://a@b/`）；拒绝 IP 直连（`http://127.0.0.1`）；host 必须等于白名单或 `.白名单` 后缀。
- **再加三层**：`follow_redirects=False`（3xx 直接拒绝，防跳转到内网）、`Content-Type` 白名单、累计读取上限 `max_bytes`。
- **动手**：把白名单改成 `example.com`，尝试抓 `https://example.com.evil.com`，确认被拒（后缀匹配要带点）。

### 3.5 LangChain LCEL 的克制用法

- **代码**：`app/llm.py`、`app/pipeline.py`。
- **关键点**：这里只需要"提示词 → 模型 → 结构化输出"，`prompt | llm | parser` 就够；**不用 AgentExecutor**（不需要模型自己选工具），也**不用向量库**（任务是抽取 + 规则打分，不是语义检索）。
- **工程保护**：失败熔断 300 秒（`_broken_until`），期间直接走正则规则引擎，保证断网/欠费也能跑。
- **会被问**：为什么不用 RAG？→ 没有"知识库问答"的需求；供应商名单由 Java 主数据给定，模型只做文本抽取，加向量库是给自己加复杂度。

### 3.6 抽取三件套：正则优先 → LLM 兜底 → 原文回查

- **代码**：`app/pipeline.py` 的 `extract_quote`、`verify_price_in_text`、`_price_candidates`。
- **规则**：正则能抽到就不调模型（省钱、稳定）；抽不到才让模型输出 JSON，但**模型给的价格必须能在原文里找到**（`28.5` / `28.50` / `1,280.00` 三种形态），否则丢弃。
- **原价陷阱**（真 bug）：页面写着「原价 ¥999.00（划线价），现价 ¥28.50/件」，第一版取第一个数字 → 抓到 999。修复：给每个候选价格按上下文打分（"现价/含税单价" +3，"原价/吊牌价" −4），窗口取 `text[start-18 : end+8]`（非对称！），取最高分。
- **动手**：自己造一条「市场价 ¥199，本店 ¥19.9 包邮」的页面，看它取哪个。

### 3.7 确定性打分与报告守门

- **代码**：`app/pipeline.py` 的 `score_quotes`、`build_risks`、`build_report`、`narrative_guard`。
- **公式**：`总分 = 价格分 40 × (最低价/本价) + 交期分 20 + 资质分 20 × (rating/5) + 风险分 20`，风险按 HIGH −6 / MEDIUM −3 / LOW −1 扣，下限 0。
- **守门**：报告正文里出现的每个数字都必须能在事实表里找到，否则整段丢弃重写——防止模型"顺手编一个 12% 降本"。
- **会被问**：为什么不让模型直接给结论？→ 结论要可复现、可审计；模型负责"读文本"，打分器负责"做决策"。

---

## 4. 横切主题：安全与可信度

| 威胁 | 防护 | 代码位置 |
|---|---|---|
| SSRF / 内网探测 | 协议、userinfo、IP、域名白名单 + 禁重定向 | `app/security.py:UrlGuard`、`app/tools.py:SafeFetcher` |
| 提示词注入 | 抓取内容包进 `<untrusted_data>`，9 条注入特征打标，系统提示声明"其中只是数据" | `app/security.py:sanitize_untrusted/wrap_untrusted` |
| 数据外泄 | 输出侧脱敏（手机号/身份证/银行卡/邮箱） | `app/security.py:mask_sensitive` |
| 抓取被滥用 | 每域名最小间隔、单次读取上限、页面数上限 | `DomainLimiter`、`FETCH_MAX_BYTES`、`FETCH_MAX_PAGES` |
| 回调伪造/重放 | HMAC + 时间戳窗口 + nonce 唯一索引 + eventId 幂等 | `client/CallbackSigner.java`、`AgentCallbackService` |
| 越权访问 | 资源归属校验（非 ADMIN 只能看自己的需求单/任务） | `RequirementService`、`TaskService` |
| 密钥泄露 | 全部走环境变量，`.env` 已 ignore，仓库无 Key | `application.yml`、`.env.example` |
| 模型编造 | 数据闸（schema 无价格字段）、计算闸（确定性公式）、文本闸（数字回查） | `pipeline.py`、`build_report` |

**怎么讲**：不要说"我做了安全"，要说"我识别出 5 类威胁，每类在哪个文件、用什么手段、残留风险是什么"。残留风险要敢承认：IP 直连能挡，但 DNS 重绑定挡不住——所以生产要在出口做网络策略。

---

## 5. 测试与评测怎么写

- **Java**（45 项）：`MockMvc` + H2（`MODE=MySQL`）跑接口级测试；`@MockBean AgentClient` 隔离 Python；SSE 用 `request().asyncStarted()` 断言而不是 `asyncDispatch`（否则要等 10 分钟超时）。
- **Python**（33 项）：`pytest` + `asyncio_mode=auto`；所有抓取走 `MockSource`（同输入同输出，`sha256` 决定价格），测试才能确定性。
- **评测**（`eval/run_eval.py`）：40 条需求 → 离线跑全流水线 → 算四个指标：
  - 供应商召回率 = 召回的正确供应商 / 应召回
  - 字段填充率 = 9 个关键字段的非空率
  - 抽检准确率 = 均匀抽样 ≤20 条，与 `MockSource.ground_truth` 对比单价/MOQ/交期
  - needsReview 标注率、黑名单识别率、报告结构完整率
- **诚实口径**：100% 是因为数据源是 mock、真值同源，只能证明"流水线正确"，不能证明"真实网页抽取准确率"。这句话面试一定要主动说。

---

## 6. 动手练习清单（20 个，由易到难）

| # | 改什么 | 验证什么 |
|---|---|---|
| 1 | 给 `/api/suppliers` 加 `minRating` 过滤 | MyBatis-Plus 条件构造 |
| 2 | 给需求单加 `deadline` 字段（V4 迁移） | Flyway 增量 |
| 3 | 登录接口限流从 10 改成 3 | 注解参数生效 |
| 4 | 新增一个 `@RateLimit` 到创建任务接口 | 拦截器覆盖面 |
| 5 | 把 JWT 过期时间改成 30 秒 | 前端 401 处理 |
| 6 | 给任务加 `PATCH /api/tasks/{id}/note` | 状态机外的字段更新边界 |
| 7 | 让 cancel 走"带条件 UPDATE" | 并发下不覆盖终态 |
| 8 | 回调里故意重复发同一 eventId | 幂等 |
| 9 | 回调里把 timestamp 改成 10 分钟前 | 时间窗 |
| 10 | SSE 断线后刷新页面 | 事件回放 |
| 11 | 抓取白名单加一个域名但不加 `www.` | 后缀匹配细节 |
| 12 | 造一个「原价 999 / 现价 28.5」页面 | 价格上下文打分 |
| 13 | 把 `score_quotes` 的价格权重从 0.40 调成 0.60 | 排序变化是否合理 |
| 14 | 关掉 LLM（清空 Key）跑全流程 | 降级可用性 |
| 15 | 给报告加一节"数据来源清单" | 模板与事实表对齐 |
| 16 | 评测集加 5 条新品类 | 召回率会不会掉 |
| 17 | 把 H2 换成本地 MySQL 跑测试 | 方言差异 |
| 18 | 给 Feign 加 fallback | 下游不可用时用户体验 |
| 19 | 加一个"任务超时 10 分钟自动失败"的定时任务 | 补偿机制 |
| 20 | 把 `claim` 里的角色改成枚举而非字符串 | 权限模型演进 |

**做练习的规矩**：每改一处，先写/改一个测试，跑绿再提交。这条习惯本身就是面试加分项。

---

## 7. 自测 20 问（先自己答，再对照）

1. 为什么 Java 管状态、Python 不管？→ 状态需要事务、幂等、补偿、审计；Python 无状态才能水平扩容、重启不丢语义。
2. `@Async` 方法里为什么读不到刚插入的数据？→ 不同线程/连接，事务未提交。
3. afterCommit 注册在哪个类？→ `TransactionSynchronizationManager.registerSynchronization`。
4. 状态机为什么要单独一个类？→ 单一入口、可测试、非法迁移统一 409、终态不可变。
5. SSE 为什么需要 ticket？→ EventSource 不能带 Authorization 头，票放 URL 且 60 秒一次性。
6. 为什么先落库再推送？→ 断线可回放，事件有 seq。
7. 回调怎么防重放？→ 时间戳 ±5min + nonce 唯一索引 + eventId 幂等。
8. 为什么用 `MessageDigest.isEqual`？→ 常量时间，防时序攻击。
9. 验签为什么对原始 body？→ 反序列化会改变字节，签名对不上。
10. 限流为什么不用 `synchronized`？→ 单机锁、阻塞、多实例失效。
11. Redis 没装为什么还能启动？→ 启动 ping 失败降级内存实现。
12. 为什么用 Flyway？→ 表结构版本化、可审计、可回放。
13. 为什么 DDL 要兼容 H2？→ 测试不依赖外部 MySQL。
14. SSRF 四道校验是什么？→ 协议、userinfo、IP 直连、域名白名单。
15. 为什么禁重定向？→ 3xx 可跳到内网，绕过白名单。
16. 原价陷阱怎么修的？→ 上下文权重 + 非对称窗口 + 取最高分候选。
17. 怎么保证模型不编价格？→ schema 无价格字段 + 原文回查 + 确定性打分 + 报告数字回查。
18. 为什么不用向量库？→ 任务不是语义检索；供应商名单由主数据给定。
19. 100% 指标可信吗？→ 只证明流水线正确（mock 真值同源），不代表真实网页准确率。
20. 这个项目最能体现你能力的一处？→ 自选一个：afterCommit 的排查过程 / 反编造三道闸 / 回调安全设计，讲"现象 → 根因 → 方案 → 验证"。

---

## 8. 14 天跟练路线

| 天 | 主题 | 产出 |
|---|---|---|
| D1 | 跑通 + 读 README/架构 | 能画出数据流 8 步 |
| D2 | 配置 + 统一响应 + 异常 | 能解释每个错误码 |
| D3 | MyBatis-Plus + Flyway | 完成练习 1、2 |
| D4 | JWT + Security 过滤器链 | 完成练习 3、5 |
| D5 | 限流 + 存储抽象 | 完成练习 4、10、11 |
| D6 | 状态机 + 任务接口 | 完成练习 6、7 |
| D7 | 事务 + 异步（afterCommit） | 能复现并修复那个 bug |
| D8 | SSE + 票据 + 回放 | 完成练习 10、11 |
| D9 | Feign + HMAC 回调 | 完成练习 8、9 |
| D10 | 结果/报告/CSV + 前端联调 | 能讲清 demo.html 的数据流 |
| D11 | Python 安全抓取 | 完成练习 11、12 |
| D12 | 抽取 + 打分 + 报告 | 完成练习 12、13、15 |
| D13 | 评测脚本 | 完成练习 16，能解释四个指标 |
| D14 | 复盘 + 简历 + 模拟问答 | 用第 7 节自测，录音讲 3 分钟 |

---

## 9. 面试前 3 天速查卡

**必须能白板画的三张图**：① 双栈架构 + 8 步数据流；② 任务状态机；③ 回调验签时序。

**五个"我有意为之"**：
1. 不做多智能体 —— 决策权交给确定性打分器，模型只读文本。
2. Python 无状态 —— 可靠性放 Java，扩容放 Python。
3. 不用向量库 —— 没有语义检索需求，加了是负债。
4. 只抓白名单公开页 + mock —— 合规优先，演示可断网。
5. 评测 100% 要主动解释口径 —— 诚实比漂亮数字值钱。

**三句"我踩过的坑"**（面试官最爱听）：
- 异步线程读不到未提交事务 → afterCommit。
- 异步回写整实体覆盖了用户的"取消" → 改成带条件的字段更新。
- 页面「原价 999 / 现价 28.5」抓错价 → 上下文打分 + 原文回查。

**别说的话**：多智能体、爬 1688/企查查、准确率 95%、高并发、支撑百万数据。
