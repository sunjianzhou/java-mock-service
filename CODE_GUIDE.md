# 代码功能说明

本文档逐文件说明项目中每个源文件、配置文件和非代码文件的职责，帮助你快速对照代码理解整体设计。

---

## 目录

- [一、项目结构速览](#一项目结构速览)
- [二、mock-core 核心框架](#二mock-core-核心框架)
  - [config 配置层](#1-config--配置层)
  - [handler 请求处理层](#2-handler--请求处理层)
  - [protocol 协议适配层](#3-protocol--协议适配层)
  - [record 录制回放层](#4-record--录制回放层)
  - [route 路由层](#5-route--路由层)
  - [script 脚本引擎层](#6-script--脚本引擎层)
  - [security 安全层](#7-security--安全层)
  - [audit 审计层](#8-audit--审计层)
  - [metrics 指标层](#9-metrics--指标层)
  - [postman 导出层](#10-postman--导出层)
  - [ws WebSocket 层](#11-ws--websocket-层)
  - [util 工具层](#12-util--工具层)
- [三、mock-business 业务配置](#三mock-business-业务配置)
- [四、mock-boot 启动模块](#四mock-boot-启动模块)
- [五、测试文件](#五测试文件)
- [六、非代码文件（配置/构建/部署）](#六非代码文件配置构建部署)
- [七、一次请求的完整代码路径](#七一次请求的完整代码路径)
- [八、关键接口与扩展点一览](#八关键接口与扩展点一览)

---

## 一、项目结构速览

```
mock-service/
│
├── mock-core/          ← 核心框架，所有功能逻辑，可作为独立 JAR 复用
│   └── src/main/java/com/mock/core/
│       ├── config/     ← 配置模型 & 热加载
│       ├── handler/    ← 请求处理 & 响应体责任链
│       ├── protocol/   ← 协议适配（form / json / xml）
│       ├── record/     ← 录制存储 & 回放
│       ├── route/      ← 动态路由注册
│       ├── script/     ← 脚本引擎
│       ├── security/   ← 鉴权过滤器
│       ├── audit/      ← 审计日志
│       ├── metrics/    ← Prometheus 指标
│       ├── postman/    ← Postman 导出
│       ├── ws/         ← WebSocket Mock
│       └── util/       ← 工具类
│
├── mock-business/      ← 业务配置，纯 YAML，零 Java 代码
│   └── src/main/resources/
│       ├── mock-endpoints.yml   ← 生产端点配置
│       └── demo.yml             ← 完整示例与注释说明
│
├── mock-boot/          ← Spring Boot 启动壳
│   └── src/main/
│       ├── java/.../
│       │   ├── MockApplication.java        ← 入口
│       │   └── MockOpenApiConfiguration.java ← Swagger
│       └── resources/
│           ├── application.yml       ← 主配置
│           ├── logback-spring.xml    ← 日志配置
│           ├── responses/            ← 示例外部响应体文件
│           └── static/mock-admin.html ← 管理控制台 UI
│
├── pom.xml             ← 多模块 Maven 父配置
├── Dockerfile          ← 镜像构建
├── docker-compose.yml  ← 容器编排
├── start.sh / start.bat ← 快速启动脚本
└── README.md           ← 使用文档
```

---

## 二、mock-core 核心框架

### 1. config — 配置层

> 负责：YAML 配置模型定义、启动校验、运行时热加载。

---

#### `EndpointConfig.java`

**职责**：单个 HTTP Mock 端点的全部配置字段（数据模型，无逻辑）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 端点唯一标识 |
| `method` | String | HTTP 方法（GET/POST） |
| `path` | String | 精确路由路径 |
| `pathPattern` | String | 动态路由（含 `{variable}`） |
| `contentType` | String | 请求 Content-Type，决定用哪个协议适配器 |
| `responseContentType` | String | 响应 Content-Type |
| `responseStatus` | int | HTTP 状态码，默认 200 |
| `responseBody` | String | 静态响应体文本 |
| `responseBodyFile` | String | 外部文件路径（`classpath:` 或 `file:` 前缀） |
| `responseScript` | String | Nashorn JS 脚本（优先级最高） |
| `responseDelay` | long | 最小响应延迟（毫秒） |
| `responseDelayMax` | long | 最大响应延迟，>`responseDelay` 时随机取值 |
| `validation` | ValidationConfig | 参数校验（见内部类） |
| `conditionalResponse` | ConditionalResponse | 条件响应（见内部类） |

**内部类**：
- `ValidationConfig`：必填参数列表（`requiredParams`）+ 格式规则（`paramRules`）+ 错误响应（`errorStatus` / `errorBody` / `formatErrorBody`）
- `ParamRule`：单条格式规则，含名称、正则、错误信息和预编译的 `Pattern`
- `ConditionalResponse`：参数名（`param`）+ 值→响应体映射（`cases`）+ 兜底（`defaultResponse`）

**关键方法**：
```java
getEffectivePath()         // pathPattern 非空则返回 pathPattern，否则返回 path
validation.resolveFormatErrorBody()  // 返回 formatErrorBody（若有），否则回退 errorBody
```

---

#### `WebSocketEndpointConfig.java`

**职责**：单个 WebSocket Mock 端点的配置（数据模型）。

| 字段 | 说明 |
|------|------|
| `id` / `description` / `path` | 标识与路由 |
| `onConnect` / `onConnectFile` | 连接成功后发送的欢迎消息（inline 文本或外部文件） |
| `onDisconnect` / `onDisconnectFile` | 断开时发送的消息 |
| `messageHandlers` | 消息处理规则列表（正则 `pattern` + 回复 `response`） |
| `heartbeat` | 心跳配置（`interval` 毫秒 + `message` 文本） |
| `delay` | 消息回显延迟（毫秒） |

---

#### `MockConfigProperties.java`

**职责**：顶层配置绑定（`@ConfigurationProperties(prefix = "mock")`）+ **启动 fail-fast 校验**。

绑定字段：
- `endpoints`：HTTP 端点列表
- `websockets`：WebSocket 端点列表

`validate()` 方法（`@PostConstruct` 自动调用）做以下检查，任何一条失败则**拒绝启动**：
1. `endpoints` 不为空
2. 每个端点的 `id` / `method` / `path或pathPattern` / `responseContentType` 不为空
3. `responseContentType` 是合法的 `MediaType` 格式
4. `responseBody` / `responseBodyFile` / `responseScript` 至少有一个
5. `(method, effectivePath)` 组合无重复

---

#### `ReloadableConfigHolder.java`

**职责**：热加载配置的**持有者**，用 `AtomicReference` 保证线程安全的无锁读。

```java
AtomicReference<MockConfigProperties> configRef  // 当前生效的配置
volatile Runnable onReload                        // 配置更新后的回调（用于刷新 WS 路由）

set(newConfig)   // 替换配置并触发 onReload 回调
get()            // 读取当前配置（无锁，路由匹配时高频调用）
```

路由匹配 Lambda 每次请求都调用 `holder.get()`，而 `AtomicReference` 的读是无锁的——这是热加载对请求处理零影响的关键。

---

#### `ConfigFileWatcher.java`

**职责**：监听外部文件系统上的 `mock-endpoints.yml` 变化，触发**自动热加载**。

只有配置了 `mock.watch-path` 且值以 `file:` 开头时才启动，否则跳过。

工作流程：
1. 用 Java NIO `WatchService` 注册目标文件所在**目录**的 `ENTRY_MODIFY` 事件
2. 轮询到事件后，过滤 `OVERFLOW` 事件（防止崩溃），确认是目标文件变化
3. 500ms 去抖（debounce）防止编辑器多次写入触发重复加载
4. 调用 `reloadFromFile()` → `YamlConfigParser.parse()` → `holder.set(newConfig)`
5. 异常时保持旧配置，日志打印错误原因
6. 网络文件系统或系统负载高时 WatchService 可能断开，内置指数退避重试（最多 10 次，上限 60 秒）

---

#### `YamlConfigParser.java`

**职责**：将 SnakeYAML 解析出的原始 `Map<String, Object>` **手动映射**为 `MockConfigProperties`。

不用 Spring 的自动绑定，因为热加载需要**在 Spring 生命周期外独立解析**。

额外功能：
- 检测并 WARN 拼写错误的配置 key（对比 `KNOWN_ENDPOINT_KEYS` 等白名单集合）
- 编译 `paramRules[].pattern` 为 `Pattern` 对象（正则预编译，避免每次请求编译）

---

### 2. handler — 请求处理层

> 负责：接收路由分发来的请求，执行完整的校验→解析→模板→构建响应流程。

---

#### `MockRequestHandler.java`

**职责**：整个 Mock 服务的**核心处理器**，协调所有子系统完成一次 Mock 请求。

主方法 `handle(ServerRequest, EndpointConfig)` 执行流程：

```
1. 检查回放模式 → 有匹配录制则直接 buildReplayResponse() 返回
2. 找到支持当前 contentType 的 ProtocolAdapter → 异步提取请求参数
3. 合并路径变量（body 参数优先覆盖同名路径变量）
4. 必填校验（hasMissingRequired）→ 失败返回 errorBody
5. 格式校验（hasInvalidFormat）→ 失败返回 formatErrorBody
6. resolveResponseBody() 走责任链解析响应体
7. applyTemplateWithType() 替换 {{param.xxx}} + {{now}}/{{uuid}}/{{seq}}
8. buildSuccessResponse() 加延迟、设响应头、返回
9. doRecord() 如果处于录制模式，记录本次交换
10. metrics.recordRequest() 统计指标
```

模板方法体系：
```java
applyTemplate(template, params)              // 测试兼容入口（保留2参数签名）
applyTemplateWithType(template, params, ct)  // 内部调用，按 contentType 判断是否 JSON 转义
applyTemplateInternal(template, params, esc) // 实际替换，含 BuiltinTemplateVars.apply()
isJsonTemplate(template)                     // 内容启发式判断（仅无 contentType 时使用）
```

---

#### `AdminEndpointHandler.java`

**职责**：所有 `/mock/_admin/**` 管理端点的**处理逻辑**，从 `MockRequestHandler` 拆分出来，遵循单一职责。

| 方法 | 对应端点 | 说明 |
|------|----------|------|
| `startRecording()` | POST /record/start | 开始录制，同步将 `metrics.replayActive` 置 false |
| `stopRecording()` | POST /record/stop | 停止录制 |
| `startReplay()` | POST /replay/start | 开启回放，同步将 `metrics.recordingActive` 置 false |
| `stopReplay()` | POST /replay/stop | 关闭回放 |
| `listRecordings()` | GET /recordings | Jackson 序列化录制列表为 JSON |
| `clearRecordings()` | DELETE /recordings | 清空 |
| `saveRecordings()` | POST /recordings/save | 异步写文件（boundedElastic） |
| `loadRecordings()` | POST /recordings/load | 异步读文件 |
| `reload()` | POST /reload | 重新加载 YAML，synchronized 防止并发 reload 交错 |
| `listRoutes()` | GET /routes | 遍历 endpoints + websockets 生成路由清单 |
| `exportPostman()` | GET /postman | 委托 PostmanCollectionBuilder |

辅助方法：
- `obj()` — 创建空 `ObjectNode`，替代字符串拼接构建 JSON
- `jsonOk(ObjectNode)` — 用 Jackson 序列化节点，返回 200 响应
- `jsonError(String)` — 返回标准 500 错误结构

---

#### `ResponseBodyResolver.java`

**职责**：响应体解析**责任链的入口**，按 `order()` 升序遍历所有 `BodyResolverStrategy`，使用第一个 `supports()` 返回 true 的策略。

```java
// Spring 注入所有 @Component 策略，启动时按 order 排好序
@Autowired
ResponseBodyResolver(List<BodyResolverStrategy> strategies)

// 非 Spring 场景（测试）的便捷构造
ResponseBodyResolver(ScriptEngineExecutor scriptExecutor)

// 入口
Mono<String> resolve(EndpointConfig config, Map<String, String> params)
```

---

#### `BodyResolverStrategy.java`（接口）

**职责**：响应体解析策略**扩展点**。实现此接口并标注 `@Component` 即可注入责任链，无需改动任何现有代码。

```java
boolean supports(EndpointConfig config)                            // 是否处理此端点
Mono<String> resolve(EndpointConfig config, Map<String,String> p)  // 解析响应体
int order()                                                        // 优先级（越小越优先）
```

---

#### `ScriptBodyResolverStrategy.java`

**职责**：优先级 **1**，处理 `responseScript` 字段（JS 脚本）。

- 在 `Schedulers.boundedElastic()` 执行，避免阻塞 Netty 事件循环
- 委托 `ScriptEngineExecutor.execute(script, params)`

---

#### `FileBodyResolverStrategy.java`

**职责**：优先级 **2**，处理 `responseBodyFile` 字段（外部文件）。

- 在 `Schedulers.boundedElastic()` 执行（阻塞 I/O）
- 委托 `ResourceReader.readFile(path)` 读取，文件不存在时返回错误 JSON

---

#### `ConditionalBodyResolverStrategy.java`

**职责**：优先级 **3**，处理 `conditionalResponse` 配置（参数值 → 响应体映射）。

匹配逻辑（纯内存，同步）：
1. 取 `conditionalResponse.param` 指定的参数值
2. 在 `cases` Map 中查找 → 命中则返回对应体
3. 未命中 → 用 `defaultResponse`
4. 无 defaultResponse → 回退到 `responseBody`

---

#### `StaticBodyResolverStrategy.java`

**职责**：优先级 **4**（兜底），永远 `supports()` 返回 true，直接返回 `responseBody`。

---

### 3. protocol — 协议适配层

> 负责：从不同格式的请求体中提取参数为统一的 `Map<String, String>`。

---

#### `ProtocolAdapter.java`（接口）

**职责**：协议适配扩展点，按 `EndpointConfig.contentType` 决定由哪个适配器处理。

```java
boolean supports(EndpointConfig config)                     // 按 contentType 判断
Mono<Map<String, String>> extractParams(ServerWebExchange)  // 异步提取参数
```

注意：匹配判断基于**配置中声明的** `contentType`，而非请求实际 Header，这样可以精确控制每个端点用哪种解析方式。

---

#### `FormUrlEncodedAdapter.java`

**职责**：处理 `application/x-www-form-urlencoded` 请求（证通系列）。

- 调用 Spring WebFlux 的 `exchange.getFormData()`（内置缓冲解析）
- 多值参数用逗号合并为单字符串：`a=1&a=2` → `{"a": "1,2"}`

---

#### `JsonAdapter.java`

**职责**：处理 `application/json` 请求（金联汇通、北京数字认证等）。

- 用 Jackson `readTree()` 解析请求体
- `flattenJson()` 递归展平，嵌套字段用 dot-notation：`{"user":{"name":"A"}}` → `{"user.name":"A"}`
- 数组展平为 `field[0]`, `field[1]` 格式

---

#### `XmlAdapter.java`

**职责**：处理 `text/xml` / `application/xml` 请求（组代中心 SOAP 系列）。

- DOM 解析（namespace-unaware，忽略命名空间前缀）
- `collectLeafElements()` 递归收集**叶子元素**（无子元素的标签）的文本内容
- SOAP Envelope/Body 等容器元素被跳过，只提取业务字段
- 启用 XXE 防护（禁止 DOCTYPE / 外部实体加载）

---

### 4. record — 录制回放层

> 负责：录制请求/响应交换记录，支持回放和文件持久化。

---

#### `RecordingStore.java`（接口）

**职责**：录制存储的**抽象接口**，默认实现为 `InMemoryRecordingStore`。

定义了全部状态管理和数据操作方法，通过替换实现类可以无缝切换存储后端（Redis、数据库等）。

---

#### `InMemoryRecordingStore.java`

**职责**：基于内存（`LinkedList`）的录制存储，带**互斥状态机**和**容量上限**。

**状态机**（`AtomicReference<Mode>`）：
```
IDLE ←→ RECORDING    （startRecording 时若正在回放，自动停止回放并 WARN）
IDLE ←→ REPLAYING    （startReplay 时若正在录制，自动停止录制并 WARN）
RECORDING 和 REPLAYING 不可同时激活
```

**并发控制**：
- `recordings` 列表所有读写操作 `synchronized(recordings)`
- `mode` 状态用 `AtomicReference`（无锁 CAS）

**容量管理**：上限 10,000 条，超出后淘汰最早的记录（`LinkedList.remove(0)` O(1)）

**文件持久化**：
- `saveToFile()` → `recordings/recordings.json`（先快照再写盘，释放锁后再 I/O）
- `loadFromFile()` → Jackson 反序列化

---

#### `RecordedExchange.java`

**职责**：一条录制记录的数据模型（POJO）。

| 字段 | 说明 |
|------|------|
| `id` | UUID，创建时自动生成 |
| `timestamp` | ISO-8601 时间戳 |
| `method` | HTTP 方法 |
| `path` | 请求路径 |
| `endpointId` | 命中的端点 id（用于回放匹配，比精确路径更可靠） |
| `requestParams` | 提取到的请求参数 Map |
| `responseStatus` | 响应状态码 |
| `responseContentType` | 响应 Content-Type |
| `responseBody` | 响应体文本 |
| `responseDelay` | 响应延迟毫秒数（回放时复现） |

---

### 5. route — 路由层

---

#### `MockRouterConfiguration.java`

**职责**：Spring 配置类，注册整个服务的路由——将 HTTP 请求分派给正确的处理器。

**核心 Bean**：`RouterFunction<ServerResponse> mockRoutes(...)`

路由匹配逻辑（每次请求执行）：
```
1. 计算 routeKey = "METHOD /path"
2. 在 adminRoutes Map 中 O(1) 查找 → 命中则交给管理处理器
3. 遍历 holder.get().getEndpoints() 做 PathPattern 匹配
4. 找到匹配的端点 → MockRequestHandler.handle()
5. 无匹配 → 404
```

`buildAdminRoutes()` 方法：将 11 个管理端点定义为 `Map<String, HandlerFunction>` — 新增管理端点只需在此 Map 加一行。

`matches()` 方法：用 `PathPatternParser` + `patternCache`（ConcurrentHashMap）做路径匹配，同时提取路径变量并存入 `request.attributes`。

`protocolAdapters()` Bean：注册三个协议适配器（Form / JSON / XML），Spring 以 `List<ProtocolAdapter>` 注入 `MockRequestHandler`。

---

### 6. script — 脚本引擎层

---

#### `ScriptEngineExecutor.java`（接口）

**职责**：脚本执行引擎**抽象**，隔离具体 JS 引擎实现，便于 JDK 升级时替换。

```java
String execute(String script, Map<String, String> params)
```

---

#### `ScriptExecutor.java`

**职责**：`ScriptEngineExecutor` 的 Nashorn 实现（Java 8 内置，JDK 15 移除）。

- `ThreadLocal<ScriptEngine>`：每线程独立引擎实例，避免并发 `put()/eval()` 互相干扰
- 注意：脚本中定义的全局 JS 变量在同一线程的多次调用间会累积，脚本应保持无状态
- 脚本通过 `params` 变量访问请求参数（`params.name`、`params.idNumber` 等）
- 执行异常时返回 `{"error":"Script error: ..."}` 格式的 JSON 字符串

---

### 7. security — 安全层

---

#### `AdminAuthWebFilter.java`

**职责**：保护 `/mock/_admin/**` 路径，要求请求头携带 `X-API-Key`。

- 通过 `@Value("${mock.admin.api-key:}")` 注入，**未配置时跳过鉴权**（向后兼容）
- `@Order(-100)` 在所有过滤器中优先级较高，确保在路由匹配之前执行
- 无效或缺少 Key → 直接返回 401，不继续 filter chain

---

### 8. audit — 审计层

---

#### `AuditWebFilter.java`

**职责**：记录每个请求的方法、路径（query 参数**名保留值脱敏**）、响应状态码、耗时。

- `@Order(Ordered.HIGHEST_PRECEDENCE)` 最高优先级，覆盖包括 404 在内的所有请求
- 日志输出到独立的 `AUDIT` logger，通过 `logback-spring.xml` 写入 `logs/audit.log`
- `maskQueryValues()` 将 `?name=张三&id=123` 脱敏为 `?name=***&id=***`，防止 PII 写入日志

---

### 9. metrics — 指标层

---

#### `MockMetrics.java`

**职责**：向 Prometheus 暴露 Mock 服务的自定义指标。

指标说明：
| 指标名 | 类型 | 维度 | 说明 |
|--------|------|------|------|
| `mock_requests_total` | Counter | method / endpoint / status | 请求计数 |
| `mock_request_duration_seconds` | Histogram | method / endpoint | 请求延迟分布 |
| `mock_websocket_sessions` | Gauge | — | 当前 WS 连接数 |
| `mock_recording_active` | Gauge | — | 录制状态（0/1） |
| `mock_replay_active` | Gauge | — | 回放状态（0/1） |

实现细节：
- `@Autowired(required = false)` 注入 `MeterRegistry`，测试环境回退到 `Metrics.globalRegistry`
- Counter / Timer 按 `method|endpointId` / `method|endpointId|status` 缓存，避免每次请求重复 `register`
- `ensureGauges()` 用双重检查锁（DCL）确保 Gauge 只注册一次

---

### 10. postman — 导出层

---

#### `PostmanCollectionBuilder.java`

**职责**：将 `MockConfigProperties` 中的端点配置导出为 **Postman Collection v2.1** 格式的 JSON。

`build()` 生成结构：
```json
{
  "info": { "name": "Mock Service API", "schema": "..." },
  "item": [ /* 每个端点一个 item */ ],
  "variable": [{ "key": "baseUrl", "value": "http://localhost:8080" }]
}
```

`buildItem()` 按 contentType 生成请求体：
- `form-urlencoded` → `urlencoded` 格式，`requiredParams` 各字段作为 key
- `json` → `raw` 格式，`buildJsonRequestSkeleton()` 按 `requiredParams` 生成 JSON 骨架（每个必填参数值为空字符串）
- 其他 → `raw` 格式

> **注意**：这里生成的是**请求体骨架**，供用户填入真实参数调用，不是响应体。

---

### 11. ws — WebSocket 层

---

#### `MockWebSocketHandler.java`

**职责**：单个 WebSocket 端点的处理器，实现连接欢迎/断开消息、消息模式匹配回显、心跳。

`handle(WebSocketSession)` 的响应式组合：
```
welcome（onConnect 消息）
  → Mono.firstWithSignal(messages处理流, heartbeat定时流)  // 两者并发，任一完成则结束
  → disconnect（onDisconnect 消息，异常时容错）
  → doFinally（更新 WS 连接数指标，打印断开日志）
```

`handleMessage()` 逻辑：遍历预编译的 `compiledPatterns`，第一个匹配的 `pattern` → 发送对应 `response`。

`template()` 模板方法：
- 替换 `{{sessionId}}` → `session.getId()`
- 替换 `{{message}}` → 客户端消息内容
- 最后调用 `BuiltinTemplateVars.apply()` 替换 `{{now}}` / `{{uuid}}` / `{{seq}}`

`resolveWsContent()` 优先使用 inline 文本，回退读取 onConnectFile / onDisconnectFile。

---

#### `WebSocketRouteConfiguration.java`

**职责**：注册 WebSocket 路由（`SimpleUrlHandlerMapping`），并挂载热加载回调。

- 首次注册：从 `holder.get()` 取当前配置，为每个 ws 端点创建 `MockWebSocketHandler`
- 热加载回调：`holder.setOnReload(() -> refreshMapping(...))` — 配置变更时自动重建 WS handler map
- `ResourceReader` 作为构造参数传入每个 `MockWebSocketHandler`

---

### 12. util — 工具层

---

#### `BuiltinTemplateVars.java`

**职责**：内置模板变量替换，HTTP 和 WebSocket 模板引擎共享。

| 变量 | 实现 | 特性 |
|------|------|------|
| `{{now}}` | `LocalDateTime.now()` 格式化为 `yyyy-MM-dd'T'HH:mm:ss` | 每次请求取当前时间 |
| `{{uuid}}` | `UUID.randomUUID()` | 每次请求生成新 UUID |
| `{{seq}}` | 静态 `AtomicLong`，`incrementAndGet()` | 全局自增，服务重启归零 |

`apply(String template)` 方法：正则扫描一遍，无 `{{` 直接返回原串（零开销）。

---

#### `ResourceReader.java`

**职责**：统一文件读取工具（`@Component`），支持三种路径前缀。

| 路径格式 | 读取方式 |
|----------|----------|
| `classpath:path/to/file` | Spring `ClassPathResource` |
| `file:/absolute/path` | `Files.readAllBytes(Paths.get(...))` |
| 无前缀 | 先尝试 classpath，再尝试文件系统 |

读取失败返回 `null` + 打印 error 日志，不抛异常（调用方判空处理）。

> 改为 `@Component` 的原因：测试时可通过 Mock 替换实现，避免依赖真实文件系统。

---

#### `JsonEscape.java`

**职责**：JSON 字符串值转义，单次字符遍历，零额外对象分配。

转义规则：`"` → `\"`，`\` → `\\`，`\n` → `\n`，`\r` → `\r`，`\t` → `\t`。

在 `applyTemplateInternal()` 中，当响应体是 JSON 格式（`contentType` 含 `json`）时，对参数值自动转义，防止注入破坏 JSON 结构。

---

## 三、mock-business 业务配置

> 纯 YAML，零 Java 代码。修改此目录下的文件配合热加载即可完成全部 Mock 配置。

---

#### `mock-endpoints.yml`

**职责**：**生产端点配置**，当前系统实际使用的所有 Mock 端点定义。

包含的端点（按业务分组）：
- 证通系列（form-encoded）：`zt-id-card`、`zt-id-image`、`zt-green-card`、`zt-green-card-image`
- 金联汇通系列（JSON）：`eid-id-card`（pathPattern）、`eid-id-image`、`eid-green-card`、`eid-green-card-image`
- 北京数字认证（JSON）：`bjca-org`
- 组代中心（XML/SOAP）：`nacao-org`、`nacao-wsdl`

---

#### `demo.yml`

**职责**：**配置示例与完整注释**，展示所有配置能力，供团队参考和新人学习。

包含 15 个带详细中文注释的示例，覆盖：
- Form / JSON / XML / GET 四种协议
- 精确路径 / pathPattern 两种路由
- 参数校验（必填 + 正则）
- 模板回显（`{{param.xxx}}` + `{{now}}` / `{{uuid}}` / `{{seq}}`）
- 条件响应（conditionalResponse）
- 延迟模拟（固定 / 随机范围）
- 外部文件响应（classpath: / file: 两种前缀）
- JS 脚本响应（时间戳、MD5 签名）
- WebSocket Mock（echo、心跳、模式匹配）

文件末尾附有**完整配置速查**（所有字段说明）和**常见问题解答**。

---

## 四、mock-boot 启动模块

> 薄壳模块，只做启动和 Swagger 配置，不含业务逻辑。

---

#### `MockApplication.java`

**职责**：Spring Boot 入口类。

```java
@SpringBootApplication(scanBasePackages = "com.mock")
// scanBasePackages 覆盖 com.mock.core 和 com.mock.boot 两个包
```

---

#### `MockOpenApiConfiguration.java`

**职责**：根据 `MockConfigProperties` **动态生成 Swagger/OpenAPI 文档**（`/swagger-ui.html`）。

为每个 HTTP 端点生成 OpenAPI `PathItem`：
- 按 `method` 注册到对应的 HTTP 方法操作（`get()` / `post()` 等）
- 请求体 Schema 按 `requiredParams` 生成字段（`StringSchema`）
- 响应示例填入 `responseBody` 和 `errorBody`

文档始终与 `MockConfigProperties` 同步（启动时生成，热加载**不触发**重新生成）。

---

#### `application.yml`

**职责**：Spring Boot 主配置。

关键配置项：
```yaml
spring.config.import: classpath:mock-endpoints.yml   # 引入业务配置
server.port: 8080
server.shutdown: graceful                             # 优雅停机，等待处理中的请求完成
spring.codec.max-in-memory-size: 1MB                 # 请求体最大缓冲（超大 XML/JSON 时调大）
spring.reactor.schedulers.boundedElastic:
  size: 8          # 阻塞任务线程池大小（脚本执行、文件 I/O）
  queue-size: 100000
management.endpoints.web.exposure.include: health,info,metrics,prometheus  # Actuator 暴露端点
```

---

#### `logback-spring.xml`

**职责**：日志输出配置，将应用日志和审计日志**分离到不同文件**。

| Appender | 输出目标 | 格式 | 滚动策略 |
|----------|----------|------|----------|
| `CONSOLE` | 控制台 | 完整格式（时间+线程+级别+类名+消息） | — |
| `FILE` | `logs/mock-service.log` | 完整格式 | 日滚动 + 100MB/文件，保留 30 天，总量 1GB |
| `AUDIT_FILE` | `logs/audit.log` | 仅时间+消息（精简） | 同 FILE |

`AUDIT` logger（`additivity=false`）只写 `AUDIT_FILE`，不输出到控制台，避免业务日志和审计日志混杂。

---

#### `static/mock-admin.html`

**职责**：纯 HTML + 原生 JavaScript 实现的**可视化管理控制台**（无外部依赖）。

功能面板：
- **Endpoints 面板**：调用 `/mock/_admin/routes`，表格展示所有已注册的 HTTP 和 WebSocket 端点
- **录制面板**：录制开始/停止、查看录制记录列表、保存/加载/清空操作
- **回放面板**：回放开始/停止
- **配置面板**：触发热加载（POST `/mock/_admin/reload`）

---

#### `responses/demo-response.json`

**职责**：演示用的外部响应体文件，供 `demo.yml` 中 `responseBodyFile: classpath:responses/demo-response.json` 示例使用。

---

## 五、测试文件

> 位于 `mock-core/src/test/`，105 个测试，0 失败。

---

| 测试文件 | 测试数 | 覆盖范围 |
|----------|--------|----------|
| `MockConfigPropertiesTest` | 16 | 配置校验（必填字段、重复路由、非法 MediaType、空列表、path/pathPattern 同时设置告警） |
| `MockRequestHandlerTest` | 26 | 核心处理器（无校验/200、必填缺失/400、自定义错误码、空值视为缺失、格式校验通过/失败、格式检查跳过、必填优先、模板替换、路径变量合并、响应延迟、条件响应、listRoutes） |
| `FormUrlEncodedAdapterTest` | 7 | Form 参数提取（正常、空 body、多值合并、特殊字符、Content-Type 匹配） |
| `JsonAdapterTest` | 8 | JSON 参数提取（顶层字段、嵌套 dot-notation、数组、无效 JSON、空 body） |
| `XmlAdapterTest` | 10 | XML/SOAP 参数提取（简单 XML、SOAP Envelope、嵌套、注释节点、空 body） |
| `MockWebSocketHandlerTest` | 5 | WS 模板引擎（sessionId 替换、message 替换、多占位符、null 输入、无占位符） |
| `MockRouterConfigurationTest` | 33 | 端到端集成测试（精确路径 200、pathPattern 匹配、404、参数校验 400、条件响应、模板回显、响应延迟、管理端点、录制/回放、WS 注册、WSDL） |

**测试配置**：
- `mock-core/src/test/resources/application.yml` — 测试专用配置（随机端口）
- `mock-core/src/test/resources/mock-endpoints.yml` — 测试端点（10 个，覆盖各功能场景）
- `TestMockApplication.java` — `@SpringBootApplication` 测试启动入口

---

## 六、非代码文件（配置/构建/部署）

---

#### `pom.xml`（根目录）

**职责**：多模块 Maven 父 POM，统一管理。

- 继承 `spring-boot-starter-parent:2.7.18`
- 声明 3 个子模块：`mock-core`、`mock-business`、`mock-boot`
- 锁定 Java 8 编译（`maven.compiler.source/target = 1.8`）
- UTF-8 编码

子模块 POM 分别声明各自依赖（Spring Boot WebFlux、Jackson、SnakeYAML、Micrometer、Swagger 等）。

---

#### `Dockerfile`

**职责**：多阶段 Docker 镜像构建配置。

```
基础镜像：eclipse-temurin:8-jre（精简 JRE，非完整 JDK）
COPY：打包好的 mock-boot-1.0.0.jar → /app/app.jar
HEALTHCHECK：每 30s 调用 /actuator/health，失败 3 次标记 unhealthy
EXPOSE：8080
ENTRYPOINT：sh -c "java $JAVA_OPTS -jar /app/app.jar"（支持通过环境变量传 JVM 参数）
```

---

#### `docker-compose.yml`

**职责**：本地开发和简单部署的容器编排配置。

- 挂载 `mock-business/src/main/resources/mock-endpoints.yml` 到容器 `/config/`（配合热加载）
- 默认 JVM 参数：`-Xms128m -Xmx256m`
- `restart: unless-stopped`（意外退出自动重启）
- 健康检查与 Dockerfile 保持一致

---

#### `start.sh` / `start.bat`

**职责**：快速启动脚本，自动设置 JVM 参数后直接运行 JAR。

```bash
# start.sh 核心逻辑
java -Xms128m -Xmx256m -jar mock-boot/target/mock-boot-1.0.0.jar "$@"
```

`"$@"` 允许追加 `--server.port=9090` 等 Spring Boot 参数。

---

#### `mock-core/recordings/recordings.json`

**职责**：录制数据的**持久化文件**，由 `POST /mock/_admin/recordings/save` 写入，`load` 读取。

格式：`RecordedExchange` 对象的 JSON 数组，可直接用文本编辑器查看和编辑。可以提交到 Git 供团队共享录制场景。

---

## 七、一次请求的完整代码路径

以 `POST /mock/ias/zt/id-card`（证通身份证认证）为例，追踪经过的每个类：

```
HTTP 请求
    │
    ▼
AuditWebFilter.filter()           ← 记录请求开始时间
    │
    ▼
AdminAuthWebFilter.filter()       ← 路径不含 _admin，直接放行
    │
    ▼
MockRouterConfiguration           ← RouterFunction Lambda 执行
  routeKey = "POST /mock/ias/zt/id-card"
  adminRoutes.get(routeKey)       → null（不是管理端点）
  matches(request, ep)            → PathPattern 匹配到 zt-id-card 端点
  返回 HandlerFunction → handler.handle(request, ep)
    │
    ▼
MockRequestHandler.handle()
  recordingStore.isReplaying()    → false（非回放模式）
  adapters.stream()...            → FormUrlEncodedAdapter.supports() = true
  FormUrlEncodedAdapter.extractParams()  → Mono<Map>（异步提取 form 参数）
    │
    ▼（flatMap 在参数提取完成后）
  合并路径变量（无 pathPattern，跳过）
  hasMissingRequired()            → false（certseq/usernm/biztyp/ptyacct/ptycd 均存在）
  hasInvalidFormat()              → false（无 paramRules）
  resolveResponseBody()
    ResponseBodyResolver.resolve()
      ScriptBodyResolverStrategy.supports()     → false（无 responseScript）
      FileBodyResolverStrategy.supports()       → false（无 responseBodyFile）
      ConditionalBodyResolverStrategy.supports()→ false（无 conditionalResponse）
      StaticBodyResolverStrategy.supports()     → true
      StaticBodyResolverStrategy.resolve()      → Mono.just(responseBody 文本)
  applyTemplateWithType(body, params, "application/json")
    applyTemplateInternal()       → 无 {{param.xxx}} 占位符，原样返回
    BuiltinTemplateVars.apply()   → 无 {{now}}/{{uuid}}/{{seq}}，原样返回
  buildSuccessResponse()          → 200 + application/json + X-Mock-Endpoint:zt-id-card
  doRecord()                      → recording=false，跳过
  metrics.recordRequest()         → 更新 Counter 和 Timer
    │
    ▼
AuditWebFilter.doOnSuccess()      ← 记录 POST /mock/ias/zt/id-card → 200 (Nms)
    │
    ▼
HTTP 响应返回
```

---

## 八、关键接口与扩展点一览

| 接口 | 位置 | 扩展方式 | 用途 |
|------|------|----------|------|
| `BodyResolverStrategy` | handler/ | 实现接口 + `@Component` | 新增响应来源（数据库、Groovy 等） |
| `RecordingStore` | record/ | 实现接口 + `@Primary @Component` | 替换录制存储（Redis、MySQL 等） |
| `ScriptEngineExecutor` | script/ | 实现接口 + `@Primary @Component` | 替换 JS 引擎（GraalVM、Groovy 等） |
| `ProtocolAdapter` | protocol/ | 实现接口，注册为 Bean | 支持新协议（gRPC、GraphQL 等） |
| `buildAdminRoutes()` | route/ | 在 Map 中加一行 | 新增管理端点 |

---

*文档生成时间：2026-05-20 | 对应代码版本：`286ac74`（refactor 提交）*
