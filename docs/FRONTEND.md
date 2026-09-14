# 前端约定与 README 要求

## 1. 前端结构与选型

采用 Vue 3 的常规工程结构，不额外发明约定。

```text
frontend/
├── src/
│   ├── api/            # 接口封装，按业务模块分文件
│   ├── assets/
│   ├── components/     # 通用组件
│   ├── composables/    # 组合式函数
│   ├── layouts/
│   ├── router/         # 路由与守卫（登录态、角色）
│   ├── stores/         # Pinia
│   ├── styles/
│   ├── utils/          # 请求实例、格式化等
│   ├── views/          # 页面
│   ├── App.vue
│   └── main.js
├── public/
├── index.html
├── vite.config.js
└── .env.development / .env.production
```

技术选型为 Vite + JavaScript + Vue Router + Pinia + Axios + Element Plus（按需引入）。

> 不使用 TypeScript。前端在本项目中是配套能力，类型系统不产生功能收益，第一版以降低开发成本为优先。Vite 支持 `.js` / `.ts` 混用，未来若要迁移到 TS，可逐个文件改后缀并补类型，不需要重构。禁止未经要求擅自引入 `tsconfig` 或把文件改成 `.ts`。

## 2. 硬性约束

- 请求统一走 `utils/request.js` 的 Axios 实例，禁止在组件里直接调用 axios。拦截器负责：注入 token、解包统一响应体（`code` / `message` / `data`）、认证失效时续期或跳登录、统一错误提示。
- token 自动续期（由上面 `utils/request.js` 的拦截器实现，后端侧契约见 `docs/DATA.md` §3）：
  - 主动：access token 剩余不足 2 分钟时，发请求前先续期。到期时刻取自登录响应的
    `expiresIn`，刷新页面后回退到解析 token 自身的 `exp`。该值只用于调度，不参与鉴权判断。
  - 被动兜底：收到 `10001` / `10007` 时续期并原样重放原请求，只重放一次。
  - 必须单飞：`/auth/refresh` 的 refresh token 是一次性轮换的，并发续期只会有一个成功，
    因此同一时刻只发一次续期请求，其余请求复用同一个 Promise。
  - 续期彻底失败才清登录态并回登录页。
- 样式注意：`ElMessage` / `ElMessageBox` 这类命令式 API 是函数式调用的，模板里不出现对应
  组件，`unplugin` 的按需解析看不到它们，样式必须显式 import（统一放在 `main.js`）。
  漏掉的表现是弹框无样式、背景透明且贴在左上角。
- api 层与后端 `/api/v1` 路径一一对应，一个业务模块一个文件。
- ID 类字段一律按字符串处理。后端已把 `Long` 序列化为 String（见 `docs/API.md` §5），禁止 `parseInt` / `Number()` 转换，否则 19 位雪花 ID 会丢精度。
- 开发环境用 Vite 代理指向 Gateway，避免跨域配置漂移。
- `.env.*` 只放非敏感配置；任何密钥都不得进入前端代码（前端产物是公开的）。
- 业务逻辑不写在组件里；跨页面状态放 Pinia，可复用逻辑放 composables。
- ESLint + Prettier 统一风格。

## 3. README 要求

README 的目标是让没接触过项目的人能看懂并跑起来，按普通工程项目的标准写即可。

必须包含：

1. 项目简介：一段话说清 AIHub 是什么、解决什么问题
2. 技术栈：列表形式，含版本线
3. 架构图：服务划分与中间件（见 `docs/ARCHITECTURE.md`）
4. 快速开始
   - 环境要求（JDK 17 / Maven / Docker / Node）
   - 最低硬件要求（见 `docs/SECURITY.md` §5）
   - 启动中间件（`docker-compose.core.yml` 或 `full`）
   - 启动后端与前端
   - 默认账号（如有）
5. 目录结构说明
6. 模块说明：各服务职责
7. 配置说明：指向 `.env.example` 与 Apollo
8. 文档索引：指向 `docs/`
9. 常见问题：端口冲突、内存不足、镜像拉取失败等

禁止：

- 空章节与 `TODO` 占位
- 与代码不一致的命令或配置
- 罗列尚未实现的功能
