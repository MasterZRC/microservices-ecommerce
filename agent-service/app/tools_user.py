"""用户侧 Agent 工具集：8 个工具 + Function Calling schema + dispatcher。

设计要点：
- 工具调用方都是 LLM；schema 必须严谨，参数必填字段不能省。
- 所有工具都通过 http_client 透传当前用户的 JWT，做到下游服务严格鉴权（不会越权）。
- 「下单」工具叫 prepare_order_preview：仅返回订单预览 JSON 与 action_card，前端展示后由用户点击「确认下单」按钮才真正调用订单创建接口。
"""
from typing import Any, Callable, Dict, List, Optional

from . import config
from .http_client import call_downstream


# ===== Function Calling tool 定义（OpenAI 兼容 schema）=====

def build_user_tools() -> List[Dict[str, Any]]:
    return [
        {
            "type": "function",
            "function": {
                "name": "search_products",
                "description": "按关键词或类目搜索商品，返回分页商品列表。适用于用户明确说想找某个东西时。",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "keyword": {"type": "string", "description": "搜索关键词，模糊匹配商品名/描述。可为空"},
                        "category_id": {"type": "integer", "description": "可选的类目 ID 限定"},
                        "limit": {"type": "integer", "description": "返回数量，默认 10", "default": 10},
                    },
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "get_product_detail",
                "description": "查询单个商品的详细信息（价格、库存、描述、分类、图片）。",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "product_id": {"type": "integer", "description": "商品 ID；如果不确定不要猜，改用 product_name"},
                        "product_name": {"type": "string", "description": "商品名称；当用户按名称确认商品时优先传这个字段"},
                    },
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "get_personalized_recommendations",
                "description": "基于当前用户的画像与行为，调用推荐系统获取个性化商品列表（含推荐理由）。",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "limit": {"type": "integer", "default": 10},
                    },
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "get_popular_products",
                "description": "获取全平台热门商品列表，适合冷启动用户或不知道想要什么时使用。",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "limit": {"type": "integer", "default": 10},
                    },
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "get_user_profile",
                "description": "查询当前用户的画像（类目偏好、品牌偏好、活跃度、价格区间）。用于个性化解释推荐理由。",
                "parameters": {"type": "object", "properties": {}},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "add_to_cart",
                "description": "把商品加入当前用户的购物车（直接生效）。",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "product_id": {"type": "integer", "description": "商品 ID；如果不确定不要猜，改用 product_name"},
                        "product_name": {"type": "string", "description": "商品名称；当用户按名称确认商品时优先传这个字段"},
                        "quantity": {"type": "integer", "default": 1, "minimum": 1, "maximum": 99},
                    },
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "get_cart",
                "description": "查询当前用户购物车中的商品列表与总数量。",
                "parameters": {"type": "object", "properties": {}},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "prepare_order_preview",
                "description": (
                    "为用户准备订单预览（不下单）。返回所选商品、单价、总额、地址，"
                    "前端会渲染成「确认下单」卡片由用户点击按钮才真正下单。"
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "items": {
                            "type": "array",
                            "description": "商品列表",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "product_id": {"type": "integer", "description": "商品 ID；如果不确定不要猜，改用 product_name"},
                                    "product_name": {"type": "string", "description": "用户确认购买的商品名称，优先使用这个字段校验商品"},
                                    "quantity": {"type": "integer", "minimum": 1, "maximum": 99},
                                },
                                "required": ["quantity"],
                            },
                        },
                        "receiver_name": {"type": "string", "description": "收货人姓名"},
                        "receiver_phone": {"type": "string", "description": "收货人手机号"},
                        "receiver_address": {"type": "string", "description": "收货地址"},
                    },
                    "required": ["items", "receiver_name", "receiver_phone", "receiver_address"],
                },
            },
        },
    ]


# ===== Dispatcher =====

def dispatch_user_tool(name: str, args: Dict[str, Any], *,
                       jwt: str, user_id: int,
                       sse_emit: Callable[[str, dict], None]) -> Dict[str, Any]:
    """
    根据工具名分发执行。返回的 dict 会被序列化成 JSON 喂回给 LLM。

    sse_emit 让某些工具可以额外推送 action_card 给前端（比如 prepare_order_preview）。
    """
    handler_map = {
        "search_products": _search_products,
        "get_product_detail": _get_product_detail,
        "get_personalized_recommendations": _get_personalized_recommendations,
        "get_popular_products": _get_popular_products,
        "get_user_profile": _get_user_profile,
        "add_to_cart": _add_to_cart,
        "get_cart": _get_cart,
        "prepare_order_preview": _prepare_order_preview,
    }
    handler = handler_map.get(name)
    if not handler:
        return {"ok": False, "error": f"未知工具: {name}"}
    try:
        return handler(args, jwt=jwt, user_id=user_id, sse_emit=sse_emit)
    except Exception as e:
        return {"ok": False, "error": str(e)}


# ===== 工具实现 =====

def _trim_product(p: Dict[str, Any]) -> Dict[str, Any]:
    """裁剪 product 字段，只保留对 LLM 决策有用的部分，节省 token。"""
    return {
        "id": p.get("id"),
        "name": p.get("name"),
        "price": p.get("price"),
        "stock": p.get("stock"),
        "category_id": p.get("categoryId"),
        "category_name": p.get("categoryName"),
        "brand": p.get("brand"),
        "image_url": p.get("imageUrl"),
        "description": (p.get("description") or "")[:80],
    }


def _normalize_name(name: str) -> str:
    return "".join(str(name or "").lower().split())


def _search_product_by_name(product_name: str, *, jwt: str) -> Optional[Dict[str, Any]]:
    name = (product_name or "").strip()
    if not name:
        return None
    res = call_downstream("GET", f"{config.PRODUCT_SERVICE_URL}/api/product/list",
                          jwt=jwt, params={"page": 1, "pageSize": 10, "keyword": name})
    products = (res.get("data") or {}).get("products", [])
    if not products:
        return None

    normalized_query = _normalize_name(name)
    exact = [p for p in products if _normalize_name(p.get("name")) == normalized_query]
    if exact:
        return exact[0]

    contains = [
        p for p in products
        if normalized_query in _normalize_name(p.get("name")) or _normalize_name(p.get("name")) in normalized_query
    ]
    return contains[0] if contains else products[0]


def _resolve_product(args: Dict[str, Any], *, jwt: str) -> Dict[str, Any]:
    """
    根据商品名或 ID 解析真实商品。商品名优先，避免模型在多轮对话中猜错 product_id。
    """
    product_name = (args.get("product_name") or "").strip()
    if product_name:
        p = _search_product_by_name(product_name, jwt=jwt)
        if not p:
            return {"ok": False, "error": f"没有找到商品：{product_name}"}
        requested_pid = args.get("product_id")
        if requested_pid and int(requested_pid) != int(p.get("id")):
            return {
                "ok": True,
                "product": p,
                "warning": (
                    f"模型传入的 product_id={requested_pid} 与商品名「{product_name}」不一致，"
                    f"已按商品名解析为 product_id={p.get('id')}。"
                ),
            }
        return {"ok": True, "product": p}

    if "product_id" not in args or args.get("product_id") in (None, ""):
        return {"ok": False, "error": "缺少商品名称或商品 ID，无法确认要操作的商品"}

    pid = int(args["product_id"])
    res = call_downstream("GET", f"{config.PRODUCT_SERVICE_URL}/api/product/{pid}", jwt=jwt)
    p = res.get("data") or {}
    if not p.get("id"):
        return {"ok": False, "error": f"商品 {pid} 不存在"}
    return {"ok": True, "product": p}


def _search_products(args, *, jwt, user_id, sse_emit) -> Dict[str, Any]:
    keyword = (args.get("keyword") or "").strip()
    category_id = args.get("category_id")
    limit = max(1, min(int(args.get("limit", 10)), 30))
    params = {"page": 1, "pageSize": limit}
    if keyword:
        params["keyword"] = keyword
    if category_id:
        params["categoryId"] = category_id
    res = call_downstream("GET", f"{config.PRODUCT_SERVICE_URL}/api/product/list",
                          jwt=jwt, params=params)
    products = (res.get("data") or {}).get("products", [])[:limit]
    return {
        "ok": True,
        "count": len(products),
        "products": [_trim_product(p) for p in products],
    }


def _get_product_detail(args, *, jwt, user_id, sse_emit) -> Dict[str, Any]:
    resolved = _resolve_product(args, jwt=jwt)
    if not resolved.get("ok"):
        return resolved
    data = {"ok": True, "product": _trim_product(resolved["product"])}
    if resolved.get("warning"):
        data["warning"] = resolved["warning"]
    return data


def _get_personalized_recommendations(args, *, jwt, user_id, sse_emit) -> Dict[str, Any]:
    limit = max(1, min(int(args.get("limit", 10)), 20))
    res = call_downstream(
        "GET", f"{config.RECOMMEND_SERVICE_URL}/api/recommendation/personal/products",
        jwt=jwt, params={"userId": user_id, "limit": limit},
    )
    products = (res.get("data") or {}).get("products", [])[:limit]
    return {"ok": True, "count": len(products),
            "products": [_trim_product(p) for p in products]}


def _get_popular_products(args, *, jwt, user_id, sse_emit) -> Dict[str, Any]:
    limit = max(1, min(int(args.get("limit", 10)), 20))
    res = call_downstream(
        "GET", f"{config.RECOMMEND_SERVICE_URL}/api/recommendation/popular/products",
        jwt=jwt, params={"limit": limit},
    )
    products = (res.get("data") or {}).get("products", [])[:limit]
    return {"ok": True, "count": len(products),
            "products": [_trim_product(p) for p in products]}


def _get_user_profile(args, *, jwt, user_id, sse_emit) -> Dict[str, Any]:
    res = call_downstream(
        "GET", f"{config.RECOMMEND_SERVICE_URL}/api/recommendation/profile/{user_id}",
        jwt=jwt,
    )
    status = res.get("status", 0)
    if status and status >= 400:
        return {"ok": False, "error": f"查询用户画像失败 (HTTP {status})"}
    profile = res.get("data") or {}
    if not profile or "message" in profile:
        return {"ok": True, "profile": None, "note": "暂无画像数据（可能是新用户）"}
    return {"ok": True, "profile": profile}


def _add_to_cart(args, *, jwt, user_id, sse_emit) -> Dict[str, Any]:
    qty = max(1, min(int(args.get("quantity", 1)), 99))
    resolved = _resolve_product(args, jwt=jwt)
    if not resolved.get("ok"):
        return resolved
    p = resolved["product"]
    pid = int(p.get("id"))
    body = {
        "userId": user_id,
        "productId": pid,
        "productName": p.get("name", ""),
        "productImage": p.get("imageUrl", ""),
        "quantity": qty,
    }
    res = call_downstream("POST", f"{config.ORDER_SERVICE_URL}/api/order/cart/add",
                          jwt=jwt, json_body=body)
    msg = (res.get("data") or {}).get("message", "添加成功")
    data = {"ok": True, "message": msg, "product": _trim_product(p), "quantity": qty}
    if resolved.get("warning"):
        data["warning"] = resolved["warning"]
    return data


def _get_cart(args, *, jwt, user_id, sse_emit) -> Dict[str, Any]:
    res = call_downstream(
        "GET", f"{config.ORDER_SERVICE_URL}/api/order/cart/list",
        jwt=jwt, params={"userId": user_id},
    )
    items = res.get("data") or []
    if not isinstance(items, list):
        items = []
    return {
        "ok": True,
        "count": len(items),
        "items": [
            {
                "product_id": it.get("productId"),
                "product_name": it.get("productName"),
                "quantity": it.get("quantity"),
            }
            for it in items
        ],
    }


def _prepare_order_preview(args, *, jwt, user_id, sse_emit) -> Dict[str, Any]:
    """
    生成订单预览（不下单）。返回结构：
      ok, total_amount, items[], receiver_name/phone/address, action_card
    并通过 sse_emit 推一个 'action_card' 事件给前端，前端识别 type=order_preview 后
    显示「确认下单」按钮，按钮调既有 POST /api/order/create。
    """
    items = args.get("items") or []
    if not isinstance(items, list) or not items:
        return {"ok": False, "error": "items 不能为空"}

    # 抓所有商品详情，计算总价
    enriched_items: List[Dict[str, Any]] = []
    total_amount = 0.0
    for it in items:
        qty = max(1, min(int(it.get("quantity", 1)), 99))
        resolved = _resolve_product(it, jwt=jwt)
        if not resolved.get("ok"):
            return resolved
        p = resolved["product"]
        pid = int(p.get("id"))
        price = float(p.get("price") or 0)
        subtotal = round(price * qty, 2)
        total_amount += subtotal
        enriched_items.append({
            "product_id": pid,
            "product_name": p.get("name"),
            "product_image": p.get("imageUrl"),
            "price": price,
            "quantity": qty,
            "subtotal": subtotal,
            "stock": p.get("stock"),
        })

    receiver_name = (args.get("receiver_name") or "").strip()
    receiver_phone = (args.get("receiver_phone") or "").strip()
    receiver_address = (args.get("receiver_address") or "").strip()
    if not receiver_name or not receiver_phone or not receiver_address:
        return {"ok": False, "error": "收货人姓名/电话/地址都不能为空"}

    preview = {
        "user_id": user_id,
        "items": enriched_items,
        "total_amount": round(total_amount, 2),
        "receiver_name": receiver_name,
        "receiver_phone": receiver_phone,
        "receiver_address": receiver_address,
    }

    # action_card 让前端弹「确认下单」按钮；path 使用前端 API base 下的相对路径，避免拼成 /api/api/...
    action = {
        "type": "order_preview",
        "title": "请确认订单后下单",
        "preview": preview,
        "submit": {
            "method": "POST",
            "path": "/order/create",
            "body": {
                "userId": user_id,
                "receiverName": receiver_name,
                "receiverPhone": receiver_phone,
                "receiverAddress": receiver_address,
                "items": [
                    {"productId": it["product_id"], "quantity": it["quantity"]}
                    for it in enriched_items
                ],
            },
        },
    }
    sse_emit("action_card", action)

    return {
        "ok": True,
        "preview": preview,
        "note": "已生成订单预览，等待用户在前端点击「确认下单」按钮才会真正创建订单。",
    }
