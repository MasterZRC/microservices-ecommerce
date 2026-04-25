"""管理侧 Agent 工具集：销售/订单/曝光/取消率/受限 SQL。

所有工具：
- 透传 admin JWT 调 admin-service
- 受限 SQL 走 sql_guard 校验后再请求 admin-service /api/admin/analytics/sql
- 工具调用结果若包含图表友好的数据，会同时通过 sse_emit 推 'chart' 事件，
  让 admin-frontend 渲染 mermaid 图。
"""
import re
from typing import Any, Callable, Dict, List

from . import config, sql_guard
from .http_client import call_downstream


def build_admin_tools() -> List[Dict[str, Any]]:
    return [
        {
            "type": "function",
            "function": {
                "name": "get_dashboard_overview",
                "description": "获取核心仪表盘指标：用户/商品/订单总数、今日订单数、今日销售额、总销售额、待处理订单数、低库存商品数。",
                "parameters": {"type": "object", "properties": {}},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "get_recent_orders",
                "description": "查最近 N 个订单（含订单号、用户、金额、状态、时间）。",
                "parameters": {
                    "type": "object",
                    "properties": {"limit": {"type": "integer", "default": 10, "minimum": 1, "maximum": 50}},
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "get_order_status_distribution",
                "description": "按订单状态聚合（待支付/已支付/已发货/已完成/已取消）的订单数和金额。",
                "parameters": {
                    "type": "object",
                    "properties": {"days": {"type": "integer", "default": 7, "minimum": 1, "maximum": 365}},
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "get_sales_trend",
                "description": "销售时序：按天聚合订单数与销售额（仅含已支付/已发货/已完成）。",
                "parameters": {
                    "type": "object",
                    "properties": {"days": {"type": "integer", "default": 14, "minimum": 1, "maximum": 90}},
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "get_top_products",
                "description": "商品 Top N 排行。metric=sales 看销量/GMV，exposure 看曝光量，click 看点击量。",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "metric": {"type": "string", "enum": ["sales", "exposure", "click"], "default": "sales"},
                        "days": {"type": "integer", "default": 7, "minimum": 1, "maximum": 365},
                        "limit": {"type": "integer", "default": 10, "minimum": 1, "maximum": 50},
                    },
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "get_cancellation_rate",
                "description": "订单取消率：取消订单数 / 总订单数。返回 totalCount/canceledCount/cancellationRatePercent。",
                "parameters": {
                    "type": "object",
                    "properties": {"days": {"type": "integer", "default": 30, "minimum": 1, "maximum": 365}},
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "get_category_performance",
                "description": "各类目业绩：GMV、销量、商品数。用于看哪个类目卖得好。",
                "parameters": {
                    "type": "object",
                    "properties": {"days": {"type": "integer", "default": 30, "minimum": 1, "maximum": 365}},
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "execute_readonly_sql",
                "description": (
                    "执行受限只读 SQL（仅 SELECT，自动 LIMIT 200，仅允许表："
                    "product, category, order_info, order_item, user, user_behavior, "
                    "product_exposure, seckill_product, seckill_activity）。"
                    "用于回答常规聚合无法满足的复杂分析问题。"
                ),
                "parameters": {
                    "type": "object",
                    "properties": {"sql": {"type": "string", "description": "完整的 SELECT 查询"}},
                    "required": ["sql"],
                },
            },
        },
    ]


def dispatch_admin_tool(name: str, args: Dict[str, Any], *,
                        jwt: str, admin_id: int,
                        sse_emit: Callable[[str, dict], None]) -> Dict[str, Any]:
    handlers = {
        "get_dashboard_overview": _dashboard,
        "get_recent_orders": _recent_orders,
        "get_order_status_distribution": _status_dist,
        "get_sales_trend": _sales_trend,
        "get_top_products": _top_products,
        "get_cancellation_rate": _cancel_rate,
        "get_category_performance": _category_perf,
        "execute_readonly_sql": _readonly_sql,
    }
    h = handlers.get(name)
    if not h:
        return {"ok": False, "error": f"未知工具 {name}"}
    try:
        return h(args, jwt=jwt, admin_id=admin_id, sse_emit=sse_emit)
    except Exception as e:
        return {"ok": False, "error": str(e)}


# ===== Helpers =====

def _admin_get(path: str, jwt: str, params: Dict[str, Any] = None) -> Any:
    res = call_downstream("GET", f"{config.ADMIN_SERVICE_URL}{path}", jwt=jwt, params=params)
    payload = res.get("data") or {}
    if isinstance(payload, dict) and "data" in payload:
        return payload.get("data")
    return payload


def _admin_post(path: str, jwt: str, body: Any) -> Any:
    res = call_downstream("POST", f"{config.ADMIN_SERVICE_URL}{path}", jwt=jwt, json_body=body)
    payload = res.get("data") or {}
    if isinstance(payload, dict) and "data" in payload:
        return payload.get("data")
    return payload


def _emit_chart(sse_emit, title: str, mermaid: str):
    sse_emit("chart", {"title": title, "mermaid": mermaid})


# ===== Tools =====

def _dashboard(args, *, jwt, admin_id, sse_emit):
    data = _admin_get("/api/admin/dashboard/stats", jwt)
    return {"ok": True, "stats": data}


def _recent_orders(args, *, jwt, admin_id, sse_emit):
    limit = max(1, min(int(args.get("limit", 10)), 50))
    data = _admin_get("/api/admin/dashboard/recent-orders", jwt, params={"limit": limit})
    if isinstance(data, list):
        # 裁剪字段
        orders = [
            {
                "id": o.get("id"),
                "orderNo": o.get("orderNo"),
                "userId": o.get("userId"),
                "userName": o.get("userName"),
                "totalAmount": o.get("totalAmount"),
                "status": o.get("status"),
                "statusName": o.get("statusName"),
                "createTime": o.get("createTime"),
            }
            for o in data
        ]
        return {"ok": True, "count": len(orders), "orders": orders}
    return {"ok": True, "data": data}


def _status_dist(args, *, jwt, admin_id, sse_emit):
    days = int(args.get("days", 7))
    rows = _admin_get("/api/admin/analytics/order-status-distribution", jwt, params={"days": days})
    rows = rows or []
    # 出一个 mermaid pie 图
    if rows:
        lines = ["pie title 订单状态分布"]
        for r in rows:
            name = str(r.get("statusName", r.get("status")))
            cnt = int(r.get("count", 0))
            # 引号中转义特殊字符
            lines.append(f'    "{name}" : {cnt}')
        _emit_chart(sse_emit, f"订单状态分布（最近 {days} 天）", "\n".join(lines))
    return {"ok": True, "days": days, "rows": rows}


def _sales_trend(args, *, jwt, admin_id, sse_emit):
    days = int(args.get("days", 14))
    rows = _admin_get("/api/admin/analytics/sales-trend", jwt, params={"days": days})
    rows = rows or []
    if rows:
        days_x = ', '.join(f'"{r.get("day")}"' for r in rows)
        sales_y = ', '.join(str(r.get("sales", 0)) for r in rows)
        mermaid = (
            "xychart-beta\n"
            f'    title "销售额时序（最近 {days} 天）"\n'
            f'    x-axis [{days_x}]\n'
            '    y-axis "销售额(元)"\n'
            f'    bar [{sales_y}]\n'
        )
        _emit_chart(sse_emit, f"销售时序（最近 {days} 天）", mermaid)
    return {"ok": True, "days": days, "rows": rows}


def _top_products(args, *, jwt, admin_id, sse_emit):
    metric = (args.get("metric") or "sales").lower()
    days = int(args.get("days", 7))
    limit = max(1, min(int(args.get("limit", 10)), 50))
    rows = _admin_get(
        "/api/admin/analytics/top-products", jwt,
        params={"metric": metric, "days": days, "limit": limit},
    )
    rows = rows or []
    if rows:
        # 取销量/曝光/点击的数值字段
        value_key = {
            "sales": "soldQuantity",
            "exposure": "exposureCount",
            "click": "clickCount",
        }.get(metric, "soldQuantity")
        names = ', '.join(f'"{(r.get("productName") or "?")[:18]}"' for r in rows)
        values = ', '.join(str(r.get(value_key, 0)) for r in rows)
        mermaid = (
            "xychart-beta\n"
            f'    title "商品 Top {limit} ({metric})"\n'
            f'    x-axis [{names}]\n'
            '    y-axis "数量"\n'
            f'    bar [{values}]\n'
        )
        _emit_chart(sse_emit, f"Top {limit} 商品 - {metric}", mermaid)
    return {"ok": True, "metric": metric, "days": days, "rows": rows}


def _cancel_rate(args, *, jwt, admin_id, sse_emit):
    days = int(args.get("days", 30))
    data = _admin_get("/api/admin/analytics/cancellation-rate", jwt, params={"days": days})
    return {"ok": True, "data": data}


def _category_perf(args, *, jwt, admin_id, sse_emit):
    days = int(args.get("days", 30))
    rows = _admin_get("/api/admin/analytics/category-performance", jwt, params={"days": days})
    rows = rows or []
    if rows:
        lines = ["pie title 类目 GMV 占比"]
        for r in rows[:10]:
            name = str(r.get("categoryName") or "未知")
            gmv = float(r.get("gmv") or 0)
            if gmv > 0:
                lines.append(f'    "{name}" : {int(gmv)}')
        if len(lines) > 1:
            _emit_chart(sse_emit, f"类目业绩（最近 {days} 天）", "\n".join(lines))
    return {"ok": True, "days": days, "rows": rows}


def _readonly_sql(args, *, jwt, admin_id, sse_emit):
    sql = args.get("sql", "")
    ok, normalized, reason = sql_guard.validate_and_normalize(sql)
    if not ok:
        return {"ok": False, "error": normalized, "reason": reason}
    # 调 admin-service 真正执行（admin-service 内还会做一次正则二重防御）
    try:
        rows = _admin_post("/api/admin/analytics/sql", jwt, {"sql": normalized})
    except Exception as e:
        return {"ok": False, "error": f"SQL 执行失败：{e}"}
    if not isinstance(rows, list):
        return {"ok": False, "error": "结果格式异常", "raw": rows}
    return {"ok": True, "sql": normalized, "row_count": len(rows), "rows": rows[:50]}
