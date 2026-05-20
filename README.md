# Mock Service

**统一 Mock 服务** — 身份认证三方接口模拟工具，零 Java 代码新增端点，YAML 驱动，响应式架构。

适用于：证通、金联汇通、北京数字认证、组代中心等身份认证接口的本地/测试环境模拟。

---

## 目录

- [1. 架构设计](#1-架构设计)
- [2. 核心能力](#2-核心能力)
- [3. 快速开始（小白 5 分钟上手）](#3-快速开始小白-5-分钟上手)
- [4. 配置指南](#4-配置指南)
- [5. 最佳实践](#5-最佳实践)
- [6. 性能参考](#6-性能参考)
- [7. Docker 部署](#7-docker-部署)
- [8. 管理端点](#8-管理端点)
- [9. 管理界面](#9-管理界面)
- [10. 常见问题](#10-常见问题)
- [11. 进一步优化方向](#11-进一步优化方向)

---

## 1. 架构设计

### 1.1 模块结构

```
mock-service/
├── mock-core/          # 核心框架（可复用 JAR，零业务依赖）
│   ├── config/         # 配置模型、校验、热加载
│   ├── route/          # 动态路由注册
│   ├── handler/        # 请求处理器（校验 + 模板 + 条件响应 + 录制/回放）
│   ├── protocol/       # 协议适配器（Form/JSON/XML）
│   ├── record/         # 请求录制存储与回放
│   ├── ws/             # WebSocket Mock 处理器与路由
│   └── audit/          # 审计日志过滤器
├── mock-business/      # 业务配置（纯 YAML，无 Java 代码）
│   └── resources/
│       ├── mock-endpoints.yml  # 生产端点配置
│       └── demo.yml           # 配置参考与示例
└── mock-boot/          # Spring Boot 启动壳
    └── resources/
        ├── application.yml    # 主配置
        ├── logback-spring.xml # 日志配置（AUDIT 独立文件）
        └── static/
            └── mock-admin.html # 管理控制台
```

### 1.2 请求处理流程

```
HTTP 请求
  │
  ▼
┌─────────────────────────────────────────────────────┐
│ AuditWebFilter (审计日志)                            │
│   格式: POST /path → 200 (12ms)                     │
│   AUDIT logger 写入独立文件 logs/audit.log           │
└─────────────────────────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────────────────────────┐
│ MockRouterConfiguration (动态 RouterFunction)       │
│   1. 匹配管理端点 (/mock/_admin/*)                  │
│   2. 匹配录制/回放控制端点                           │
│   3. 遍历 endpoint 列表，PathPattern 匹配            │
│   4. 提取路径变量 → request attributes              │
│   5. 匹配成功 → MockRequestHandler.handle()         │
│   6. 无匹配 → 404                                   │
└─────────────────────────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────────────────────────┐
│ MockRequestHandler.handle()                         │
│   1. 回放检查 → 有匹配录制则直接返回                 │
│   2. ProtocolAdapter 提取参数                        │
│   3. 合并路径变量 (body 参数优先级更高)              │
│   4. 必填校验 + 正则格式校验                         │
│   5. 条件响应匹配 (conditionalResponse)             │
│   6. {{param.xxx}} 模板引擎替换                     │
│   7. 可选 responseDelay 延迟                        │
│   8. 录制 (recording mode)                          │
│   9. 构建响应                                       │
└─────────────────────────────────────────────────────┘
```

### 1.3 设计决策

| 决策 | 理由 |
|------|------|
| WebFlux + Netty（非 Servlet） | 全异步非阻塞，适合高并发 Mock 场景 |
| RouterFunction（非 @RequestMapping） | 运行时动态注册/替换路由，支持热加载 |
| DataBuffer.wrap(bytes)（非 bodyValue） | 精确控制字节输出，避免 Spring 自动序列化 |
| AtomicReference 配置持有 | 线程安全、无锁的热加载配置 |
| SnakeYAML 直解析（非 Spring Environment） | 热加载时独立解析，不依赖 Spring 生命周期 |
| Java 8 | 兼容遗留系统调用方环境 |

---

## 2. 核心能力

### 2.1 协议适配

| Content-Type | 适配器 | 参数提取方式 |
|---|---|---|
| `application/x-www-form-urlencoded` | FormUrlEncodedAdapter | `exchange.getFormData()` |
| `application/json` | JsonAdapter | Jackson `readTree()` 提取顶层 String 字段 |
| `text/xml` | XmlAdapter | DOM 解析提取叶子元素（含 SOAP Envelope） |

### 2.2 路由匹配

- **精确路径**: `path: /mock/ias/zt/id-card`
- **动态路径**: `pathPattern: /api/users/{userId}/orders/{orderId}` 支持 `{variable}` 占位符
- **路径变量**: 自动提取并合并到参数 Map，模板 `{{param.userId}}` 可回显

### 2.3 参数校验

- **必填检查**: `validation.requiredParams: [name, idNumber]`
- **正则格式**: `validation.paramRules: [{name: idNumber, pattern: "^\\d{18}$"}]`
- **校验顺序**: 必填优先 → 格式次之 → 全部通过才返回 200
- **自定义错误**: 可配置 `errorStatus` + `errorBody`（支持模板）

### 2.4 条件响应（根据参数值返回不同内容）

```yaml
conditionalResponse:
  param: status
  cases:
    "0": '{"code":"0","message":"成功","data":"{{param.name}}"}'
    "1": '{"code":"1","message":"失败","reason":"{{param.name}}"}'
  defaultResponse: '{"code":"-1","message":"未知状态"}'
```

根据请求参数 `status` 的值匹配不同响应体，无匹配时走 `defaultResponse`。

### 2.5 模板引擎

```
{{param.fieldName}}  →  替换为实际参数值
参数不存在           →  替换为空字符串 ""
无占位符             →  零开销原样返回
```

### 2.6 请求录制与回放

- **录制模式**: `POST /mock/_admin/record/start` → 所有请求+响应被自动捕获
- **回放模式**: `POST /mock/_admin/replay/start` → 匹配的请求直接返回已录制响应
- **持久化**: 录制记录可保存到 `recordings/recordings.json` 文件
- **跨团队共享**: 录制文件可提交到 Git，团队成员 load 后重现相同场景

### 2.7 WebSocket Mock

```yaml
mock:
  websockets:
    - id: ws-echo
      path: /ws/echo
      onConnect: '{"type":"connected","sessionId":"{{sessionId}}"}'
      messageHandlers:
        - pattern: "ping"
          response: "pong"
        - pattern: ".*"
          response: '{"type":"echo","original":"{{message}}"}'
      heartbeat:
        interval: 30000
        message: '{"type":"ping"}'
```

支持：连接欢迎消息、模式匹配回显、`{{message}}`/`{{sessionId}}` 模板、心跳、延迟响应。

### 2.8 Swagger / OpenAPI 文档

- 启动后访问 `http://localhost:8080/swagger-ui.html` 即可查看自动生成的 API 文档
- 基于 `MockConfigProperties` 实时生成，始终与配置保持同步
- 显示每个端点的请求体 Schema、响应示例、校验规则

### 2.9 热加载

- `POST /mock/_admin/reload` — 手动触发，从 classpath 重载配置
- `ConfigFileWatcher` — 配置 `mock.watch-path` 启用文件监听自动热加载
- 重载时做完整 fail-fast 校验，失败则保持旧配置不变

### 2.10 审计日志

- `AUDIT` Logger 记录每个请求的方法、路径、状态码、耗时
- 格式: `POST /mock/ias/zt/id-card → 200 (12ms)`
- 独立日志文件 `logs/audit.log`，30 天滚动保留，单文件最大 100MB
- 应用日志写入 `logs/mock-service.log`，控制台同步输出

### 2.11 其他

- **响应延迟**: `responseDelay: 500` 固定延迟；`responseDelayMax: 2000` 随机范围 [delay, delayMax]（毫秒），模拟真实网络抖动
- **Prometheus 指标**: `GET /actuator/prometheus` 导出 `mock_requests_total`、`mock_request_duration_seconds` 等自定义指标
- **Postman 导出**: `GET /mock/_admin/postman` 一键导出 Postman Collection v2.1
- **响应体外部文件**: `responseBodyFile: classpath:responses/data.json` 或 `file:./config/data.json`
- **动态响应脚本**: `responseScript` 字段支持 JavaScript (Nashorn)，可访问 `params` 变量
- **管理界面**: 访问 `/mock-admin.html` 使用可视化控制台
- **健康检查**: `/actuator/health`、`/actuator/metrics`
- **Docker 支持**: 多阶段 Dockerfile + docker-compose.yml
- **启动校验**: fail-fast，无效配置拒绝启动

---

## 3. 快速开始（小白 5 分钟上手）

### 3.1 前提条件

- **JDK 8** 或更高版本
- **Maven** 3.6+（或在 IDEA 中直接运行）

验证环境：
```bash
java -version     # 应显示 1.8.0_xxx
mvn -version      # 应显示 3.6+
```

### 3.2 启动服务

**方式一：命令行**

```bash
cd mock-service
mvn clean package -DskipTests
java -jar mock-boot/target/mock-boot-1.0.0.jar

# 看到以下日志说明启动成功:
#   Registered 10 mock endpoints:
#   POST /mock/ias/zt/id-card -> ...
#   Registered WebSocket endpoint: /ws/echo -> ...
#   Netty started on port 8080
```

**方式二：IDEA**

1. `File → Open → 选择 mock-service 目录`
2. 找到 `mock-boot/.../MockApplication.java`
3. 右键 → `Run 'MockApplication.main()'`

### 3.3 验证服务

```bash
# 1. Form 端点测试
curl -X POST http://localhost:8080/mock/ias/zt/id-card \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "name=test&idNo=123456"

# 2. 查看所有路由
curl http://localhost:8080/mock/_admin/routes

# 3. Swagger UI（浏览器打开）
# http://localhost:8080/swagger-ui.html

# 4. 管理控制台（浏览器打开）
# http://localhost:8080/mock-admin.html
```

### 3.4 添加你自己的端点

打开 `mock-business/src/main/resources/mock-endpoints.yml`，在 `mock.endpoints:` 列表末尾添加：

```yaml
    - id: my-first-mock
      description: "我的第一个Mock端点"
      method: POST
      path: /my/api/test
      contentType: application/json
      responseContentType: application/json
      responseStatus: 200
      responseBody: |
        {"code":"0","message":"OK","echo":"{{param.name}}"}
```

调用 `POST /mock/_admin/reload` 热加载（无需重启），然后测试：

```bash
curl -X POST http://localhost:8080/my/api/test \
  -H "Content-Type: application/json" \
  -d '{"name":"张三"}'
# 返回: {"code":"0","message":"OK","echo":"张三"}
```

---

## 4. 配置指南

### 4.1 必填字段（HTTP 端点）

| 字段 | 说明 | 示例 |
|------|------|------|
| `id` | 唯一标识（英文） | `zt-id-card` |
| `method` | HTTP 方法 | `GET` / `POST` |
| `path` 或 `pathPattern` | 路由路径（至少一个） | `/mock/api/test` |
| `responseContentType` | 响应 Content-Type | `application/json` |
| `responseBody` | 响应体文本 | 见模板语法 |

### 4.2 可选字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `contentType` | — | 请求 Content-Type（GET 不需要） |
| `responseStatus` | 200 | HTTP 状态码（可用于模拟错误） |
| `responseDelay` | 0 | 响应延迟（毫秒），模拟慢网络 |
| `description` | — | 业务描述（建议填写） |
| `validation` | — | 参数校验配置 |
| `conditionalResponse` | — | 条件响应配置 |
| `pathPattern` | — | 路径模式，支持 `{variable}` |

### 4.3 校验配置详解

```yaml
validation:
  requiredParams:          # 必填参数列表
    - name
    - idNumber
  paramRules:              # 格式校验规则（可选）
    - name: idNumber
      pattern: "^\\d{18}$"
      errorMessage: "身份证号必须是18位数字"
  errorStatus: 400         # 校验失败 HTTP 状态码（默认 400）
  errorBody: |             # 校验失败响应体（支持模板）
    {"error_no":"1001","error_info":"缺少必填字段"}
```

### 4.4 条件响应配置

```yaml
conditionalResponse:
  param: status                    # 判断依据的参数名
  cases:                           # 值 → 响应体 映射
    "0": '{"code":"0","message":"成功"}'
    "1": '{"code":"1","message":"失败"}'
  defaultResponse: |               # 无匹配时的兜底
    {"code":"-1","message":"未知状态: {{param.status}}"}
```

### 4.5 WebSocket 端点配置

```yaml
mock:
  websockets:
    - id: ws-echo
      description: "WebSocket回显测试"
      path: /ws/echo
      onConnect: '{"type":"connected"}'
      onDisconnect: '{"type":"disconnected"}'
      messageHandlers:
        - pattern: "ping"
          response: "pong"
        - pattern: ".*"
          response: '{"echo":"{{message}}"}'
      heartbeat:
        interval: 30000
        message: '{"type":"ping"}'
      delay: 0
```

### 4.6 模板语法

```
{{param.fieldName}}    → 替换为字段值
{{param.missing}}      → 字段不存在时替换为 ""
无占位符               → 零开销原样返回
```

WebSocket 模板额外支持：`{{sessionId}}`、`{{message}}`

---

## 5. 最佳实践

### 5.1 端点命名规范

建议 `id` 采用 `{provider}-{bizType}` 格式：
```
zt-id-card       # 证通 - 简项身份证
eid-id-card      # 金联汇通 - 简项身份证
bjca-org         # 北京数字认证 - 组织机构
```

### 5.2 配置组织

- **生产配置**: `mock-endpoints.yml` — 实际需要的端点
- **参考文档**: `demo.yml` — 完整示例供团队参考
- **测试配置**: `mock-core/src/test/resources/application.yml` — 测试端点

### 5.3 校验配置建议

- **始终配置 `requiredParams`**：让 Mock 尽早暴露调用方参数错误
- **错误响应体保持统一格式**：与真实接口的错误格式一致
- **模板回显请求参数**：用 `{{param.xxx}}` 回显缺失参数，方便定位

### 5.4 录制/回放工作流

```
1. POST /mock/_admin/record/start        # 开始录制
2. 执行测试场景（手动或自动化）          # 所有请求被自动捕获
3. POST /mock/_admin/record/stop         # 停止录制
4. POST /mock/_admin/recordings/save     # 保存到文件
5. 将 recordings/recordings.json 提交 Git
6. 其他团队成员:
   POST /mock/_admin/recordings/load     # 加载录制
   POST /mock/_admin/replay/start        # 开启回放
```

### 5.5 模拟真实场景

```yaml
# 模拟超时
- id: slow-api
  responseDelay: 3000

# 模拟业务错误
- id: error-400
  responseStatus: 400
  responseBody: '{"error_no":"1001","error_info":"参数错误"}'

# 模拟服务异常
- id: error-500
  responseStatus: 500
  responseBody: '{"error_no":"9999","error_info":"服务内部异常"}'
```

---

## 6. 性能参考

| 项目 | 值 |
|------|-----|
| CPU | Intel Core i7 (4 核 8 线程) |
| JVM | OpenJDK 1.8.0_321, -Xms128m -Xmx256m |
| 框架 | Spring Boot 2.7.18, WebFlux (Netty) |
| 理论吞吐 | 10,000+ req/s（取决于硬件和 payload） |
| 启动堆内存 | ~80MB |
| 稳态堆内存 | ~120MB（含 Netty Buffer Pool） |
| 无延迟响应 | <5ms（局域网） |

---

## 7. Docker 部署

```bash
# 构建与启动
mvn clean package -DskipTests
docker build -t mock-service:1.0.0 .
docker run -d -p 8080:8080 --name mock-service mock-service:1.0.0

# 带热加载挂载
docker run -d -p 8080:8080 \
  -e JAVA_OPTS="-Dmock.watch-path=file:/config/mock-endpoints.yml" \
  -v $(pwd)/mock-endpoints.yml:/config/mock-endpoints.yml \
  mock-service:1.0.0

# Docker Compose
docker-compose up -d
```

---

## 8. 管理端点

### HTTP Mock 管理

| 端点 | 方法 | 说明 |
|------|------|------|
| `/mock/_admin/routes` | GET | 返回所有已注册路由（含 WebSocket）的 JSON 清单 |
| `/mock/_admin/reload` | POST | 从 classpath 重新加载配置 |

### 录制/回放控制

| 端点 | 方法 | 说明 |
|------|------|------|
| `/mock/_admin/record/start` | POST | 开始录制 |
| `/mock/_admin/record/stop` | POST | 停止录制 |
| `/mock/_admin/recordings` | GET | 列出所有录制记录 |
| `/mock/_admin/recordings` | DELETE | 清空录制记录 |
| `/mock/_admin/recordings/save` | POST | 保存录制到文件 |
| `/mock/_admin/recordings/load` | POST | 从文件加载录制 |
| `/mock/_admin/replay/start` | POST | 开启回放模式 |
| `/mock/_admin/replay/stop` | POST | 关闭回放模式 |

### Spring Actuator

| 端点 | 方法 | 说明 |
|------|------|------|
| `/actuator/health` | GET | 健康检查 |
| `/actuator/metrics` | GET | HTTP 请求指标 |

### 文档与界面

| 路径 | 说明 |
|------|------|
| `/swagger-ui.html` | Swagger UI — 自动生成的 API 文档 |
| `/mock-admin.html` | 管理控制台 — 可视化查看/管理端点、录制、回放 |

---

## 9. 管理界面

访问 `http://localhost:8080/mock-admin.html` 使用可视化控制台，支持：

- **Endpoints 面板**: 查看所有已注册端点（HTTP + WebSocket），一键刷新
- **录制面板**: 开始/停止录制，查看录制记录，保存/加载/清空
- **回放面板**: 开启/关闭回放模式
- **配置面板**: 一键热加载

纯 HTML + 原生 JavaScript，无外部依赖。

---

## 10. 常见问题

**Q: 修改 YAML 后一定要重启吗？**
A: 不需要。调用 `POST /mock/_admin/reload` 热加载，或配置 `mock.watch-path` 启用文件监听。

**Q: 如何让响应随请求参数变化？**
A: 使用 `{{param.fieldName}}` 模板占位符；如果需要值→内容的映射，使用 `conditionalResponse`。

**Q: 录制和回放怎么用？**
A: 开始录制 → 执行测试场景 → 停止录制 → 保存文件。回放时加载文件 → 开启回放模式，后续匹配请求直接返回录制内容。

**Q: WebSocket 怎么测试？**
A: 配置 `mock.websockets` YAML，启动后使用 `wscat -c ws://localhost:8080/ws/echo` 或浏览器 DevTools 连接测试。

**Q: 审计日志在哪里？**
A: 应用日志在 `logs/mock-service.log`，审计日志独立写入 `logs/audit.log`。均配置了 30 天滚动保留。

**Q: Swagger UI 怎么访问？**
A: 启动后浏览器打开 `http://localhost:8080/swagger-ui.html`。

**Q: 配置错误会怎样？**
A: 启动时 fail-fast 拒绝启动；热加载时保持旧配置不变并打印错误日志。

---

## 11. 已实现的高级特性

以下特性已全部实现，详见 `demo.yml` 完整示例：

| 特性 | 说明 | 配置字段 |
|------|------|----------|
| Prometheus 指标导出 | 按 endpoint/method/status 统计请求数、延迟、录制/回放状态、WS 连接数 | `/actuator/prometheus` |
| 响应体外部文件 | 支持 `classpath:` 和 `file:` 前缀引用外部响应文件 | `responseBodyFile` |
| 随机延迟范围 | 在 `[responseDelay, responseDelayMax]` 内随机取值，模拟真实网络抖动 | `responseDelayMax` |
| 动态响应脚本 | JavaScript/Nashorn 生成签名、时间戳等动态内容 | `responseScript` |
| Postman Collection 导出 | `GET /mock/_admin/postman` 一键导出 Collection v2.1 | — |

### 未来可探索方向

以下方向可根据实际需求选择性推进：

| 方向 | 说明 | 复杂度 |
|------|------|--------|
| GraphQL Mock | 支持 GraphQL query/mutation 模拟 | 高 |
| gRPC 协议支持 | 新增 ProtocolAdapter 支持 gRPC/protobuf | 高 |
| 多租户 | 不同命名空间的配置隔离 | 高 |
| Admin API Key 认证 | 管理端点 X-API-Key 鉴权 | 中 |

---

## 附录: 测试覆盖

```
105 个测试, 0 失败, 0 错误

MockConfigPropertiesTest ........... 16 个 (配置校验)
FormUrlEncodedAdapterTest ..........  7 个 (Form 参数提取)
JsonAdapterTest ....................  8 个 (JSON 参数提取)
XmlAdapterTest ....................  10 个 (XML/SOAP 参数提取)
MockRequestHandlerTest ............  26 个 (处理器: 校验/模板/延迟/条件响应/路径变量)
MockWebSocketHandlerTest ...........  5 个 (WebSocket 模板引擎)
MockRouterConfigurationTest .......  33 个 (端到端集成测试: HTTP + 录制/回放 + WebSocket)
```

---

*Built with Spring Boot 2.7.18 + WebFlux (Netty) + Java 8*
