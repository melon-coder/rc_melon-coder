# notify-center

企业内部通知投递中心：接收业务系统提交的外部 HTTP 通知请求，并**尽可能可靠地投递**到目标地址。

业务系统不关心外部 API 的返回值，只要求「通知请求能被稳定、可靠地送达」。本服务在不可靠的 HTTP 之上，用「先持久化 + 异步投递 + 重试」造出一层可靠投递。

---

## 1. 需求理解

### 1.1 问题本质

多个业务系统在关键事件发生时（广告引流注册成功、订阅付款成功、商品购买等）需要调用外部供应商的 HTTP(s) API 通知对方。不同供应商的请求地址、Header、Body 各不相同。业务系统本身**不关心返回值**，只要求**不丢、最终送达**。

这本质上是一个 **Outbox + 可靠投递** 问题：核心矛盾是 HTTP 天生不可靠（超时、5xx、网络抖动、对端宕机、响应丢失），而我们要承诺可靠送达。

### 1.2 关键点

| 关键点 | 说明 |
|---|---|
| **至少一次（at-least-once）** | 目标「尽可能可靠送达」+ 业务不关心返回值，只有 at-least-once 同时满足「不丢 + 最终送达」。代价是**可能重复**，重复由下游幂等消化 |
| **先持久化、后应答** | 收到请求先写库，写成功才返回成功；只要落库成功，进程崩溃也不丢 |
| **失败重试** | 指数退避 + 抖动，直到成功或进入死信，绝不静默丢弃 |
| **幂等** | 业务重复提交同一通知（幂等键）不重复投递 |
| **不同供应商不同请求** | URL / Header / Body 由业务提交时提供，按 `supplier` 维度做隔离保护 |
| **不保证 exactly-once** | 跨 HTTP 无法做到恰好一次；2xx 响应在网络丢失时会导致重发，这是 at-least-once 的固有代价 |

### 1.3 系统边界（解决什么 / 不解决什么）

**解决：**
- 接收通知请求，先落库、再异步投递（不丢）
- 失败重试（指数退避 + 抖动）
- 幂等去重
- 对端保护：超时、并发上限、熔断
- 可观测：状态查询、死信告警、手动重投

**明确不解决（及理由）：**

| 不解决 | 理由 |
|---|---|
| 不保证「恰好一次」 | 分布式 + HTTP 下无法实现，需对端配合；「不关心返回值」的前提就是允许重复 |
| 不解析返回值的业务语义 | 2xx 即「已送达」，非 2xx/网络错误即「未送达需重试」；下游业务是否真处理成功是下游职责 |
| 不做内容编排/业务转换 | 业务提供最终 header/body，本服务只透传；业务逻辑不进本服务 |
| 不保证跨通知全局顺序 | 只保证单条 at-least-once；强顺序牺牲吞吐与重试灵活性 |
| 不保证「绝不重复」 | at-least-once 固有代价 |

> 边界内主动防的安全点：允许业务传任意 URL 存在 **SSRF** 风险，通过 `notify.allowed-hosts` 白名单（默认空 = 允许全部 http/https）限制。

---

## 2. 整体架构与核心设计

### 2.1 技术栈

- Java 8 + Spring Boot 2.7 + Maven
- H2 内嵌库（outbox 持久化，生产可换 MySQL/PostgreSQL，仅改配置）
- Spring Data JPA、Spring `@Scheduled`（DB 轮询调度器）
- 自研 `CircuitBreaker` / `ConcurrencyLimiter` / `BackoffPolicy`（零额外中间件依赖）

### 2.2 模块结构

```
com.example.notify
├─ api/          # Controller（提交/查询/手动重投）+ DTO + 全局异常处理
├─ model/        # Notification 实体 + NotificationStatus 状态机枚举
├─ repository/   # findDue（捞取到期任务）/ claim（原子认领）/ reclaimStaleDelivering（回收）
├─ service/      # NotificationService（幂等提交/查询/重投）、ClaimService、DeliveryService
├─ dispatcher/   # Dispatcher（轮询 + 回收）、HttpSender（HTTP 发送）
├─ retry/        # BackoffPolicy（指数退避 + 抖动）
├─ circuit/      # CircuitBreaker（熔断）、ConcurrencyLimiter（每供应商并发限流）
└─ config/       # Clock / 投递线程池 / RestTemplate / 调度开关
```

### 2.3 状态机

```
                 submit             claim               2xx
        ┌───── PENDING ──┐──► DELIVERING ──────────────────► SUCCESS
        │                 │       │
        │                 │       │ 失败(未达上限/未超时)
        │   retry         │       └──► RETRY ──► (再次被捞取 claim)
        │   (手动重投)     │               │
        └── DEAD ◄────────┘               │ 达 maxAttempts 或超 deadlineAt
                                          └──► DEAD
```

- `PENDING` / `RETRY` / `SUCCESS` / `DEAD` 是持久化的稳定状态
- `DELIVERING` 是**在途状态**：`claim` 原子地把 `PENDING/RETRY` 置为 `DELIVERING`（带 `deliver_started_at` 租约），防止同一任务被并发重复投递
- 崩溃残留的 `DELIVERING`（租约超时）由 `reaper` 定时回收为 `RETRY`

### 2.4 核心流程

```
1. 提交（submit）
   业务 POST /api/notifications
   → 校验（URL scheme、method、SSRF 白名单）
   → 幂等判断（idempotencyKey 已存在则直接返回原 notifyId）
   → 落库 outbox（PENDING，next_retry_at=now，deadline_at=now+7d）
   → 202 Accepted + notifyId

2. 调度（Dispatcher.poll，默认 1s 一轮）
   → findDue 捞出 PENDING/RETRY 且 next_retry_at<=now 的批次
   → 逐条 claim（原子 UPDATE ... WHERE status IN ('PENDING','RETRY')）
   → 认领成功则投递给有界线程池

3. 投递（DeliveryService.deliver）
   → 熔断打开 or 并发超限 → 重新排期（不消耗尝试次数）
   → HttpSender 发送（连接/读超时，非 2xx 视为失败）
   → 2xx → SUCCESS；失败 → attempt+1，退避重试或进 DEAD

4. 回收（Dispatcher.reap，默认 60s 一轮）
   → 把 deliver_started_at 早于 lease（默认 10min）的 DELIVERING 回收为 RETRY

5. 死信兜底
   → DEAD 状态可查询、可 POST /{notifyId}/retry 手动重投（重置次数与截止时间）
```

### 2.5 数据模型（outbox 表）

| 字段 | 说明 |
|---|---|
| `notify_id` | 唯一，返回给调用方 |
| `idempotency_key` | 唯一，幂等去重 |
| `supplier` | 供应商标识（默认取 URL host），熔断/限流/隔离维度 |
| `url` / `method` / `headers` / `body` | 透传的请求信息 |
| `status` | PENDING / DELIVERING / RETRY / SUCCESS / DEAD |
| `attempt_count` / `max_attempts` | 已尝试次数 / 上限 |
| `next_retry_at` / `deadline_at` | 下次重试时间 / 最终截止时间 |
| `deliver_started_at` | 在途租约（认领时间戳） |
| `last_error` | 最近一次失败原因（可观测） |

---

## 3. 关键工程决策与取舍

### 3.1 调度方案：DB 轮询 vs MQ（选 DB 轮询）

- **选择**：DB 轮询调度器（`@Scheduled` + `findDue`）
- **为什么**：与「先落库后应答」天然一致；outbox 可查、可对账、可手动重投；重试时间精确可控；**零额外中间件**，运维成本最低
- **取舍/代价**：轮询有延迟、DB 压力随任务量线性增长，吞吐上限中等（适合中小流量）
- **演进**：吞吐上来后把「轮询捞取」换成 RocketMQ 延迟消息即可，落库/投递/重试逻辑不变

### 3.2 投递语义：at-least-once（而非 exactly-once）

- **为什么**：目标就是「尽可能可靠送达」；exactly-once 跨 HTTP 在分布式下不可实现（需对端配合）；at-most-once 会丢，违背需求
- **取舍/代价**：可能重复，设计说明必须声明「下游需幂等」，并与供应商约定幂等键透传（把「至少一次 + 幂等」包装成「有效一次」）

### 3.3 幂等提交的实现：TransactionTemplate + 唯一约束

- **为什么**：单靠「先查再插」在并发下有竞态；单靠捕获唯一约束异常在 JPA 中会陷入 rollback-only 事务，无法在同一事务内再查询
- **做法**：插入放在独立事务中（`TransactionTemplate`），约束冲突只回滚该事务，随后在**新事务**里查回已存在记录返回
- **取舍/代价**：多一次事务边界，但换来并发重复提交下的正确性

### 3.4 非 2xx 的处理：RestTemplate no-op 错误处理器

- **为什么**：投递服务把「供应商返回 500」当作**正常可重试的失败**，而非异常；需要干净地记录状态码与响应体，用于可观测与排查
- **做法**：自定义 `ResponseErrorHandler` 让 `hasError` 恒为 false，由 `HttpSender` 自己判断 2xx/非 2xx
- **取舍/代价**：放弃 RestTemplate 默认的 4xx/5xx 异常语义，逻辑更清晰

### 3.5 时间类型：LocalDateTime（而非 Instant/UTC）

- **为什么**：H2 + Hibernate 5.6 下 `LocalDateTime` 最无歧义、演示零风险
- **取舍/代价**：无时区信息，跨节点/跨时区会出问题；**生产多节点应换 `Instant`/UTC**

### 3.6 在途防重：DELIVERING 租约 + reaper

- **为什么**：异步投递（有界线程池）下，若不标记在途状态，同一任务会被下一轮 poll 重复捞取；崩溃时在途任务会永久卡死
- **做法**：`claim` 原子置为 `DELIVERING` 并记录 `deliver_started_at`；`reaper` 回收超租约的残留
- **取舍/代价**：多一个中间状态与一个回收任务；换来「不重复投递 + 崩溃自愈」

### 3.7 对端保护：熔断 + 并发限流（按 supplier 维度）

- **为什么**：某供应商宕机时，若不熔断，持续的重试会打满自己的线程池与网络，拖垮整个服务
- **做法**：每 supplier 独立 `CircuitBreaker`（连续失败达阈值开闸、冷却后半开试探）与 `ConcurrencyLimiter`（信号量限制在途请求数）
- **取舍/代价**：熔断打开期间该供应商的通知会被「挂起 + 可见」（重排期而非丢弃），符合「永不静默丢弃」原则

### 3.8 可测试性：注入 Clock

- **为什么**：退避、截止时间（deadline）、熔断冷却都是时间依赖逻辑，直接调 `LocalDateTime.now()` 不可测
- **做法**：注入 `java.time.Clock` bean，测试用固定/可变 Clock 精确验证时间边界
- **取舍/代价**：多一个 bean，但让 deadline 过期、熔断冷却等逻辑可被确定性测试覆盖

### 3.9 存储：H2（演示）→ MySQL（生产）

- **为什么**：H2 内嵌、克隆即跑，零安装即可验证；生产换 MySQL 只改 `spring.datasource.url` 与驱动
- **取舍/代价**：H2 不适用于生产；但代码不绑定任何 H2 特有语法（原生 SQL 均为标准 SQL），切换成本极低

---

## 运行方式

```bash
cd notify-center
mvn spring-boot:run          # 启动，H2 落盘到 ./data，端口 8080
```

```bash
# 提交通知
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"url":"http://supplier.example/callback","method":"POST",
       "headers":{"Authorization":"Bearer x"},
       "body":"{\"event\":\"user.registered\"}",
       "idempotencyKey":"ord-1001"}'
# → 202 {"notifyId":"...","status":"PENDING"}
# 或者执行：
curl -X POST http://localhost:8080/api/notifications -H "Content-Type: application/json" -d @request-sample.json

curl http://localhost:8080/api/notifications/{notifyId}               # 查询状态
curl -X POST http://localhost:8080/api/notifications/{notifyId}/retry  # 死信手动重投
```

测试：`mvn test`（26 个用例，覆盖退避/熔断/HTTP 发送/投递状态机/幂等提交/全链路集成）。
