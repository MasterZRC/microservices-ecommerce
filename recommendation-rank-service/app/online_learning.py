"""
在线学习模块
实时消费曝光/点击事件，更新 DeepFM-Attention 模型
"""

import torch
import torch.nn as nn
import logging
import threading
import time
import queue
from typing import Dict, List, Optional, Tuple, Any
from collections import deque
import numpy as np

logger = logging.getLogger(__name__)


class OnlineSampleBuffer:
    """在线学习样本缓冲区"""

    def __init__(self, max_size: int = 1000):
        self.max_size = max_size
        self.buffer = deque(maxlen=max_size)
        self.lock = threading.Lock()

    def add(self, sample: Tuple[Dict, Dict, int]):
        """添加样本 (user_features, item_features, label)"""
        with self.lock:
            self.buffer.append(sample)

    def get_batch(self, batch_size: int) -> Tuple[List, List, List]:
        """获取一批样本"""
        with self.lock:
            if len(self.buffer) < batch_size:
                return [], [], []

            samples = list(self.buffer)[:batch_size]
            for _ in range(min(batch_size, len(self.buffer))):
                self.buffer.popleft()

        user_features = [s[0] for s in samples]
        item_features = [s[1] for s in samples]
        labels = [s[2] for s in samples]
        return user_features, item_features, labels

    def size(self) -> int:
        with self.lock:
            return len(self.buffer)


class OnlineLearningConsumer:
    """
    实时消费曝光/点击日志，更新模型

    策略：
    - 曝光事件：label=0，加入缓冲区
    - 点击事件：label=1，加入缓冲区并触发训练
    - 定时训练：即使没有点击事件，也定期用缓冲区数据训练
    """

    def __init__(
        self,
        model: nn.Module,
        learning_rate: float = 0.0001,
        min_buffer_size: int = 32,
        train_interval_seconds: int = 60,
        max_buffer_size: int = 1000
    ):
        self.model = model
        self.device = next(model.parameters()).device

        # 使用较小的学习率进行增量更新
        self.optimizer = torch.optim.Adam(model.parameters(), lr=learning_rate)
        self.criterion = nn.BCELoss()

        self.buffer = OnlineSampleBuffer(max_size=max_buffer_size)
        self.min_buffer_size = min_buffer_size
        self.train_interval = train_interval_seconds

        self.is_running = False
        self.train_thread: Optional[threading.Thread] = None
        self.stats = {
            "total_exposure": 0,
            "total_click": 0,
            "total_train_batches": 0,
            "last_train_time": None,
            "avg_train_loss": 0.0,
        }
        self.stats_lock = threading.Lock()

    def on_exposure(self, user_id: int, product_id: int, user_features: Dict, item_features: Dict):
        """
        曝光事件：加入缓冲区，label=0
        """
        try:
            sample = (user_features.copy(), item_features.copy(), 0)
            self.buffer.add(sample)
            with self.stats_lock:
                self.stats["total_exposure"] += 1
        except Exception as e:
            logger.warning(f"曝光事件处理失败: {e}")

    def on_click(self, user_id: int, product_id: int, user_features: Dict, item_features: Dict):
        """
        点击事件：加入缓冲区并立即触发一次小批量训练
        """
        try:
            sample = (user_features.copy(), item_features.copy(), 1)
            self.buffer.add(sample)
            with self.stats_lock:
                self.stats["total_click"] += 1

            # 立即触发训练（使用点击数据优先更新）
            if self.buffer.size() >= self.min_buffer_size:
                self._train_batch()

        except Exception as e:
            logger.warning(f"点击事件处理失败: {e}")

    def start(self):
        """启动定时训练线程"""
        if self.is_running:
            logger.warning("在线学习已在运行中")
            return

        self.is_running = True
        self.train_thread = threading.Thread(target=self._train_loop, daemon=True)
        self.train_thread.start()
        logger.info("在线学习服务已启动")

    def stop(self):
        """停止在线学习"""
        self.is_running = False
        if self.train_thread:
            self.train_thread.join(timeout=5)
        logger.info("在线学习服务已停止")

    def _train_loop(self):
        """定时训练循环"""
        while self.is_running:
            time.sleep(self.train_interval)
            if not self.is_running:
                break

            if self.buffer.size() >= self.min_buffer_size:
                self._train_batch()

    def _train_batch(self):
        """执行一次小批量训练"""
        try:
            user_features, item_features, labels = self.buffer.get_batch(64)

            if not labels:
                return

            # 构建张量（复用 AttentionDataset 的特征构建逻辑）
            sparse_list = []
            dense_list = []
            seq_list = []

            for uf, itf, _ in zip(user_features, item_features, labels):
                sparse = [
                    min(int(uf.get("view_1d", 0)) // 10, 9),
                    min(int(uf.get("click_1d", 0)) // 5, 9),
                    min(int(uf.get("last_active_hours", 24)) // 24, 13),
                    int(uf.get("prefer_category", 0)) % 10,
                    int(itf.get("category_id", 0)) % 100,
                    int(itf.get("brand_id", 0)) % 50,
                    min(int(itf.get("price_bucket", 0)), 9),
                    min(int(itf.get("sales_bucket", 0)), 9),
                ]
                dense = [
                    float(uf.get("view_1d", 0)) / 100.0,
                    float(uf.get("click_1d", 0)) / 50.0,
                    float(uf.get("cart_1d", 0)) / 20.0,
                    float(uf.get("buy_1d", 0)) / 10.0,
                    float(uf.get("view_7d", 0)) / 500.0,
                    float(uf.get("last_active_hours", 24)) / 720.0,
                    float(itf.get("hot_score", 0)) / 10000.0,
                    float(itf.get("price_ratio", 0.5)),
                    float(uf.get("category_match", 0)),
                    float(uf.get("brand_match", 0)),
                ]
                # 序列特征（从用户特征中提取，如果没有则用零向量）
                seq_emb = uf.get("sequence_emb")
                if seq_emb is not None:
                    seq_list.append(seq_emb)
                else:
                    seq_list.append(np.zeros((20, 16), dtype=np.float32))

                sparse_list.append(sparse)
                dense_list.append(dense)

            sparse_tensor = torch.tensor(sparse_list, dtype=torch.long).to(self.device)
            dense_tensor = torch.tensor(dense_list, dtype=torch.float32).to(self.device)
            seq_tensor = torch.from_numpy(np.stack(seq_list)).float().to(self.device)
            labels_tensor = torch.tensor(labels, dtype=torch.float32).to(self.device)

            # 训练
            self.model.train()
            self.optimizer.zero_grad()
            outputs = self.model(sparse_tensor, dense_tensor, seq_tensor)
            loss = self.criterion(outputs, labels_tensor)
            loss.backward()
            self.optimizer.step()

            # 更新统计
            with self.stats_lock:
                self.stats["total_train_batches"] += 1
                self.stats["last_train_time"] = time.time()
                # 滑动平均 loss
                n = self.stats["total_train_batches"]
                self.stats["avg_train_loss"] = (self.stats["avg_train_loss"] * (n - 1) + loss.item()) / n

            logger.debug(f"在线学习批次完成: loss={loss.item():.4f}, buffer_size={self.buffer.size()}")

        except Exception as e:
            logger.error(f"在线学习批次训练失败: {e}")

    def get_stats(self) -> Dict[str, Any]:
        """获取在线学习统计信息"""
        with self.stats_lock:
            return {
                **self.stats,
                "buffer_size": self.buffer.size(),
                "is_running": self.is_running,
            }


class OnlineLearningService:
    """
    在线学习服务
    封装在线学习消费者，提供 REST API 接口
    """

    def __init__(self):
        self.consumer: Optional[OnlineLearningConsumer] = None
        self.model = None
        self.is_initialized = False

    def initialize(self, model: nn.Module, learning_rate: float = 0.0001):
        """初始化在线学习服务"""
        self.model = model
        self.consumer = OnlineLearningConsumer(
            model=model,
            learning_rate=learning_rate,
            min_buffer_size=32,
            train_interval_seconds=60,
            max_buffer_size=2000
        )
        self.is_initialized = True
        logger.info("在线学习服务初始化完成")

    def start(self):
        """启动在线学习"""
        if not self.is_initialized:
            raise RuntimeError("在线学习服务未初始化")
        self.consumer.start()

    def stop(self):
        """停止在线学习"""
        if self.consumer:
            self.consumer.stop()

    def record_exposure(self, user_id: int, product_id: int,
                       user_features: Dict, item_features: Dict):
        """记录曝光事件"""
        if self.consumer:
            self.consumer.on_exposure(user_id, product_id, user_features, item_features)

    def record_click(self, user_id: int, product_id: int,
                    user_features: Dict, item_features: Dict):
        """记录点击事件"""
        if self.consumer:
            self.consumer.on_click(user_id, product_id, user_features, item_features)

    def get_status(self) -> Dict[str, Any]:
        """获取在线学习状态"""
        if not self.consumer:
            return {"status": "not_initialized"}
        return {
            **self.consumer.get_stats(),
            "model_device": str(next(self.model.parameters()).device) if self.model else "N/A"
        }


# 全局单例
_online_learning_service: Optional[OnlineLearningService] = None


def get_online_learning_service() -> OnlineLearningService:
    global _online_learning_service
    if _online_learning_service is None:
        _online_learning_service = OnlineLearningService()
    return _online_learning_service
