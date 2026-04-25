"""受限只读 SQL 校验器：白名单表 + SELECT only + 自动 LIMIT。"""
import re
from typing import Set, Tuple

import sqlparse
from sqlparse.sql import Statement
from sqlparse.tokens import DML


# 允许的表（与 admin-service 数据库一致）
ALLOWED_TABLES = {
    "product", "category", "order_info", "order_item",
    "user", "user_behavior", "product_exposure",
    "seckill_product", "seckill_activity",
}

# 任何写操作关键字一律拒绝（多重防御）
WRITE_KEYWORDS = re.compile(
    r"\b(insert|update|delete|drop|truncate|alter|create|grant|revoke|"
    r"rename|merge|replace|call|exec|execute|do|use|set\s+|lock\s+|unlock\s+)\b",
    re.IGNORECASE,
)

DEFAULT_LIMIT = 200
MAX_LIMIT = 500


def _has_limit(stmt_text: str) -> bool:
    return re.search(r"\blimit\b\s+\d+", stmt_text, re.IGNORECASE) is not None


_TABLE_NAME_RE = re.compile(
    r"(?:\bFROM|\bJOIN)\s+([`\"\w]+(?:\.[`\"\w]+)?)",
    re.IGNORECASE,
)


def _extract_table_names(stmt_text: str) -> Set[str]:
    """
    从 SQL 文本中正则提取 FROM/JOIN 后的表名（含 schema.table 形式）。
    简单且稳定，避免依赖 sqlparse 内部 token 结构。
    """
    names: Set[str] = set()
    for m in _TABLE_NAME_RE.finditer(stmt_text):
        raw = m.group(1)
        # 去掉反引号/双引号；取最后一段（schema.table -> table）
        name = raw.replace("`", "").replace('"', "").split(".")[-1].strip().lower()
        if name and not name.startswith("("):
            names.add(name)
    return names


def validate_and_normalize(sql: str) -> Tuple[bool, str, str]:
    """
    校验并规范化 SQL。
    返回 (ok, normalized_sql_or_message, reason)

    - 单条语句、必须以 SELECT 开头
    - 不允许任何写关键字
    - 表必须在白名单
    - 自动追加 LIMIT
    """
    if not sql or not sql.strip():
        return False, "SQL 为空", "empty"

    raw = sql.strip().rstrip(";").strip()

    # 1. 不允许多条
    statements = sqlparse.split(raw)
    if len(statements) > 1:
        return False, "禁止一次执行多条 SQL", "multiple"

    # 2. 必须 SELECT
    parsed = sqlparse.parse(raw)
    if not parsed:
        return False, "SQL 解析失败", "unparsable"
    stmt = parsed[0]
    first_token = stmt.token_first(skip_ws=True, skip_cm=True)
    if first_token is None or first_token.ttype is not DML or first_token.value.upper() != "SELECT":
        return False, "仅允许 SELECT 查询", "not_select"

    # 3. 写关键字黑名单（含一些不在 sqlparse keyword 体系内的）
    if WRITE_KEYWORDS.search(raw):
        return False, "检测到写操作或危险关键字，已拒绝", "write_kw"

    # 4. 表白名单
    tables = _extract_table_names(raw)
    if not tables:
        return False, "未识别到任何表名（请确保使用 FROM/JOIN）", "no_table"
    illegal = tables - ALLOWED_TABLES
    if illegal:
        return False, f"表 {sorted(illegal)} 不在白名单内（允许：{sorted(ALLOWED_TABLES)}）", "table_not_allowed"

    # 5. 自动追加 LIMIT
    normalized = raw
    if not _has_limit(normalized):
        normalized = f"{normalized} LIMIT {DEFAULT_LIMIT}"
    else:
        # 用户写了 LIMIT，但若 > MAX_LIMIT 截断（替换数字部分）
        def _cap(m):
            n = int(m.group(1))
            return f"LIMIT {min(n, MAX_LIMIT)}"
        normalized = re.sub(r"\blimit\b\s+(\d+)", _cap, normalized, flags=re.IGNORECASE)

    return True, normalized, "ok"
