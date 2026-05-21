# Mock Service

**统一 Mock 服务** — 身份认证三方接口模拟工具。零 Java 代码新增端点，纯 YAML 配置驱动，响应式异步架构。

适用场景：证通、金联汇通、北京数字认证、组代中心等身份认证接口的本地/测试环境模拟，以及任何需要快速搭建 HTTP / WebSocket 桩服务的场合。

---

## 目录

- [1. 一分钟体验](#1-一分钟体验)
- [2. 架构设计](#2-架构设计)
- [3. 核心能力](#3-核心能力)
- [4. 快速开始（5 分钟上手）](#4-快速开始5-分钟上手)
- [5. 完整配置参考](#5-完整配置参考)
- [6. 模板引擎](#6-模板引擎)
- [7. 参数校验](#7-参数校验)
- [8. 录制与回放](#8-录制与回放)
- [9. WebSocket Mock](#9-websocket-mock)
- [10. 最佳实践](#10-最佳实践)
- [11. 管理端点参考](#11-管理端点参考)
- [11.5 管理控制台使用说明](#115-管理控制台使用说明)
- [12. 服务器部署与热更新](#12-服务器部署与热更新)
- [13. 扩展开发](#13-扩展开发)
- [14. 常见问题](#14-常见问题)
- [15. 测试覆盖](#15-测试覆盖)

---

## 1. 一分钟体验

```bash
# 启动服务（见第 4 节）
java -jar mock-boot/target/mock-boot-1.0.0.jar

# 发一个请求，马上看到 Mock 响应
curl -X POST http://localhost:8080/mock/ias/zt/id-card \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "certseq=SEQ001&usernm=张三&biztyp=A001&ptyacct=acc001&ptycd=cd001"
```

返回：
```json
{
  "error_no": "0",
  "results": [{ "status": "1", "respinfo": "认证一致", "name": "测试姓名" }]
}
```

不满意响应内容？打开 `mock-business/src/main/resources/mock-endpoints.yml` 改一行，然后：

```bash
curl -X POST http://localhost:8080/mock/_admin/reload
# {"status":"ok","endpoints":10,"websockets":1}  — 热加载完成，无需重启
```

---

## 2. 架构设计

### 2.1 模块结构

```
mock-service/
├── mock-core/          # 核心框架（可复用 JAR，零业务依赖）
│   ├── config/         # 配置模型 & 热加载（EndpointConfig / ConfigFileWatcher）
│   ├── route/          # 动态路由注册（MockRouterConfiguration — 路由表驱动）
│   ├── handler/        # 请求处理器 & 响应体责任链
│   │   ├── MockRequestHandler.java     # 核心分发：校验→模板→构建响应
│   │   ├── ResponseBodyResolver.java   # 责任链入口
│   │   ├── BodyResolverStrategy.java   # 策略接口（可扩展）
│   │   ├── ScriptBodyResolverStrategy  # 优先级 1：JS 脚本
│   │   ├── FileBodyResolverStrategy    # 优先级 2：外部文件
│   │   ├── ConditionalBodyResolver     # 优先级 3：条件分支
│   │   └── StaticBodyResolverStrategy  # 优先级 4：静态文本（兜底）
│   ├── protocol/       # 协议适配器（Form / JSON / XML）
│   ├── record/         # 录制存储（RecordingStore 接口 + InMemoryRecordingStore）
│   ├── script/         # 脚本引擎（ScriptEngineExecutor 接口 + ScriptExecutor Nashorn）
│   ├── ws/             # WebSocket Mock 处理器与路由
│   ├── audit/          # 审计日志过滤器（参数值已脱敏）
│   ├── metrics/        # Prometheus 自定义指标
│   ├── postman/        # Postman Collection 导出
│   └── util/           # ResourceReader（@Component）/ BuiltinTemplateVars
├── mock-business/      # 业务配置（纯 YAML，零 Java 代码）
│   └── resources/
│       ├── mock-endpoints.yml   # 生产端点配置
│       └── demo.yml             # 完整示例与注释说明
└── mock-boot/          # Spring Boot 启动壳
    └── resources/
        ├── application.yml     # 主配置
        ├── logback-spring.xml  # 日志（AUDIT 独立文件）
        └── static/
            └── mock-admin.html # 可视化管理控制台
```

### 2.2 请求处理流程

```
HTTP 请求
    │
    ▼
┌─────────────────────────────────────────┐
│ AuditWebFilter                          │  记录方法/路径/状态/耗时（参数值脱敏）
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│ AdminAuthWebFilter (可选 X-API-Key)     │  保护 /mock/_admin/** 端点
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│ MockRouterConfiguration                 │
│   1. 路由表匹配管理端点 (O(1) 查找)     │
│   2. 遍历 endpoint 列表 PathPattern 匹配│
│   3. 提取路径变量 → request attributes  │
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│ MockRequestHandler.handle()             │
│   1. 回放检查 → 有录制直接返回          │
│   2. ProtocolAdapter 提取请求参数       │
│   3. 合并路径变量（body 参数优先）       │
│   4. 必填校验 → 格式校验               │
│   5. ResponseBodyResolver 责任链        │
│      Script(1) > File(2) > Cond(3) > Static(4) │
│   6. BuiltinTemplateVars 内置变量替换   │
│   7. {{param.xxx}} 占位符替换           │
│   8. 录制 / 可选延迟 / 返回响应         │
└─────────────────────────────────────────┘
```

### 2.3 关键设计决策

| 决策 | 理由 |
|------|------|
| WebFlux + Netty | 全异步非阻塞，一个线程池处理上千并发 Mock 请求 |
| 路由表替代 if-else | 新增管理端点在 Map 中加一行，无需触碰匹配逻辑 |
| BodyResolverStrategy 责任链 | 新增响应来源（Groovy / Faker 等）只需加一个 @Component |
| RecordingStore 接口 | 替换为 Redis 实现即可跨实例共享录制数据 |
| AtomicReference 配置持有 | 热加载无锁、对路由匹配零阻塞 |
| SnakeYAML SafeConstructor | 禁止 YAML 标签实例化任意 Java 类，防止注入 |
| 录制/回放互斥状态机 | RECORDING 与 REPLAYING 不可并存，切换时自动停止另一方 |

---

## 3. 核心能力

| 能力 | 说明 |
|------|------|
| 3 种协议适配 | form-urlencoded / JSON / XML(SOAP) |
| 精确 & 动态路径 | `path` 精确匹配，`pathPattern` 支持 `{variable}` |
| 参数校验 | 必填检查 + 正则格式校验，两类失败可返回不同错误体 |
| 模板引擎 | `{{param.xxx}}` + 内置变量 `{{now}}` `{{uuid}}` `{{seq}}` |
| 条件响应 | 根据参数值映射不同响应体 |
| 响应体优先级链 | Script > 外部文件 > 条件分支 > 静态文本 |
| 响应延迟 | 固定延迟 / [min, max] 随机范围 |
| 录制 & 回放 | 录制真实请求，回放复现场景，文件持久化可跨团队共享 |
| WebSocket Mock | 连接欢迎/断开消息、模式匹配回显、心跳 |
| 热加载 | API 触发 or 文件监听自动触发，保持旧配置直到新配置校验通过 |
| Prometheus 指标 | 请求计数、延迟直方图、录制/回放状态、WS 连接数 |
| Postman 导出 | 一键生成 Collection v2.1，请求体按必填参数自动骨架化 |
| 管理控制台 | `/mock-admin.html` 可视化查看端点、录制、回放 |
| Swagger UI | `/swagger-ui.html` 自动生成 API 文档 |

---

## 4. 快速开始（5 分钟上手）

### 4.1 前提条件

- JDK 8+（推荐 JDK 8，JDK 11+ 会禁用 Nashorn 脚本功能）
- Maven 3.6+

```bash
java -version  # 应显示 1.8.x 或以上
mvn -version   # 应显示 3.6+
```

### 4.2 编译并启动

```bash
cd mock-service
mvn clean package -DskipTests
java -jar mock-boot/target/mock-boot-1.0.0.jar
```

看到以下日志说明启动成功：
```
Registered 10 mock endpoints:
  POST /mock/ias/zt/id-card -> 证通简项身份证认证 (id=zt-id-card, status=200)
  ...
Registered WebSocket endpoint: /ws/echo -> WebSocket Echo 测试
Netty started on port 8080
```

**IDEA 启动：** `mock-boot/.../MockApplication.java` → 右键 → Run

### 4.3 验证服务

```bash
# 查看所有已注册路由
curl http://localhost:8080/mock/_admin/routes

# 测试 Form 端点（证通）
curl -X POST http://localhost:8080/mock/ias/zt/id-card \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "certseq=001&usernm=张三&biztyp=A001&ptyacct=acc&ptycd=cd"

# 测试 JSON 端点（金联汇通，动态路径）
curl -X POST http://localhost:8080/vals-ap/identity/1/sync/APP001 \
  -H "Content-Type: application/json" \
  -d '{"app_id":"APP001","biz_type":"A001","user_id_info":"xxx"}'

# 浏览器打开管理控制台
open http://localhost:8080/mock-admin.html
```

### 4.4 添加第一个自定义端点

打开 `mock-business/src/main/resources/mock-endpoints.yml`，在 `mock.endpoints:` 末尾追加：

```yaml
    - id: my-first-mock
      description: "我的第一个 Mock 端点"
      method: POST
      path: /my/api/hello
      contentType: application/json
      responseContentType: application/json
      responseStatus: 200
      responseBody: |
        {
          "code": "0",
          "message": "你好，{{param.name}}！",
          "requestId": "{{uuid}}",
          "timestamp": "{{now}}"
        }
      validation:
        requiredParams:
          - name
        errorStatus: 400
        errorBody: |
          {"code":"1001","message":"缺少必填参数 name"}
```

热加载（**无需重启**）：

```bash
curl -X POST http://localhost:8080/mock/_admin/reload
```

测试：

```bash
curl -X POST http://localhost:8080/my/api/hello \
  -H "Content-Type: application/json" \
  -d '{"name":"张三"}'
# 返回：{"code":"0","message":"你好，张三！","requestId":"xxxx-uuid","timestamp":"2026-05-20T..."}
```

---

## 5. 完整配置参考

### 5.1 HTTP 端点字段

| 字段 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `id` | ✅ | — | 唯一标识（英文，如 `zt-id-card`） |
| `method` | ✅ | — | `GET` 或 `POST` |
| `path` 或 `pathPattern` | ✅ 二选一 | — | 路由路径（见 5.2） |
| `responseContentType` | ✅ | — | 响应 Content-Type |
| `contentType` | GET 可省 | — | 请求 Content-Type，决定协议适配器 |
| `responseStatus` | | `200` | HTTP 状态码 |
| `responseBody` | 三选一 | — | 静态响应体文本，支持模板 |
| `responseBodyFile` | 三选一 | — | 外部文件，`classpath:` 或 `file:` 前缀 |
| `responseScript` | 三选一 | — | Nashorn JS 脚本（JDK 8，优先级最高） |
| `responseDelay` | | `0` | 响应延迟（毫秒） |
| `responseDelayMax` | | `0` | 随机延迟上限，`> responseDelay` 时生效 |
| `description` | | — | 业务描述（强烈建议填写，显示在日志/控制台） |
| `validation` | | — | 参数校验（见第 7 节） |
| `conditionalResponse` | | — | 条件响应（见 5.3） |

### 5.2 路径配置

```yaml
# 精确路径 — 完全匹配请求 URL
path: /mock/ias/zt/id-card

# 动态路径 — {variable} 自动提取并注入模板
pathPattern: /vals-ap/identity/{type}/sync/{appId}
# 请求 /vals-ap/identity/1/sync/APP001 时：
# {{param.type}} = "1"，{{param.appId}} = "APP001"

# 注意：同时设置 path 和 pathPattern 时，优先使用 pathPattern（启动打 WARN）
```

### 5.3 条件响应

根据指定参数的值返回不同响应体：

```yaml
conditionalResponse:
  param: status          # 判断依据的参数名
  cases:
    "0": |               # 参数值 = "0" 时返回
      {"code":"0","message":"认证一致"}
    "1": |
      {"code":"1","message":"认证不一致"}
  defaultResponse: |     # 无匹配时的兜底（可省略，省略则回退到 responseBody）
    {"code":"-1","message":"未知状态：{{param.status}}"}
```

### 5.4 响应体优先级

```
responseScript  >  responseBodyFile  >  conditionalResponse  >  responseBody
```

同一端点只会使用优先级最高的那一项。

### 5.5 Content-Type 与协议适配器对应

| contentType | 适配器 | 参数提取方式 |
|-------------|--------|-------------|
| `application/x-www-form-urlencoded` | FormUrlEncodedAdapter | `key=value&...` 表单参数 |
| `application/json` | JsonAdapter | JSON 顶层字段（支持嵌套 dot-notation） |
| `text/xml` | XmlAdapter | XML/SOAP 叶子元素（DOM 解析） |
| 未配置（GET） | — | 无请求体，路径变量可用 |

---

## 6. 模板引擎

### 6.1 参数占位符

在 `responseBody`、`errorBody`、`formatErrorBody`、`conditionalResponse.cases` 和 WebSocket 消息中均可使用：

```
{{param.fieldName}}   → 替换为请求参数 fieldName 的值
{{param.missing}}     → 参数不存在时替换为空字符串（不报错）
```

参数来源（按优先级，后者覆盖前者）：
1. `pathPattern` 提取的路径变量
2. 请求体参数（form / JSON / XML）

### 6.2 内置变量

无需外部依赖，直接在任何模板中使用：

| 变量 | 说明 | 示例输出 |
|------|------|---------|
| `{{now}}` | 当前服务器时间 | `2026-05-20T14:30:00` |
| `{{uuid}}` | 随机 UUID | `550e8400-e29b-41d4-a716-446655440000` |
| `{{seq}}` | 全局自增序列号（服务重启归零） | `1`、`2`、`3`... |

WebSocket 模板额外支持：

| 变量 | 说明 |
|------|------|
| `{{sessionId}}` | 当前 WebSocket 连接 ID |
| `{{message}}` | 客户端发送的消息内容 |

### 6.3 综合示例

```yaml
responseBody: |
  {
    "code": "0",
    "requestId": "{{uuid}}",
    "processTime": "{{now}}",
    "serialNo": "REQ{{seq}}",
    "name": "{{param.name}}",
    "bizType": "{{param.bizType}}"
  }
```

---

## 7. 参数校验

### 7.1 完整校验配置

```yaml
validation:
  requiredParams:           # 必填参数列表（存在且非空才通过）
    - certseq
    - usernm
    - biztyp
  paramRules:               # 格式校验规则（可选，必填通过后才执行）
    - name: certseq
      pattern: "^[A-Z0-9]{6,20}$"
      errorMessage: "certseq 必须是 6-20 位大写字母或数字"
    - name: usernm
      pattern: "^.{2,20}$"
      errorMessage: "usernm 长度需在 2-20 个字符之间"
  errorStatus: 400          # 必填校验失败的 HTTP 状态码
  errorBody: |              # 必填校验失败的响应体
    {"error_no":"1001","error_info":"缺少必填字段"}
  formatErrorBody: |        # 格式校验失败的响应体（可选，不填则和 errorBody 相同）
    {"error_no":"1002","error_info":"参数格式不符合要求：{{param.certseq}}"}
```

### 7.2 校验流程

```
请求到达
    │
    ▼
必填检查（requiredParams）
    │ 任一缺失/为空
    ├─── 返回 errorStatus + errorBody
    │
    ▼ 全部通过
格式校验（paramRules）
    │ 任一不匹配
    ├─── 返回 errorStatus + formatErrorBody（未配置则用 errorBody）
    │
    ▼ 全部通过
解析响应体 → 模板替换 → 返回 responseStatus + responseBody
```

### 7.3 常用正则速查

```yaml
paramRules:
  - name: idNumber
    pattern: "^\\d{17}[\\dXx]$"    # 18 位身份证号（末位可为 X）
  - name: phone
    pattern: "^1[3-9]\\d{9}$"       # 11 位手机号
  - name: date
    pattern: "^\\d{4}-\\d{2}-\\d{2}$"  # yyyy-MM-dd 日期
  - name: biztyp
    pattern: "^A\\d{3}$"            # A+3位数字
```

---

## 8. 录制与回放

### 8.1 工作流

```
第一步：录制
  POST /mock/_admin/record/start   ← 开始录制
  [执行真实测试场景]               ← 所有命中 Mock 的请求+响应被自动记录
  POST /mock/_admin/record/stop    ← 停止录制

第二步：持久化（可选，跨团队共享）
  POST /mock/_admin/recordings/save   ← 保存到 recordings/recordings.json
  git add recordings/recordings.json  ← 提交到版本库

第三步：回放
  POST /mock/_admin/recordings/load   ← 加载录制文件
  POST /mock/_admin/replay/start      ← 开启回放模式
  [执行测试]                          ← 匹配的请求直接返回录制响应
  POST /mock/_admin/replay/stop       ← 关闭回放
```

### 8.2 状态互斥说明

录制和回放**不可同时激活**（语义相互矛盾）。当你调用 `record/start` 时如果正在回放，服务会**自动停止回放**再开始录制，反之亦然——日志中会打印 WARN 提示。

### 8.3 回放匹配规则

回放按 **endpointId + HTTP 方法** 匹配，而不是精确 URL 路径。这意味着 `pathPattern` 动态路由（如 `/vals-ap/identity/{type}/sync/{appId}`）的录制也能被正确回放。

---

## 9. WebSocket Mock

### 9.1 配置示例

```yaml
mock:
  websockets:
    - id: ws-echo
      description: "WebSocket 回显测试"
      path: /ws/echo
      onConnect: '{"type":"connected","sessionId":"{{sessionId}}","time":"{{now}}"}'
      onDisconnect: '{"type":"disconnected"}'
      messageHandlers:
        - pattern: "ping"             # 精确匹配 "ping"
          response: "pong"
        - pattern: "(?i).*hello.*"    # 正则匹配（忽略大小写）
          response: '{"reply":"Hi! {{message}}"}'
        - pattern: ".*"               # 兜底：回显任意消息
          response: '{"echo":"{{message}}","id":"{{uuid}}"}'
      heartbeat:
        interval: 30000               # 每 30 秒发送心跳（毫秒）
        message: '{"type":"ping"}'
      delay: 200                      # 消息回显延迟 200ms
```

### 9.2 测试方法

```bash
# 方式一：wscat（npm install -g wscat）
wscat -c ws://localhost:8080/ws/echo

# 方式二：Chrome DevTools Console
const ws = new WebSocket("ws://localhost:8080/ws/echo");
ws.onmessage = e => console.log(e.data);
ws.send("ping");
```

---

## 10. 最佳实践

### 10.1 端点 ID 命名规范

推荐 `{provider}-{bizType}` 格式，全小写，用连字符：

```
zt-id-card         证通 — 简项身份证
zt-id-image        证通 — 人像认证
eid-id-card        金联汇通 — 简项身份证
bjca-org           北京数字认证 — 组织机构
nacao-org          组代中心 — 机构查询
```

### 10.2 配置文件组织

```
mock-business/src/main/resources/
├── mock-endpoints.yml   # 生产端点 — 只放真实需要的端点
└── demo.yml             # 示例 & 文档 — 所有配置能力的展示，供团队参考
```

团队建议：
- `mock-endpoints.yml` 只维护当前联调需要的端点，保持精简
- 每次添加端点时顺便补充 `description`，其他团队成员看路由列表就能理解用途
- 大型响应体（> 200 行）使用 `responseBodyFile: classpath:responses/xxx.json` 外置

### 10.3 校验配置建议

```yaml
# 好的做法：错误信息明确，区分必填缺失和格式错误
validation:
  requiredParams: [certseq, usernm]
  paramRules:
    - name: certseq
      pattern: "^[A-Z0-9]{6,20}$"
      errorMessage: "certseq 格式错误"
  errorStatus: 400
  errorBody: |
    {"error_no":"1001","error_info":"缺少必填字段 certseq 或 usernm"}
  formatErrorBody: |
    {"error_no":"1002","error_info":"参数格式校验失败，请检查 certseq 是否符合规则"}

# 避免：所有错误返回同一个 errorBody，调用方无法定位问题
```

### 10.4 选对响应类型

| 场景 | 推荐方案 |
|------|----------|
| 固定响应、不依赖请求参数 | `responseBody` 静态文本 |
| 响应中需要回显请求参数 | `responseBody` + `{{param.xxx}}` 模板 |
| 同一接口多种结果（成功/失败） | `conditionalResponse` |
| 大型响应体（> 200 行 JSON） | `responseBodyFile` 外部文件 |
| 需要动态签名/时间戳 | `responseScript` JS 脚本（仅 JDK 8） |
| 不需要动态签名，只需时间戳 | `{{now}}` 内置变量（推荐，不依赖 JDK 版本） |

### 10.5 热加载使用姿势

**手动热加载**（默认）：修改 YAML → 调用 `POST /mock/_admin/reload`

**自动热加载**（推荐团队协作时使用）：

```yaml
# application.yml 或启动参数
mock:
  watch-path: file:./config/mock-endpoints.yml  # 监听外部文件
```

```bash
docker run -p 8080:8080 \
  -e JAVA_OPTS="-Dmock.watch-path=file:/config/mock-endpoints.yml" \
  -v $(pwd)/mock-endpoints.yml:/config/mock-endpoints.yml \
  mock-service:1.0.0
```

修改挂载的文件，服务会在 500ms 内自动热加载，不需要重启或手动 API 调用。

### 10.6 录制/回放协作工作流

```bash
# 1. 测试前：团队 A 录制真实联调场景
curl -X POST http://localhost:8080/mock/_admin/record/start
# ... 执行测试 ...
curl -X POST http://localhost:8080/mock/_admin/record/stop
curl -X POST http://localhost:8080/mock/_admin/recordings/save

# 2. 提交录制文件
git add recordings/recordings.json
git commit -m "feat: 录制证通认证接口场景"
git push

# 3. 团队 B 复现相同场景
git pull
curl -X POST http://localhost:8080/mock/_admin/recordings/load
curl -X POST http://localhost:8080/mock/_admin/replay/start
# 现在所有请求都按录制的响应回放，结果完全确定
```

### 10.7 模拟故障场景

```yaml
# 模拟接口超时（配合调用方超时参数测试）
- id: slow-3s
  responseDelay: 3000
  responseBody: '{"code":"0"}'

# 模拟随机抖动（测试调用方的重试/熔断逻辑）
- id: flaky-network
  responseDelay: 200
  responseDelayMax: 3000

# 模拟服务内部错误
- id: error-500
  responseStatus: 500
  responseBody: '{"code":"9999","message":"服务内部异常"}'

# 模拟认证失败
- id: auth-fail
  responseStatus: 401
  responseBody: '{"code":"2001","message":"认证失败，请检查签名"}'
```

---

## 11. 管理端点参考

所有管理端点均在 `/mock/_admin/` 前缀下。如配置了 `mock.admin.api-key`，需在请求头带 `X-API-Key: <key>`。

### HTTP Mock 管理

| 端点 | 方法 | 说明 |
|------|------|------|
| `/mock/_admin/routes` | GET | 返回所有已注册路由（HTTP + WebSocket）的 JSON 清单 |
| `/mock/_admin/reload` | POST | 从 classpath 重新加载配置（fail-fast：失败保持旧配置） |
| `/mock/_admin/postman` | GET | 导出 Postman Collection v2.1（请求体按 requiredParams 自动骨架化） |

### 录制 / 回放

| 端点 | 方法 | 说明 |
|------|------|------|
| `/mock/_admin/record/start` | POST | 开始录制（若正在回放，自动停止回放） |
| `/mock/_admin/record/stop` | POST | 停止录制 |
| `/mock/_admin/replay/start` | POST | 开启回放（若正在录制，自动停止录制） |
| `/mock/_admin/replay/stop` | POST | 关闭回放 |
| `/mock/_admin/recordings` | GET | 列出所有录制记录 |
| `/mock/_admin/recordings` | DELETE | 清空录制记录 |
| `/mock/_admin/recordings/save` | POST | 保存录制到 `recordings/recordings.json` |
| `/mock/_admin/recordings/load` | POST | 从文件加载录制记录 |

### Spring Actuator

| 端点 | 说明 |
|------|------|
| `/actuator/health` | 健康检查 |
| `/actuator/prometheus` | Prometheus 指标（含 `mock_*` 自定义指标） |

### 界面与文档

| 路径 | 说明 |
|------|------|
| `/mock-admin.html` | 管理控制台（查看端点、配置编辑器、录制、回放、热加载） |
| `/swagger-ui.html` | Swagger UI API 文档 |

### 配置编辑（新增）

| 端点 | 方法 | 说明 |
|------|------|------|
| `/mock/_admin/apply` | POST | 接收 YAML 文本，直接替换内存中的配置（无需重启，重启后失效） |

---

## 11.5 管理控制台使用说明

浏览器打开 `http://localhost:8080/mock-admin.html`，共 5 个功能面板：

### 端点列表面板

展示所有已注册的 HTTP 和 WebSocket 端点，支持按 id / path / 描述**实时搜索**过滤。

| 列 | 说明 |
|----|------|
| 详情按钮 | 弹出完整配置详情（路径、ContentType、响应状态码、延迟、校验规则、响应体预览） |
| Method | 请求方法（POST / GET / WS↑↓） |
| Path | 端点路径（pathPattern 优先） |
| ID | 端点唯一标识 |
| 描述 | 业务说明 |
| 状态码 | 正常响应的 HTTP 状态码 |
| 延迟(ms) | 响应延迟范围（固定值或 min~max 随机） |
| 校验 | 是否配置了必填参数校验及参数个数 |

点击任意端点行的「**详情**」按钮，弹出详情卡片，显示该端点的完整配置信息和响应体预览（前 200 字符）。

### 配置编辑器面板

**无需修改 YAML 文件、无需重启**，直接在页面上编辑配置并立即生效。

使用步骤：
1. 点击「**加载当前端点（参考格式）**」，将现有端点的 YAML 结构自动填入编辑器
2. 在编辑器中增删改端点配置
3. 点击「**✔ 应用配置**」，配置立即生效，端点列表自动刷新

> **注意**：此操作只修改内存中的配置，服务重启后恢复磁盘文件内容。如需持久化，还需手动将内容同步到 `mock-endpoints.yml`。

YAML 格式要求与 `mock-endpoints.yml` 完全相同，必须包含 `mock.endpoints` 根键。

### 录制面板

| 按钮 | 说明 |
|------|------|
| 开始录制 | 进入录制模式，之后所有命中 Mock 端点的请求+响应自动记录 |
| 停止录制 | 退出录制模式，显示已录制条数 |
| 刷新列表 | 重新加载录制记录列表 |
| 💾 保存到文件 | 将录制数据写入 `recordings/recordings.json` |
| 📂 从文件加载 | 从 `recordings/recordings.json` 读取录制数据 |
| 清空 | 清除内存中所有录制记录（需二次确认） |

录制列表中，点击每条记录展开查看请求参数和完整响应体。

### 回放面板

| 按钮 | 说明 |
|------|------|
| 开启回放模式 | 按 endpointId + 方法匹配录制记录，命中的请求直接返回录制响应 |
| 关闭回放模式 | 恢复正常 Mock 逻辑 |

**录制与回放互斥**：开启其中一个会自动关闭另一个，页面状态指示灯同步更新。

### 工具面板

| 功能 | 说明 |
|------|------|
| 热加载 | 从 classpath:mock-endpoints.yml 重新加载配置，成功后端点列表自动刷新 |
| 下载 Postman Collection | 导出当前所有端点为 Postman v2.1 格式，直接下载为 JSON 文件 |
| Swagger UI | 跳转到自动生成的 API 文档页面 |
| Health Check | 查看服务健康状态 |
| Prometheus | 查看 Prometheus 格式的监控指标 |

---

## 12. 服务器部署与热更新

### 12.1 核心思路

JAR 包里打包了一份默认的 `mock-endpoints.yml`（classpath 内）。**热更新的关键是把配置文件放在 JAR 外部，并用 `mock.watch-path` 指向它**。之后只需修改那个外部 YAML 文件，服务 500ms 内自动感知并重载——不需要重启服务，不需要调任何接口。

```
服务器目录
/opt/mock-service/
├── mock-boot-1.0.0.jar          ← 只在升级版本时替换
└── config/
    └── mock-endpoints.yml       ← 日常只改这个文件
```

---

### 12.2 第一步：本机打包

```bash
cd mock-service
mvn clean package -DskipTests
# 产物：mock-boot/target/mock-boot-1.0.0.jar
```

---

### 12.3 第二步：上传到服务器

```bash
# 在服务器上建好目录
ssh user@your-server "mkdir -p /opt/mock-service/config"

# 上传 JAR（只在第一次或版本升级时需要）
scp mock-boot/target/mock-boot-1.0.0.jar \
    user@your-server:/opt/mock-service/

# 上传初始配置
scp mock-business/src/main/resources/mock-endpoints.yml \
    user@your-server:/opt/mock-service/config/
```

---

### 12.4 第三步：启动服务

```bash
# SSH 登录服务器
ssh user@your-server

# 启动（关键：--mock.watch-path 指向外部配置文件）
java -Xms128m -Xmx256m \
  -jar /opt/mock-service/mock-boot-1.0.0.jar \
  --mock.watch-path=file:/opt/mock-service/config/mock-endpoints.yml \
  --server.port=8080
```

看到以下日志说明启动并监听成功：
```
文件监听已启动: /opt/mock-service/config/mock-endpoints.yml
Registered 10 mock endpoints:
Netty started on port 8080
```

---

### 12.5 第四步：热更新配置（三种方式）

**方式一：直接在服务器上编辑**

```bash
vim /opt/mock-service/config/mock-endpoints.yml
# 保存后无需任何操作，500ms 内日志自动出现：
# 检测到配置文件变更，开始热加载...
# 热加载完成: 10 个 endpoint, 1 个 websocket
```

**方式二：从本机推送（日常推荐）**

```bash
# 本机改完 YAML 后，直接 scp 覆盖过去
scp mock-business/src/main/resources/mock-endpoints.yml \
    user@your-server:/opt/mock-service/config/mock-endpoints.yml
# 无需其他操作，服务器自动感知并重载
```

**方式三：服务器从 Git 拉取（适合 CI/CD）**

```bash
# 服务器上（或通过 webhook/cron 触发）
cd /opt/mock-service/config
git pull
# 文件内容变化后自动触发热加载
```

> **注意**：如果改了 YAML 语法有误，热加载会失败，**服务继续用旧配置正常运行**，日志打印具体错误原因，不影响线上服务。

---

### 12.6 配成 systemd 服务（后台常驻）

避免 SSH 断开后进程退出，生产环境必备：

```bash
# 在服务器上创建 systemd 服务文件
sudo tee /etc/systemd/system/mock-service.service > /dev/null << 'EOF'
[Unit]
Description=Mock Service
After=network.target

[Service]
User=nobody
WorkingDirectory=/opt/mock-service
ExecStart=/usr/bin/java -Xms128m -Xmx256m \
  -jar /opt/mock-service/mock-boot-1.0.0.jar \
  --mock.watch-path=file:/opt/mock-service/config/mock-endpoints.yml \
  --server.port=8080
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

# 启用并启动
sudo systemctl daemon-reload
sudo systemctl enable mock-service   # 开机自启
sudo systemctl start mock-service

# 常用管理命令
sudo systemctl status mock-service   # 查看状态
sudo systemctl restart mock-service  # 重启（升级 JAR 后执行）
journalctl -u mock-service -f        # 实时查看日志
```

---

### 12.7 版本升级流程

只升级 JAR，配置文件不动：

```bash
# 本机重新打包
mvn clean package -DskipTests

# 上传新 JAR
scp mock-boot/target/mock-boot-1.0.0.jar user@your-server:/opt/mock-service/

# 重启服务（配置文件保持不变，自动使用外部 YAML）
ssh user@your-server "sudo systemctl restart mock-service"
```

---

### 12.8 Docker 部署

#### 构建镜像

```bash
mvn clean package -DskipTests
docker build -t mock-service:1.0.0 .
```

#### 基础运行

```bash
docker run -d -p 8080:8080 --name mock-service mock-service:1.0.0
```

#### 带热加载的运行（推荐）

```bash
# 将宿主机的 mock-endpoints.yml 挂载进容器，修改宿主机文件即自动热加载
docker run -d -p 8080:8080 \
  -e JAVA_OPTS="-Dmock.watch-path=file:/config/mock-endpoints.yml" \
  -v $(pwd)/mock-endpoints.yml:/config/mock-endpoints.yml \
  --name mock-service mock-service:1.0.0
```

#### Docker Compose

```bash
docker-compose up -d
docker-compose logs -f mock-service
```

---

### 12.9 各部署方式对比

| 方式 | 热更新 | 适合场景 |
|------|--------|----------|
| JAR + systemd + 外部文件 | ✅ 改文件自动生效 | **推荐，Linux 服务器** |
| JAR 直接运行 + 外部文件 | ✅ 改文件自动生效 | 临时测试 / 开发调试 |
| Docker + volume 挂载 | ✅ 改宿主机文件自动生效 | 容器化环境 |
| JAR 内置配置（无 watch-path） | ❌ 需重新打包重启 | 不推荐 |

---

### 12.10 常用启动参数速查

```bash
java -jar mock-boot-1.0.0.jar \
  --server.port=9090 \                                              # 修改端口（默认 8080）
  --mock.watch-path=file:/opt/config/mock-endpoints.yml \          # 启用外部文件热加载
  --mock.admin.api-key=your-secret-key \                           # 保护管理端点（可选）
  --logging.file.path=/var/log/mock-service                        # 日志输出目录
```

---

## 13. 扩展开发

### 13.1 添加自定义响应解析策略

只需实现 `BodyResolverStrategy` 接口并标注 `@Component`，无需修改任何现有代码：

```java
@Component
public class DatabaseBodyResolverStrategy implements BodyResolverStrategy {

    @Override
    public boolean supports(EndpointConfig config) {
        // 当 endpoint 配置了 "dbQuery" 扩展字段时生效
        return config.getResponseBody() != null 
            && config.getResponseBody().startsWith("db:");
    }

    @Override
    public Mono<String> resolve(EndpointConfig config, Map<String, String> params) {
        // 从数据库查询并返回结果
        return Mono.fromCallable(() -> queryFromDb(config, params))
                   .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public int order() { return 0; }  // 优先级高于 Script(1)
}
```

### 13.2 替换录制存储后端

实现 `RecordingStore` 接口并注册为 `@Primary @Component`，即可将录制数据存入 Redis 等外部系统：

```java
@Primary
@Component
public class RedisRecordingStore implements RecordingStore {
    // 实现所有接口方法，数据存入 Redis
}
```

### 13.3 替换脚本引擎（JDK 升级时）

实现 `ScriptEngineExecutor` 接口，替换 Nashorn：

```java
@Primary
@Component
public class GraalJsExecutor implements ScriptEngineExecutor {
    @Override
    public String execute(String script, Map<String, String> params) {
        // 使用 GraalVM JS 引擎执行
    }
}
```

### 13.4 新增管理端点

在 `MockRouterConfiguration.buildAdminRoutes()` 方法的 Map 中加一行：

```java
routes.put("POST /mock/_admin/my-action", req -> handler.myAction());
```

---

## 14. 常见问题

**Q：修改 YAML 后，响应没有变化？**

A：需要触发热加载。两种方式：
- 手动：`curl -X POST http://localhost:8080/mock/_admin/reload`
- 自动：配置 `mock.watch-path=file:./config/mock-endpoints.yml`（文件需在 JAR 外部）

注意：`classpath` 内的文件是打包进 JAR 的，修改后需要重新打包或使用外部文件。

---

**Q：`{{param.xxx}}` 没有被替换，原样输出？**

A：检查以下几点：
1. 请求 `contentType` 是否与端点配置匹配（form / json / xml）
2. 参数名大小写是否一致（区分大小写）
3. JSON 请求体是否为合法 JSON（可用 `curl -v` 查看实际发送内容）
4. XML 请求体中的参数是否是叶子元素（无子元素的标签才会被提取）

---

**Q：启动报错 `检测到重复路由` / `endpoints 列表为空`？**

A：
- `重复路由`：同一 method + path 出现了两次，检查 YAML 中是否有 id 不同但路径相同的端点
- `endpoints 列表为空`：YAML 格式错误（缩进错误最常见），或 `spring.config.import` 没有引入 `mock-endpoints.yml`

---

**Q：热加载报错 `热加载失败`，服务仍在用旧配置？**

A：这是设计行为——新配置校验失败时，保持旧配置不变，服务继续正常运行。查看日志中的具体错误，通常是 YAML 语法错误或必填字段缺失。

---

**Q：同时调用了 `record/start` 和 `replay/start`，哪个生效？**

A：两者互斥，后调用的那个会自动停止先前的状态。例如先 `record/start` 再 `replay/start`，录制会自动停止，回放开始生效。日志中会打印 WARN 提示。

---

**Q：`responseScript` 在 JDK 11+ 不能用了怎么办？**

A：有两种替代方案：
- **简单动态值**（时间戳、UUID、序列号）：直接用内置变量 `{{now}}` `{{uuid}}` `{{seq}}`，不依赖任何 JDK 版本
- **复杂业务逻辑**（签名、加密）：实现 `ScriptEngineExecutor` 接口，接入 GraalVM JS 或 Groovy（见第 13.3 节）

---

**Q：`{{seq}}` 序列号从多少开始？重启后会重置吗？**

A：从 1 开始，每次调用自增。**服务重启后归零**（内存计数器，非持久化）。如果需要持久化序列号，可通过 `responseScript` 调用外部数据库。

---

**Q：Postman 导出的请求体是空的？**

A：Postman 导出功能会按端点的 `validation.requiredParams` 自动生成请求体骨架（每个必填参数作为 key，值为空字符串）。如果端点没有配置 `validation`，JSON 请求体会是 `{}`。导入 Postman 后填入实际值即可。

---

**Q：路径变量和请求体参数同名，哪个优先？**

A：**请求体参数优先级更高**，会覆盖同名的路径变量。例如：
```
pathPattern: /api/{userId}/data
请求 body: {"userId": "override"}
→ {{param.userId}} = "override"（body 值覆盖了路径变量）
```

---

**Q：`pathPattern` 路径中带 `/` 的变量能匹配吗？**

A：不能。`{variable}` 只匹配单个路径段（不含 `/`）。如果目标 URL 结构中某段含 `/`，需要拆分为多个变量或使用精确路径。

---

**Q：录制了 100 条，回放时只命中 30 条是怎么回事？**

A：回放按 `endpointId + HTTP 方法` 匹配，找到第一条匹配记录就返回。如果录制时同一个端点有多条记录，当前版本只返回第一条。未命中时，回放模式会继续走正常 Mock 逻辑，返回 `responseBody` 的静态响应。

---

**Q：如何对接 Prometheus + Grafana？**

A：`GET /actuator/prometheus` 返回以下自定义指标：

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `mock_requests_total` | Counter | 请求总数（标签：method/endpoint/status） |
| `mock_request_duration_seconds` | Histogram | 请求延迟分布 |
| `mock_websocket_sessions` | Gauge | 当前 WebSocket 连接数 |
| `mock_recording_active` | Gauge | 录制状态（0/1） |
| `mock_replay_active` | Gauge | 回放状态（0/1） |

在 Prometheus `scrape_configs` 中添加：
```yaml
- job_name: 'mock-service'
  static_configs:
    - targets: ['localhost:8080']
  metrics_path: '/actuator/prometheus'
```

---

## 15. 测试覆盖

```
105 个测试，0 失败，0 错误

MockConfigPropertiesTest ........... 16  配置校验（必填、重复路由、非法 Content-Type）
FormUrlEncodedAdapterTest ..........  7  Form 参数提取
JsonAdapterTest ....................  8  JSON 参数提取（含嵌套 dot-notation）
XmlAdapterTest ....................  10  XML/SOAP 参数提取
MockRequestHandlerTest ............  26  处理器：校验 / 模板 / 延迟 / 条件响应 / 路径变量
MockWebSocketHandlerTest ...........  5  WebSocket 模板引擎
MockRouterConfigurationTest .......  33  端到端集成（HTTP + 管理端点 + 录制/回放 + WebSocket）
```

---

## 附录：性能参考

| 指标 | 参考值 |
|------|--------|
| 启动堆内存 | ~80 MB |
| 稳态堆内存 | ~120 MB（含 Netty Buffer Pool） |
| 无延迟响应延迟 | < 5ms（局域网） |
| 理论吞吐 | 10,000+ req/s（4 核 8 线程，-Xmx256m） |
| 最大录制容量 | 10,000 条（超出后淘汰最早记录） |

---

*Spring Boot 2.7.18 + WebFlux (Netty) + Java 8 — 零业务代码新增 Mock 端点*
