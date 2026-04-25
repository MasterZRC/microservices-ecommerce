# 一、整体架构 · AI Agent 集成（计划归档）

> 对应原 Cursor 附件 `plan.md`（Agent 功能阶段）  
> 执行时间线：2026-04 期间

---

## 一、目标

1. **用户侧**：对话式选品、推荐、加购；**下单仅预览**（`prepare_order_preview`），真实创建订单由用户在前端确认。  
2. **管理侧**：自然语言查询销售、订单状态、取消率等；固定聚合 API + **受限只读 SQL**（`sql_guard` + 服务端二次校验）。  
3. **模型**：通义千问（DashScope），如 `qwen-plus`；需 `DASHSCOPE_API_KEY` 与容器环境注入。  
4. **协议**：SSE 流式、Function Calling 工具循环；修复流式 `tool_calls` 对 `function.arguments` 分片拼接。  
5. **安全**：JWT 区分用户/管理员、透传业务调用、SQL 防写、token 配额与 Prometheus 告警、Grafana 面板。

---

## 二、组件清单

| 层级 | 内容 |
|------|------|
| 新增微服务 | `agent-service`（FastAPI、端口 8011） |
| 用户工具 | 搜索、详情、推荐、热门、画像、购物车、下单预览等 |
| 管理工具 | 调用 `admin-service` 分析接口 + 只读 SQL |
| 后端扩展 | `admin-service` AnalyticsController/Service/Mapper |
| 前端 | 用户 `AgentChat.vue`；管理端 `AIInsights.vue` + mermaid |
| 网关 | `/api/agent/**` 路由、SSE 相关头、JWT 白名单按需 |
| 观测 | Prometheus job、告警规则、Grafana `agent-overview` |
| 文档 | `docs/agent-architecture.md`、`README` Agent 章节 |

---

## 三、实施顺序（摘要）

1. **Batch 1**：agent-service 骨架、用户工具与 prompt、前端气泡、网关与 compose、`.env.example`。  
2. **Batch 2**：管理端工具与 `sql_guard`、Analytics API、管理前端页、监控与告警。  
3. **验收**：推荐 → 加购 → 下单预览 → 前端确认；管理端自然语言 → 表格/图表/解读。  
4. **环境**：`docker compose` 重启后确认 `agent/health` 中 `llm_configured` 与真实对话带工具调用。

---

## 四、约束

- **不修改计划文件本身**（原 Cursor 任务约定）。  
- 不引入与现有栈重复的大依赖；遵守项目 Java/Python 规范。  
- 密钥只走环境变量，不落库、不进镜像。

---

## 五、相关文档

- `docs/agent-architecture.md`  
- `agent-service/README`（若存在）与仓库根 `README.md` Agent 小节
