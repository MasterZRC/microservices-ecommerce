"""下游服务 HTTP 调用封装：统一注入 JWT、超时、重试、统一异常。"""
import logging
from typing import Any, Dict, Optional

import httpx

from . import config

log = logging.getLogger(__name__)


def call_downstream(
    method: str,
    url: str,
    *,
    jwt: Optional[str] = None,
    params: Optional[Dict[str, Any]] = None,
    json_body: Any = None,
    timeout: Optional[float] = None,
) -> Dict[str, Any]:
    """
    通用下游调用，返回 dict。失败抛 RuntimeError，由 tool 层 catch 后包成友好结果。
    """
    headers: Dict[str, str] = {"Content-Type": "application/json"}
    if jwt:
        # 若调用方已带 Bearer 前缀则原样转发
        headers["Authorization"] = jwt if jwt.startswith("Bearer ") else f"Bearer {jwt}"

    t = timeout or config.TOOL_HTTP_TIMEOUT_SECONDS
    last_err: Optional[Exception] = None
    for attempt in range(2):  # 最多 1 次重试
        try:
            with httpx.Client(timeout=t) as client:
                resp = client.request(method.upper(), url, params=params, json=json_body, headers=headers)
            if resp.status_code >= 500:
                raise RuntimeError(f"{method} {url} HTTP {resp.status_code}: {resp.text[:200]}")
            try:
                return {"status": resp.status_code, "data": resp.json()}
            except Exception:
                return {"status": resp.status_code, "data": resp.text}
        except httpx.HTTPError as e:
            last_err = e
            log.warning(f"call_downstream attempt {attempt} failed: {e}")
    raise RuntimeError(f"调用下游服务失败 {method} {url}: {last_err}")
