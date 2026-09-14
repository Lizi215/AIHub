# 后端工程规范

## 1. 模块与依赖边界

```text
backend/
├── pom.xml
├── aihub-gateway/
├── aihub-api/          # 服务间契约：Feign 接口 + 服务间 DTO
├── aihub-common/       # 公共能力
├── aihub-user/
├── aihub-asset/
├── aihub-search/
├── aihub-file/
├── aihub-ai/
└── aihub-community/
```

| 模块 | 放什么 | 禁止放什么 |
|---|---|---|
| `aihub-common` | 统一响应体、统一异常、错误码枚举、常量、通用工具、全局配置（Jackson / 时间 / 分页） | 任何业务逻辑、任何业务实体 |
| `aihub-api` | 各服务的 Feign 接口与服务间请求 / 响应 DTO | 实体类、Mapper、业务实现 |

**服务之间只能依赖 `aihub-common` 与 `aihub-api`，禁止直接依赖彼此的实现模块**，否则必然形成循环依赖。

`aihub-common` **禁止成为业务代码垃圾桶**。

## 2. 服务内目录结构

```text
controller/
service/
service/impl/
mapper/
entity/
dto/
vo/
enums/
config/
exception/
handler/
```

AI Service 额外允许：

```text
agent/
├── orchestrator/
├── tool/
├── plan/
└── context/
```

## 3. 跨服务调用（OpenFeign）

使用 OpenFeign（`@HttpExchange` + `RestClient` 是官方替代方案，本项目不采用）。

| 项 | 约定 |
|---|---|
| 超时 | 必须显式配置 connect / read 超时，**禁止使用默认值**，具体值走 Apollo 配置 |
| 重试 | **默认关闭**。仅幂等接口（GET）允许重试；写操作重试会造成重复写入 |
| 熔断降级 | 使用 **Sentinel**（`spring-cloud-starter-alibaba-sentinel`）+ Sentinel Dashboard。**规则必须持久化到 Nacos**，禁止依赖 Dashboard 的内存规则（重启即丢） |
| 上下文透传 | 必须透传 `traceId` 与当前 `userId`，统一在 Feign RequestInterceptor 中实现 |
| 错误转换 | 下游非 2xx 必须转换为本服务的业务异常，**禁止把下游错误码直接透出给前端** |
| 接口定义 | Feign 接口统一放在 `aihub-api` 模块 |

## 4. MyBatis / MyBatis-Plus

MyBatis-Plus 建立在 MyBatis 之上（`mybatis-plus-spring-boot3-starter` 已包含 MyBatis），**不是两套 ORM 并存**，不要引入两套依赖或写两套配置。

分工边界：

| 场景 | 用什么 |
|---|---|
| 单表 CRUD、主键 / 单条件查询、批量插入 | MyBatis-Plus BaseMapper / IService |
| 中等复杂度单表条件查询 | LambdaQueryWrapper |
| 多表 JOIN、聚合、子查询、复杂动态 SQL | Mapper XML |

硬性约束：

- 复杂 SQL 一律写 Mapper XML，统一放在 `src/main/resources/mapper/**/*.xml`，并在配置中**显式指定** `mybatis-plus.mapper-locations`（漏配会导致 XML 静默失效）。
- **禁止把长 SQL 写进 `@Select` 注解。**
- **禁止用 Wrapper 硬拼多表 JOIN**（Wrapper 不支持 JOIN），必须落到 XML；不得退化成多次单表查询造成 N+1。
- **禁止使用 `apply()` / `last()` 拼接用户输入**；XML 中一律用 `#{}`，**禁止用 `${}` 接收外部参数**。
- Mapper 层禁止拼接 SQL 字符串。
- 统一使用 `LambdaQueryWrapper`（避免字段名硬编码），不使用 `QueryWrapper`。
- 分页统一走 `PaginationInnerInterceptor`，**禁止手写 LIMIT**。
- **不引入** `mybatis-plus-join`（MPJ）等第三方 JOIN 扩展。

## 5. Hutool

允许并推荐使用 Hutool 5.8.x：

- StrUtil / CollUtil / ObjectUtil / BeanUtil / DateUtil / IdUtil
- 常见 IO / 集合 / 字符串工具
- BCrypt（`cn.hutool.crypto.digest.BCrypt`）用于密码哈希

**不要为了使用 Hutool 而替代 Spring、MyBatis-Plus 等专业框架已有能力。**

版本统一由 Maven 父工程管理。

## 6. 文件结构

```text
AIHub/
├── docs/
├── backend/
├── frontend/
├── deploy/
├── sql/
├── .env.example
├── .gitignore
└── README.md
```
