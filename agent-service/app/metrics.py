"""Prometheus 指标埋点。"""
from prometheus_client import Counter, Histogram, Gauge


# 请求维度
agent_requests_total = Counter(
    "agent_requests_total",
    "AI Agent 请求总数",
    ["channel", "status"],  # channel = user|admin, status = ok|error|denied
)

agent_request_duration_seconds = Histogram(
    "agent_request_duration_seconds",
    "Agent 完整一次对话耗时（秒）",
    ["channel"],
    buckets=(0.5, 1, 2, 3, 5, 8, 15, 30, 60),
)

# Token 消耗
agent_tokens_total = Counter(
    "agent_tokens_total",
    "Agent 累计消耗 token 数",
    ["channel", "kind"],  # kind = prompt|completion
)

# 工具调用
agent_tool_calls_total = Counter(
    "agent_tool_calls_total",
    "Agent 工具调用次数",
    ["channel", "tool", "status"],  # status = ok|error
)

agent_tool_duration_seconds = Histogram(
    "agent_tool_duration_seconds",
    "Agent 单次工具调用耗时（秒）",
    ["tool"],
    buckets=(0.05, 0.1, 0.2, 0.5, 1, 2, 5),
)

# 配额
agent_quota_used = Gauge(
    "agent_quota_used",
    "用户/管理员当日已消耗 Agent 配额",
    ["channel", "principal_id"],
)

agent_quota_denied_total = Counter(
    "agent_quota_denied_total",
    "因配额耗尽被拒绝的请求数",
    ["channel"],
)
