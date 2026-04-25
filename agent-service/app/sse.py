"""SSE 工具：把事件序列化为 text/event-stream。"""
import asyncio
import json
import queue
import threading
from typing import AsyncGenerator, Callable


def encode_event(event_type: str, data: dict) -> str:
    """构造单条 SSE 事件帧。"""
    payload = json.dumps(data, ensure_ascii=False, default=str)
    return f"event: {event_type}\ndata: {payload}\n\n"


class SseChannel:
    """
    线程安全的事件队列，配合后台线程跑 LLM tool loop 并向 FastAPI SSE 端点流式输出。

    典型用法：
        channel = SseChannel()

        def background():
            try:
                run_tool_loop(..., sse_emit=channel.emit)
            finally:
                channel.close()

        threading.Thread(target=background, daemon=True).start()
        async for chunk in channel.stream():
            yield chunk
    """

    _SENTINEL = object()

    def __init__(self):
        self._q: "queue.Queue" = queue.Queue()

    def emit(self, event_type: str, data: dict) -> None:
        self._q.put((event_type, data))

    def close(self) -> None:
        self._q.put(self._SENTINEL)

    async def stream(self) -> AsyncGenerator[str, None]:
        loop = asyncio.get_event_loop()
        while True:
            item = await loop.run_in_executor(None, self._q.get)
            if item is self._SENTINEL:
                yield encode_event("done", {"ok": True})
                break
            event_type, data = item
            yield encode_event(event_type, data)
