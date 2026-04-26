"""集中配置：环境变量、下游服务 URL、模型/限额参数。"""
import os


def _env(key: str, default: str = "") -> str:
    return os.environ.get(key, default).strip()


# ===== 模型配置 =====
_LLM_PROVIDER = _env("LLM_PROVIDER", "qwen").lower()
LLM_PROVIDER = _LLM_PROVIDER if _LLM_PROVIDER in {"qwen", "deepseek"} else "qwen"
DASHSCOPE_API_KEY = _env("DASHSCOPE_API_KEY")
QWEN_MODEL = _env("QWEN_MODEL", "qwen-plus")
DEEPSEEK_API_KEY = _env("DEEPSEEK_API_KEY")
DEEPSEEK_MODEL = _env("DEEPSEEK_MODEL", "deepseek-v4-flash")
DEEPSEEK_BASE_URL = _env("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
DEEPSEEK_THINKING = _env("DEEPSEEK_THINKING", "disabled").lower()
if DEEPSEEK_THINKING not in {"enabled", "disabled"}:
    DEEPSEEK_THINKING = "disabled"
LLM_MAX_TOKENS = int(_env("LLM_MAX_TOKENS", "2048"))
LLM_TEMPERATURE = float(_env("LLM_TEMPERATURE", "0.3"))
TOOL_LOOP_MAX_STEPS = int(_env("TOOL_LOOP_MAX_STEPS", "8"))
TOOL_HTTP_TIMEOUT_SECONDS = float(_env("TOOL_HTTP_TIMEOUT_SECONDS", "5"))

# ===== JWT 配置（与 user-service / api-gateway 共用同一密钥）=====
JWT_SECRET = _env("JWT_SECRET", "mySecretKeyForEcommerceGraduationProject2024")

# ===== 下游服务 URL（容器内 docker DNS） =====
PRODUCT_SERVICE_URL = _env("PRODUCT_SERVICE_URL", "http://product-service:8002")
ORDER_SERVICE_URL = _env("ORDER_SERVICE_URL", "http://order-service:8003")
RECOMMEND_SERVICE_URL = _env("RECOMMEND_SERVICE_URL", "http://recommendation-service:8004")
ADMIN_SERVICE_URL = _env("ADMIN_SERVICE_URL", "http://admin-service:8006")

# ===== Redis (用于配额计数) =====
REDIS_HOST = _env("REDIS_HOST", "redis")
REDIS_PORT = int(_env("REDIS_PORT", "6379"))

# ===== MySQL (admin agent 受限 SQL 工具) =====
MYSQL_HOST = _env("MYSQL_HOST", "mysql")
MYSQL_PORT = int(_env("MYSQL_PORT", "3306"))
MYSQL_USER = _env("MYSQL_USER", "root")
MYSQL_PASSWORD = _env("MYSQL_PASSWORD", "root123")
MYSQL_DATABASE = _env("MYSQL_DATABASE", "ecommerce")

# ===== 配额（防止 token 失控烧钱） =====
USER_DAILY_QUOTA = int(_env("AGENT_USER_DAILY_QUOTA", "50"))
ADMIN_DAILY_QUOTA = int(_env("AGENT_ADMIN_DAILY_QUOTA", "200"))


def is_llm_configured() -> bool:
    if LLM_PROVIDER == "deepseek":
        return bool(DEEPSEEK_API_KEY)
    return bool(DASHSCOPE_API_KEY)


def selected_model() -> str:
    if LLM_PROVIDER == "deepseek":
        return DEEPSEEK_MODEL
    return QWEN_MODEL


def selected_api_key_name() -> str:
    if LLM_PROVIDER == "deepseek":
        return "DEEPSEEK_API_KEY"
    return "DASHSCOPE_API_KEY"
