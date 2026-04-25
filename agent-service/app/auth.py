"""JWT 解析与简单配额。"""
import datetime as dt
import logging
from typing import Optional, Tuple

import jwt
import redis

from . import config

log = logging.getLogger(__name__)

_redis: Optional[redis.Redis] = None


def get_redis() -> redis.Redis:
    global _redis
    if _redis is None:
        _redis = redis.Redis(host=config.REDIS_HOST, port=config.REDIS_PORT,
                             decode_responses=True, socket_timeout=2)
    return _redis


def parse_jwt(token: str) -> dict:
    """解析 JWT，失败抛 ValueError。约定与 user-service / admin-service 一致：HS256。"""
    if not token:
        raise ValueError("缺少 JWT")
    if token.startswith("Bearer "):
        token = token[7:]
    try:
        claims = jwt.decode(token, config.JWT_SECRET, algorithms=["HS256"])
    except Exception as e:
        raise ValueError(f"JWT 校验失败: {e}")
    return claims


def extract_principal(claims: dict) -> Tuple[str, int, str]:
    """
    返回 (channel, principal_id, role)。
    channel = 'admin' if claims has adminId else 'user'
    """
    if "adminId" in claims:
        return "admin", int(claims["adminId"]), str(claims.get("role", "admin"))
    if "userId" in claims:
        return "user", int(claims["userId"]), str(claims.get("role", "user"))
    raise ValueError("JWT 中既无 userId 也无 adminId")


def quota_check_and_incr(channel: str, principal_id: int) -> Tuple[bool, int, int]:
    """
    每日配额计数：返回 (allowed, used, limit)。
    Redis key: agent:quota:{channel}:{yyyy-mm-dd}:{principal_id}, TTL 25h。
    """
    limit = config.USER_DAILY_QUOTA if channel == "user" else config.ADMIN_DAILY_QUOTA
    today = dt.datetime.utcnow().strftime("%Y-%m-%d")
    key = f"agent:quota:{channel}:{today}:{principal_id}"
    try:
        r = get_redis()
        used = r.incr(key)
        if used == 1:
            r.expire(key, 25 * 3600)
        used = int(used)
    except Exception as e:
        # Redis 不可用时降级允许（但日志告警）
        log.warning(f"quota redis 不可用，降级放行: {e}")
        return True, 0, limit
    return (used <= limit), used, limit
