"""DashScope (通义千问) Function Calling 客户端封装 + tool loop。"""
import json
import logging
import time
from typing import AsyncIterator, Callable, Dict, List, Optional

import dashscope
from dashscope import Generation

from . import config, metrics

log = logging.getLogger(__name__)

if config.DASHSCOPE_API_KEY:
    dashscope.api_key = config.DASHSCOPE_API_KEY
else:
    log.warning("DASHSCOPE_API_KEY 未配置，Agent 将无法实际调用 LLM；请在 .env 中填写。")


def _record_token(channel: str, usage: dict):
    if not usage:
        return
    metrics.agent_tokens_total.labels(channel=channel, kind="prompt").inc(usage.get("input_tokens", 0) or 0)
    metrics.agent_tokens_total.labels(channel=channel, kind="completion").inc(usage.get("output_tokens", 0) or 0)


def _stream_one_round(messages: List[Dict], tools: List[Dict]) -> Generation:
    """
    调用一次模型生成，返回 dashscope 的 Generation 响应迭代器。
    使用 incremental_output=True 实现 token 级流式。
    Function calling 需要 result_format='message'。
    """
    return Generation.call(
        model=config.QWEN_MODEL,
        messages=messages,
        tools=tools,
        result_format="message",
        stream=True,
        incremental_output=True,
        max_tokens=config.LLM_MAX_TOKENS,
        temperature=config.LLM_TEMPERATURE,
    )


def run_tool_loop(
    *,
    channel: str,
    messages: List[Dict],
    tools_spec: List[Dict],
    tool_dispatcher: Callable[[str, dict], Dict],
    sse_emit: Callable[[str, dict], None],
) -> str:
    """
    Function Calling 主循环：
      1. 把 messages 发给模型，流式吐 token
      2. 模型若返回 tool_calls，则依次执行 tool_dispatcher，把工具结果作为 message 追加
      3. 重复，直到模型给出无 tool_calls 的最终回复，或达到 TOOL_LOOP_MAX_STEPS

    Args:
        channel:        'user' or 'admin'，用于指标分桶
        messages:       初始消息（含 system + history + 当前 user）
        tools_spec:     OpenAI function calling 风格的 tool schema 列表
        tool_dispatcher:同步函数 (name, args_dict) -> dict（工具执行结果）
        sse_emit:       (event_type, payload) -> None；事件类型 token/tool_call/tool_result/done/error

    Returns:
        最终 assistant 文本。
    """
    if not config.DASHSCOPE_API_KEY:
        msg = "agent-service 未配置 DASHSCOPE_API_KEY，请在 .env 中填写后重启容器。"
        sse_emit("error", {"message": msg})
        return msg

    final_text_parts: List[str] = []

    for step in range(config.TOOL_LOOP_MAX_STEPS):
        log.info(f"[loop step={step}] msgs={len(messages)} channel={channel}")

        try:
            responses = _stream_one_round(messages, tools_spec)
        except Exception as e:
            log.exception("LLM 调用异常")
            sse_emit("error", {"message": f"模型调用失败: {e}"})
            return ""

        # 累积本轮 assistant 消息（content 增量 + tool_calls 增量）
        accumulated_content = ""
        # tool_calls 增量合并：dashscope (OpenAI 兼容) 流式时，每个 chunk 的
        # tool_calls 是 partial：{index, id?, type?, function: {name?, arguments?}},
        # 其中 function.arguments 是字符串增量片段，需要按 index 拼接。
        tc_buffer: Dict[int, Dict] = {}  # index -> {id, type, function:{name, arguments}}
        last_usage: Optional[Dict] = None
        last_finish_reason: Optional[str] = None

        for resp in responses:
            if not getattr(resp, "output", None):
                if getattr(resp, "code", None):
                    err = f"{resp.code}: {getattr(resp, 'message', '')}"
                    log.error(f"LLM 返回错误 {err}")
                    sse_emit("error", {"message": err})
                    return ""
                continue

            choices = resp.output.get("choices") if isinstance(resp.output, dict) else None
            if not choices:
                continue
            choice = choices[0]
            message = choice.get("message", {})

            # incremental content（token 级）
            delta_content = message.get("content")
            if isinstance(delta_content, str) and delta_content:
                accumulated_content += delta_content
                sse_emit("token", {"text": delta_content})

            # incremental tool_calls：按 index 累积（兼容 OpenAI/DashScope 增量协议）
            for i, tc in enumerate(message.get("tool_calls") or []):
                idx = tc.get("index", i)
                slot = tc_buffer.setdefault(idx, {"id": "", "type": "function",
                                                   "function": {"name": "", "arguments": ""}})
                if tc.get("id"):
                    slot["id"] = tc["id"]
                if tc.get("type"):
                    slot["type"] = tc["type"]
                fn = tc.get("function") or {}
                if fn.get("name"):
                    # 部分模型 name 也是分片增量；用追加更安全
                    if not slot["function"]["name"]:
                        slot["function"]["name"] = fn["name"]
                    elif slot["function"]["name"] != fn["name"]:
                        # 不一致时优先采用更长的（一般是后到的完整名）
                        if len(fn["name"]) > len(slot["function"]["name"]):
                            slot["function"]["name"] = fn["name"]
                if "arguments" in fn and fn["arguments"]:
                    slot["function"]["arguments"] += fn["arguments"]

            last_finish_reason = choice.get("finish_reason")
            last_usage = getattr(resp, "usage", None)

        # 按 index 顺序拼回完整 tool_calls 列表
        accumulated_tool_calls: List[Dict] = [tc_buffer[k] for k in sorted(tc_buffer.keys())]

        if last_usage:
            _record_token(channel, last_usage if isinstance(last_usage, dict) else dict(last_usage))

        # 把 assistant 消息加入历史（注意 dashscope 的 tool_calls 结构与 OpenAI 一致）
        assistant_msg: Dict = {"role": "assistant", "content": accumulated_content or ""}
        if accumulated_tool_calls:
            assistant_msg["tool_calls"] = accumulated_tool_calls
        messages.append(assistant_msg)

        if accumulated_content:
            final_text_parts.append(accumulated_content)

        # 没有工具调用 → 结束
        if not accumulated_tool_calls:
            break

        # 执行所有工具
        for tc in accumulated_tool_calls:
            tc_id = tc.get("id") or f"call_{step}_{int(time.time()*1000)}"
            fn = tc.get("function", {})
            name = fn.get("name", "")
            raw_args = fn.get("arguments", "{}")
            try:
                args = json.loads(raw_args) if isinstance(raw_args, str) else (raw_args or {})
            except Exception:
                args = {}

            sse_emit("tool_call", {"id": tc_id, "name": name, "args": args})

            t0 = time.time()
            try:
                result = tool_dispatcher(name, args)
                status = "ok"
            except Exception as e:
                log.exception(f"tool {name} failed")
                result = {"error": str(e)}
                status = "error"
            duration = time.time() - t0

            metrics.agent_tool_calls_total.labels(channel=channel, tool=name, status=status).inc()
            metrics.agent_tool_duration_seconds.labels(tool=name).observe(duration)

            sse_emit("tool_result", {"id": tc_id, "name": name, "result": result, "ms": int(duration * 1000)})

            messages.append({
                "role": "tool",
                "name": name,
                "tool_call_id": tc_id,
                "content": json.dumps(result, ensure_ascii=False, default=str),
            })

        if last_finish_reason and last_finish_reason not in ("tool_calls", None):
            # 模型已主动结束
            break
    else:
        # 达到 max steps
        log.warning(f"达到 tool loop 上限 {config.TOOL_LOOP_MAX_STEPS}")
        sse_emit("error", {"message": "对话太复杂，已达工具调用上限，请简化问题再试。"})

    return "".join(final_text_parts)
