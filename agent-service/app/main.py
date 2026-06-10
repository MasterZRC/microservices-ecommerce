"""agent-service FastAPI 主入口。"""
import logging
import threading
import time
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, PlainTextResponse
from prometheus_client import generate_latest, CONTENT_TYPE_LATEST
from pydantic import BaseModel, Field

from . import auth, config, llm, metrics, prompts
from .sse import SseChannel, encode_event
from .tools_user import build_user_tools, dispatch_user_tool
from .tools_admin import build_admin_tools, dispatch_admin_tool

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s")
log = logging.getLogger(__name__)

app = FastAPI(title="agent-service", version="1.0.0",
              description="双端 AI Agent 微服务")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ===== 请求模型 =====
class ChatMessage(BaseModel):
    role: str = Field(..., description="user|assistant|system|tool")
    content: str = ""
    name: Optional[str] = None
    tool_call_id: Optional[str] = None
    tool_calls: Optional[List[Dict[str, Any]]] = None


class ChatRequest(BaseModel):
    messages: List[ChatMessage] = Field(default_factory=list)
    user_message: Optional[str] = None  # 便捷字段：仅本次的新消息


# ===== 健康检查 / 元信息 =====
@app.get("/agent/health")
async def health() -> Dict[str, Any]:
    return {
        "status": "healthy",
        "provider": config.LLM_PROVIDER,
        "model": config.selected_model(),
        "llm_configured": config.is_llm_configured(),
        "version": "1.0.0",
    }


@app.get("/agent/metrics")
async def metrics_endpoint() -> PlainTextResponse:
    return PlainTextResponse(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.get("/agent/greeting")
async def greeting() -> Dict[str, str]:
    return {"user": prompts.USER_GREETING, "admin": prompts.ADMIN_GREETING}


# ===== 公共流式入口 =====
def _build_initial_messages(system_prompt: str, req: ChatRequest) -> List[Dict[str, Any]]:
    msgs: List[Dict[str, Any]] = [{"role": "system", "content": system_prompt}]
    for m in req.messages:
        d: Dict[str, Any] = {"role": m.role, "content": m.content or ""}
        if m.name:
            d["name"] = m.name
        if m.tool_call_id:
            d["tool_call_id"] = m.tool_call_id
        if m.tool_calls:
            d["tool_calls"] = m.tool_calls
        msgs.append(d)
    if req.user_message:
        msgs.append({"role": "user", "content": req.user_message})
    return msgs


def _resolve_jwt_and_principal(authorization: Optional[str]) -> tuple:
    """返回 (jwt_str, channel, principal_id)。失败抛 HTTPException。"""
    if not authorization:
        raise HTTPException(status_code=401, detail="缺少 Authorization Header")
    try:
        claims = auth.parse_jwt(authorization)
        channel, pid, _role = auth.extract_principal(claims)
    except ValueError as e:
        raise HTTPException(status_code=401, detail=str(e))
    return authorization, channel, pid


# ===== 用户 Agent =====
@app.post("/agent/user/chat")
async def user_chat(req: ChatRequest, request: Request,
                    authorization: Optional[str] = Header(None)):
    jwt_str, channel, principal_id = _resolve_jwt_and_principal(authorization)
    if channel != "user":
        raise HTTPException(status_code=403, detail="该入口仅允许普通用户调用")

    allowed, used, limit = auth.quota_check_and_incr("user", principal_id)
    metrics.agent_quota_used.labels(channel="user", principal_id=str(principal_id)).set(used)
    if not allowed:
        metrics.agent_quota_denied_total.labels(channel="user").inc()
        metrics.agent_requests_total.labels(channel="user", status="denied").inc()
        raise HTTPException(status_code=429,
                            detail=f"每日 Agent 配额已用尽 ({used}/{limit})，明天再来吧。")

    initial_msgs = _build_initial_messages(prompts.USER_SYSTEM_PROMPT, req)
    tools_spec = build_user_tools()
    sse = SseChannel()

    def background():
        t0 = time.time()
        status = "ok"
        try:
            llm.run_tool_loop(
                channel="user",
                messages=initial_msgs,
                tools_spec=tools_spec,
                tool_dispatcher=lambda name, args: dispatch_user_tool(name, args,
                                                                     jwt=jwt_str,
                                                                     user_id=principal_id,
                                                                     sse_emit=sse.emit),
                sse_emit=sse.emit,
            )
        except Exception as e:
            status = "error"
            log.exception("user agent error")
            sse.emit("error", {"message": str(e)})
        finally:
            duration = time.time() - t0
            metrics.agent_request_duration_seconds.labels(channel="user").observe(duration)
            metrics.agent_requests_total.labels(channel="user", status=status).inc()
            sse.close()

    threading.Thread(target=background, daemon=True).start()

    headers = {
        "Cache-Control": "no-cache",
        "X-Accel-Buffering": "no",  # 阻止 nginx/网关缓冲
    }
    return StreamingResponse(sse.stream(), media_type="text/event-stream", headers=headers)


# 兼容入口：让前端可以 GET 形式调用（例如某些 SSE polyfill）
@app.get("/agent/user/chat")
async def user_chat_get():
    return PlainTextResponse("Use POST", status_code=405)


# ===== 管理 Agent =====
@app.post("/agent/admin/chat")
async def admin_chat(req: ChatRequest, request: Request,
                     authorization: Optional[str] = Header(None)):
    jwt_str, channel, principal_id = _resolve_jwt_and_principal(authorization)
    if channel != "admin":
        raise HTTPException(status_code=403, detail="该入口仅允许管理员调用")

    allowed, used, limit = auth.quota_check_and_incr("admin", principal_id)
    metrics.agent_quota_used.labels(channel="admin", principal_id=str(principal_id)).set(used)
    if not allowed:
        metrics.agent_quota_denied_total.labels(channel="admin").inc()
        metrics.agent_requests_total.labels(channel="admin", status="denied").inc()
        raise HTTPException(status_code=429,
                            detail=f"每日 Agent 配额已用尽 ({used}/{limit})，明天再来吧。")

    initial_msgs = _build_initial_messages(prompts.ADMIN_SYSTEM_PROMPT, req)
    tools_spec = build_admin_tools()
    sse = SseChannel()

    def background():
        t0 = time.time()
        status = "ok"
        try:
            llm.run_tool_loop(
                channel="admin",
                messages=initial_msgs,
                tools_spec=tools_spec,
                tool_dispatcher=lambda name, args: dispatch_admin_tool(name, args,
                                                                      jwt=jwt_str,
                                                                      admin_id=principal_id,
                                                                      sse_emit=sse.emit),
                sse_emit=sse.emit,
            )
        except Exception as e:
            status = "error"
            log.exception("admin agent error")
            sse.emit("error", {"message": str(e)})
        finally:
            duration = time.time() - t0
            metrics.agent_request_duration_seconds.labels(channel="admin").observe(duration)
            metrics.agent_requests_total.labels(channel="admin", status=status).inc()
            sse.close()

    threading.Thread(target=background, daemon=True).start()

    headers = {
        "Cache-Control": "no-cache",
        "X-Accel-Buffering": "no",
    }
    return StreamingResponse(sse.stream(), media_type="text/event-stream", headers=headers)
