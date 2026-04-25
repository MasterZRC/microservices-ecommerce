"""
DeepFM + Attention 模型模块
在现有 DeepFM 基础上增加 DIN 风格的 Attention 机制处理序列特征
"""

import torch
import torch.nn as nn
import torch.nn.functional as F
from typing import Dict, List, Optional, Tuple
import os
import pickle
import logging
import numpy as np

logger = logging.getLogger(__name__)


class AttentionLayer(nn.Module):
    """
    DIN 风格 Attention 层
    用于计算目标商品与用户历史行为序列之间的注意力权重
    """

    def __init__(self, embedding_dim: int = 16, attention_dim: int = 16):
        super().__init__()
        self.embedding_dim = embedding_dim
        self.attention_dim = attention_dim

        # Attention 网络：多层感知机
        self.attention_net = nn.Sequential(
            nn.Linear(embedding_dim * 3, attention_dim),
            nn.ReLU(),
            nn.Linear(attention_dim, 1),
            nn.Softmax(dim=1)
        )

    def forward(self, target_item_emb: torch.Tensor, history_item_embs: torch.Tensor) -> torch.Tensor:
        """
        计算注意力加权的历史序列表示

        Args:
            target_item_emb: 目标商品 embedding [batch, emb_dim]
            history_item_embs: 历史行为 embedding [batch, seq_len, emb_dim]

        Returns:
            加权后的历史序列表示 [batch, emb_dim]
        """
        batch_size = target_item_emb.size(0)
        seq_len = history_item_embs.size(1)

        # 构造 Attention 输入: [target, history, target - history]
        target_expanded = target_item_emb.unsqueeze(1).expand(-1, seq_len, -1)  # [batch, seq, emb]
        concat = torch.cat([target_expanded, history_item_embs, target_expanded - history_item_embs], dim=-1)

        # 计算 Attention 权重
        attention_scores = self.attention_net(concat)  # [batch, seq, 1]
        attention_weights = attention_scores.squeeze(-1)  # [batch, seq]

        # 加权求和
        weighted_history = torch.bmm(attention_weights.unsqueeze(1), history_item_embs)  # [batch, 1, emb]
        return weighted_history.squeeze(1)  # [batch, emb]


class DeepFMWithAttention(nn.Module):
    """
    DeepFM + Attention 混合模型

    在 DeepFM 基础上增加：
    - DIN 风格 Attention 处理用户历史行为序列
    - 序列特征与目标商品的注意力匹配

    输入:
    - sparse_features: [batch, 8] 稀疏特征ID
    - dense_features: [batch, dense_dim] 密集特征
    - sequence_emb: [batch, seq_len, emb_dim] 历史行为embedding

    输出:
    - 点击率预测 [batch, 1]
    """

    def __init__(
        self,
        sparse_field_dims: List[int],
        dense_dim: int,
        seq_len: int = 20,
        embedding_dim: int = 8,
        hidden_layers: List[int] = [128, 64, 32],
        dropout_rate: float = 0.2
    ):
        super().__init__()

        self.sparse_field_dims = sparse_field_dims
        self.dense_dim = dense_dim
        self.seq_len = seq_len
        self.embedding_dim = embedding_dim

        # ===== 复用原有 DeepFM 部分 =====
        self.num_fields = len(sparse_field_dims)

        # 稀疏特征嵌入层
        self.embeddings = nn.ModuleList([
            nn.Embedding(dim, embedding_dim)
            for dim in sparse_field_dims
        ])

        # FM 一阶参数
        self.first_order_weights = nn.ParameterList([
            nn.Parameter(torch.randn(1) * 0.01)
            for _ in sparse_field_dims
        ])
        self.first_order_dense = nn.Linear(dense_dim, 1, bias=False)

        # DNN 部分
        dnn_input_dim = self.num_fields * embedding_dim + dense_dim
        layers = []
        prev_dim = dnn_input_dim
        for i, hidden_dim in enumerate(hidden_layers):
            layers.extend([
                nn.Linear(prev_dim, hidden_dim),
                nn.BatchNorm1d(hidden_dim),
                nn.ReLU(),
                nn.Dropout(dropout_rate)
            ])
            prev_dim = hidden_dim
        # DNN 最后一层输出 32 维（与 seq_feat 16 拼接为 48 维）
        layers.append(nn.Linear(prev_dim, 32))
        self.dnn = nn.Sequential(*layers)

        # ===== Attention 部分 =====
        self.attention = AttentionLayer(embedding_dim, attention_dim=16)

        # 序列特征处理层
        self.seq_fc = nn.Sequential(
            nn.Linear(embedding_dim, 32),
            nn.ReLU(),
            nn.Linear(32, 16)
        )

        # 最终输出层：DeepFM(32) + Attention(16) = 48 -> 1
        self.output_fc = nn.Linear(32 + 16, 1)

        self.sigmoid = nn.Sigmoid()

    def get_item_embedding_from_sparse(self, sparse_features: torch.Tensor) -> torch.Tensor:
        """
        从稀疏特征中提取商品相关的 embedding
        使用商品类目和品牌作为商品表示
        """
        embeddings_list = []
        for i, emb_layer in enumerate(self.embeddings):
            embeddings_list.append(emb_layer(sparse_features[:, i]))
        embeddings_stack = torch.stack(embeddings_list, dim=1)
        # 取商品部分 (后4个特征) 的平均
        item_emb = embeddings_stack[:, -4:, :].mean(dim=1)
        return item_emb

    def forward(
        self,
        sparse_features: torch.Tensor,
        dense_features: torch.Tensor,
        sequence_emb: torch.Tensor
    ) -> torch.Tensor:
        """
        前向传播

        Args:
            sparse_features: [batch, num_fields] 稀疏特征
            dense_features: [batch, dense_dim] 密集特征
            sequence_emb: [batch, seq_len, emb_dim] 历史行为 embedding

        Returns:
            预测 CTR [batch, 1]
        """
        batch_size = sparse_features.size(0)

        # ========== DeepFM 部分 ==========
        embeddings_list = []
        for i, emb_layer in enumerate(self.embeddings):
            embeddings_list.append(emb_layer(sparse_features[:, i]))
        embeddings_stack = torch.stack(embeddings_list, dim=1)

        # 一阶: 稀疏特征 embedding 求和 + 密集特征线性变换
        first_order_emb_sum = embeddings_stack.sum(dim=1)  # [batch, embedding_dim]
        first_order = first_order_emb_sum.sum(dim=1)  # [batch] 标量

        # 加上密集特征的线性组合
        first_order = first_order + self.first_order_dense(dense_features).squeeze(-1)  # [batch]

        # 二阶: FM 交互项
        emb_sum = embeddings_stack.sum(dim=1)
        square_of_sum = torch.pow(emb_sum, 2)
        emb_squared = torch.pow(embeddings_stack, 2)
        sum_of_square = emb_squared.sum(dim=1)
        second_order = 0.5 * (square_of_sum - sum_of_square).sum(dim=1)  # [batch]

        # DNN
        emb_flat = embeddings_stack.view(batch_size, -1)
        dnn_input = torch.cat([emb_flat, dense_features], dim=1)
        dnn_output = self.dnn(dnn_input)  # [batch, 32]

        deepfm_out = first_order.unsqueeze(1) + second_order.unsqueeze(1) + dnn_output  # [batch, 32]

        # ========== Attention 部分 ==========
        target_emb = self.get_item_embedding_from_sparse(sparse_features)
        attention_weighted = self.attention(target_emb, sequence_emb)
        seq_feat = self.seq_fc(attention_weighted)  # [batch, 16]

        # ========== 合并输出 ==========
        # deepfm_out: [batch, 32], seq_feat: [batch, 16] -> concat -> [batch, 48]
        combined = torch.cat([deepfm_out, seq_feat], dim=1)
        output = self.output_fc(combined).squeeze(-1)
        output = self.sigmoid(output)

        return output


class AttentionDataset(torch.utils.data.Dataset):
    """支持序列特征的点击率预估数据集"""

    def __init__(
        self,
        user_features: List[Dict],
        item_features: List[Dict],
        labels: List[int],
        sequence_embs: Optional[List[np.ndarray]] = None,
        embedding_dim: int = 8,
        seq_len: int = 20
    ):
        self.user_features = user_features
        self.item_features = item_features
        self.labels = labels
        self.sequence_embs = sequence_embs
        self.embedding_dim = embedding_dim
        self.seq_len = seq_len

    def __len__(self):
        return len(self.labels)

    def __getitem__(self, idx) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
        # 稀疏特征
        user_feat = self.user_features[idx]
        item_feat = self.item_features[idx]

        sparse = [
            min(int(user_feat.get("view_1d_bucket", 0)), 9),
            min(int(user_feat.get("click_1d_bucket", 0)), 9),
            min(int(user_feat.get("active_days_bucket", 0)), 13),
            min(int(user_feat.get("prefer_category_idx", 0)), 9),
            int(item_feat.get("category_id", 0)) % 100,
            int(item_feat.get("brand_id", 0)) % 50,
            min(int(item_feat.get("price_bucket", 0)), 9),
            min(int(item_feat.get("sales_bucket", 0)), 9),
        ]

        # 密集特征
        dense = [
            float(user_feat.get("view_1d", 0)) / 100.0,
            float(user_feat.get("click_1d", 0)) / 50.0,
            float(user_feat.get("cart_1d", 0)) / 20.0,
            float(user_feat.get("buy_1d", 0)) / 10.0,
            float(user_feat.get("view_7d", 0)) / 500.0,
            float(user_feat.get("last_active_hours", 24)) / 720.0,
            float(item_feat.get("hot_score", 0)) / 10000.0,
            float(item_feat.get("price_ratio", 0.5)),
            float(user_feat.get("category_match", 0)),
            float(user_feat.get("brand_match", 0)),
        ]

        # 序列特征（与模型的 embedding_dim 对齐）
        if self.sequence_embs is not None and idx < len(self.sequence_embs):
            seq_emb = torch.from_numpy(self.sequence_embs[idx]).float()
            # 确保维度正确（兼容旧数据）
            if seq_emb.shape[0] != self.seq_len:
                seq_emb = torch.zeros(self.seq_len, self.embedding_dim, dtype=torch.float32)
            elif seq_emb.shape[1] != self.embedding_dim:
                seq_emb = torch.zeros(self.seq_len, self.embedding_dim, dtype=torch.float32)
        else:
            seq_emb = torch.zeros(self.seq_len, self.embedding_dim, dtype=torch.float32)

        return (
            torch.tensor(sparse, dtype=torch.long),
            torch.tensor(dense, dtype=torch.float32),
            seq_emb,
            torch.tensor(self.labels[idx], dtype=torch.float32)
        )


class DeepFMAttentionTrainer:
    """DeepFM-Attention 模型训练器"""

    def __init__(
        self,
        model: DeepFMWithAttention,
        device: str = "cpu",
        learning_rate: float = 0.001,
        weight_decay: float = 1e-5
    ):
        self.model = model.to(device)
        self.device = device
        self.optimizer = torch.optim.Adam(model.parameters(), lr=learning_rate, weight_decay=weight_decay)
        self.criterion = nn.BCELoss()

    def train_epoch(self, dataloader) -> float:
        self.model.train()
        total_loss = 0.0
        num_batches = 0

        for batch in dataloader:
            sparse_feat, dense_feat, seq_emb, labels = batch
            sparse_feat = sparse_feat.to(self.device)
            dense_feat = dense_feat.to(self.device)
            seq_emb = seq_emb.to(self.device)
            labels = labels.to(self.device)

            self.optimizer.zero_grad()
            outputs = self.model(sparse_feat, dense_feat, seq_emb)
            loss = self.criterion(outputs, labels)
            loss.backward()
            self.optimizer.step()

            total_loss += loss.item()
            num_batches += 1

        return total_loss / num_batches if num_batches > 0 else 0.0

    def evaluate(self, dataloader) -> Dict:
        self.model.eval()
        total_loss = 0.0
        all_probs = []
        all_labels = []

        with torch.no_grad():
            for batch in dataloader:
                sparse_feat, dense_feat, seq_emb, labels = batch
                sparse_feat = sparse_feat.to(self.device)
                dense_feat = dense_feat.to(self.device)
                seq_emb = seq_emb.to(self.device)
                labels = labels.to(self.device)

                outputs = self.model(sparse_feat, dense_feat, seq_emb)
                loss = self.criterion(outputs, labels)
                total_loss += loss.item()

                probs = outputs.cpu().numpy().flatten()
                labs = labels.cpu().numpy().flatten()
                all_probs.extend(probs.tolist())
                all_labels.extend(labs.tolist())

        all_probs = np.array(all_probs, dtype=np.float32).flatten()
        all_labels = np.array(all_labels, dtype=np.int32).flatten()
        n = len(all_labels)
        pos_count = int(all_labels.sum())

        avg_loss = total_loss / len(dataloader) if len(dataloader) > 0 else 0.0

        eps = 1e-7
        clipped_probs = np.clip(all_probs, eps, 1 - eps)
        logloss = float(-np.mean(
            all_labels * np.log(clipped_probs) + (1 - all_labels) * np.log(1 - clipped_probs)
        ))

        auc = 0.0
        neg_count = n - pos_count
        if pos_count > 0 and neg_count > 0:
            try:
                from sklearn.metrics import roc_auc_score
                auc = float(roc_auc_score(all_labels, all_probs))
            except Exception:
                auc = 0.0

        accuracy = float(np.mean((all_probs > 0.5).astype(int) == all_labels))
        preds = (all_probs > 0.5).astype(int)
        tp = int(np.sum((preds == 1) & (all_labels == 1)))
        pred_pos = int(np.sum(preds))
        precision = float(tp / pred_pos) if pred_pos > 0 else 0.0
        recall = float(tp / pos_count) if pos_count > 0 else 0.0
        f1 = float(2 * precision * recall / (precision + recall)) if (precision + recall) > 0 else 0.0

        return {
            "loss": avg_loss,
            "logloss": logloss,
            "auc": auc,
            "accuracy": accuracy,
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "positive_rate": float(pos_count / n) if n > 0 else 0.0,
        }

    def save_model(self, path: str):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        torch.save({
            "model_state_dict": self.model.state_dict(),
            "sparse_field_dims": self.model.sparse_field_dims,
            "dense_dim": self.model.dense_dim,
            "seq_len": self.model.seq_len,
            "embedding_dim": self.model.embedding_dim,
        }, path)
        logger.info(f"DeepFM-Attention 模型已保存到: {path}")

    def load_model(self, path: str):
        checkpoint = torch.load(path, map_location=self.device)
        self.model.load_state_dict(checkpoint["model_state_dict"])
        logger.info(f"DeepFM-Attention 模型已从: {path} 加载")


class DeepFMAttentionRanker:
    """DeepFM-Attention 排序器封装"""

    DEFAULT_SPARSE_DIMS = [10, 10, 14, 10, 100, 50, 10, 10]
    DEFAULT_DENSE_DIM = 10
    DEFAULT_SEQ_LEN = 20
    DEFAULT_EMBEDDING_DIM = 8

    def __init__(self, model_path: Optional[str] = None, embedding_dim: int = None):
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model: Optional[DeepFMWithAttention] = None
        self.trainer: Optional[DeepFMAttentionTrainer] = None
        self.seq_builder = None
        self.is_loaded = False
        self.model_path = model_path

        self.embedding_dim = embedding_dim or self.DEFAULT_EMBEDDING_DIM
        self.seq_len = self.DEFAULT_SEQ_LEN
        self.sparse_field_dims = self.DEFAULT_SPARSE_DIMS
        self.dense_dim = self.DEFAULT_DENSE_DIM

    def build_model(self) -> DeepFMWithAttention:
        model = DeepFMWithAttention(
            sparse_field_dims=self.sparse_field_dims,
            dense_dim=self.dense_dim,
            seq_len=self.seq_len,
            embedding_dim=self.embedding_dim,
            hidden_layers=[128, 64, 32],
            dropout_rate=0.2
        )
        return model

    def load_model(self, model_path: Optional[str] = None) -> bool:
        try:
            path = model_path or self.model_path
            if path and os.path.exists(path):
                checkpoint = torch.load(path, map_location=self.device)
                self.sparse_field_dims = checkpoint.get("sparse_field_dims", self.DEFAULT_SPARSE_DIMS)
                self.dense_dim = checkpoint.get("dense_dim", self.DEFAULT_DENSE_DIM)
                self.seq_len = checkpoint.get("seq_len", self.DEFAULT_SEQ_LEN)
                self.embedding_dim = checkpoint.get("embedding_dim", 8)

                self.model = self.build_model()
                self.model.load_state_dict(checkpoint["model_state_dict"])
                logger.info(f"从 {path} 加载 DeepFM-Attention 模型")
            else:
                logger.info("初始化新的 DeepFM-Attention 模型")
                self.model = self.build_model()

            self.model.to(self.device)
            self.model.eval()
            self.is_loaded = True

            self.trainer = DeepFMAttentionTrainer(
                self.model,
                device=str(self.device),
                learning_rate=0.001,
                weight_decay=1e-5
            )

            # 初始化序列特征构建器
            from .features import SequenceFeatureBuilder
            self.seq_builder = SequenceFeatureBuilder()

            logger.info(f"DeepFM-Attention 模型已加载到设备: {self.device}")
            return True

        except Exception as e:
            logger.error(f"模型加载失败: {e}")
            return False

    def rank(
        self,
        user_features: Dict,
        item_features: Dict,
        user_id: int = None
    ) -> List[float]:
        """对候选商品进行排序"""
        if not self.model or not self.is_loaded:
            logger.warning("模型未加载，返回默认分数")
            return [0.5] * len(item_features)

        self.model.eval()

        item_ids = list(item_features.keys())

        # 如果提供了 user_id，构建序列特征
        if user_id and self.seq_builder:
            behaviors = self.seq_builder.load_user_behavior_sequence(user_id, limit=self.seq_len)
            seq_embs = self.seq_builder.get_sequence_embedding(behaviors, self.embedding_dim)
            seq_embs = np.stack([seq_embs] * len(item_ids), axis=0)  # [batch, seq_len, emb_dim]
        else:
            seq_embs = np.zeros((len(item_ids), self.seq_len, self.embedding_dim), dtype=np.float32)

        with torch.no_grad():
            batch_sparse = []
            batch_dense = []

            for item_id in item_ids:
                item_feat = item_features[item_id]

                prefer_category = user_features.get("prefer_category")
                if isinstance(prefer_category, list):
                    prefer_category = prefer_category[0] if prefer_category else 0
                if prefer_category is None:
                    prefer_category = 0
                prefer_brand = user_features.get("prefer_brand")
                if isinstance(prefer_brand, list):
                    prefer_brand = prefer_brand[0] if prefer_brand else 0
                if prefer_brand is None:
                    prefer_brand = 0

                sparse = [
                    min(int(user_features.get("view_1d", 0)) // 10, 9),
                    min(int(user_features.get("click_1d", 0)) // 5, 9),
                    min(int(user_features.get("last_active_hours", 24)) // 24, 13),
                    int(prefer_category) % 10,
                    int(item_feat.get("category_id", 0)) % 100,
                    int(item_feat.get("brand_id", 0)) % 50,
                    min(int(item_feat.get("price_bucket", 0)), 9),
                    min(int(item_feat.get("sales_bucket", 0)), 9),
                ]

                dense = [
                    float(user_features.get("view_1d", 0)) / 100.0,
                    float(user_features.get("click_1d", 0)) / 50.0,
                    float(user_features.get("cart_1d", 0)) / 20.0,
                    float(user_features.get("buy_1d", 0)) / 10.0,
                    float(user_features.get("view_7d", 0)) / 500.0,
                    float(user_features.get("last_active_hours", 24)) / 720.0,
                    float(item_feat.get("hot_score", 0)) / 10000.0,
                    float(item_feat.get("price_ratio", 0.5)),
                    1.0 if prefer_category == item_feat.get("category_id") else 0.0,
                    1.0 if prefer_brand == item_feat.get("brand_id") else 0.0,
                ]

                batch_sparse.append(sparse)
                batch_dense.append(dense)

            sparse_tensor = torch.tensor(batch_sparse, dtype=torch.long).to(self.device)
            dense_tensor = torch.tensor(batch_dense, dtype=torch.float32).to(self.device)
            seq_tensor = torch.from_numpy(seq_embs).to(self.device)

            scores = self.model(sparse_tensor, dense_tensor, seq_tensor)
            return scores.cpu().numpy().tolist()

    def train(
        self,
        train_data: Dict,
        val_data: Optional[Dict] = None,
        sequence_embs_train: Optional[List[np.ndarray]] = None,
        sequence_embs_val: Optional[List[np.ndarray]] = None,
        epochs: int = 10,
        batch_size: int = 256,
        save_path: Optional[str] = None
    ) -> Dict:
        """训练模型"""
        if not self.model:
            self.load_model()

        train_dataset = AttentionDataset(
            user_features=train_data["user_features"],
            item_features=train_data["item_features"],
            labels=train_data["labels"],
            sequence_embs=sequence_embs_train,
            embedding_dim=self.embedding_dim,
            seq_len=self.seq_len
        )
        train_loader = torch.utils.data.DataLoader(train_dataset, batch_size=batch_size, shuffle=True)

        val_loader = None
        if val_data:
            val_dataset = AttentionDataset(
                user_features=val_data["user_features"],
                item_features=val_data["item_features"],
                labels=val_data["labels"],
                sequence_embs=sequence_embs_val,
                embedding_dim=self.embedding_dim,
                seq_len=self.seq_len
            )
            val_loader = torch.utils.data.DataLoader(val_dataset, batch_size=batch_size)

        history = {"train_loss": [], "train_metrics": []}
        if val_loader:
            history["val_loss"] = []
            history["val_metrics"] = []

        best_val_auc = 0.0

        for epoch in range(epochs):
            train_loss = self.trainer.train_epoch(train_loader)
            train_metrics = self.trainer.evaluate(train_loader)

            history["train_loss"].append(train_loss)
            history["train_metrics"].append(train_metrics)

            logger.info(f"Epoch {epoch+1}/{epochs} | "
                        f"Train Loss={train_loss:.4f} AUC={train_metrics.get('auc', 0):.4f} "
                        f"LogLoss={train_metrics.get('logloss', 0):.4f}")

            if val_loader:
                val_metrics = self.trainer.evaluate(val_loader)
                history["val_loss"].append(val_metrics["loss"])
                history["val_metrics"].append(val_metrics)

                logger.info(f"           Val     Loss={val_metrics['loss']:.4f} "
                            f"AUC={val_metrics.get('auc', 0):.4f}")

                val_auc = val_metrics.get("auc", 0.0)
                if val_auc > best_val_auc and save_path:
                    best_val_auc = val_auc
                    self.trainer.save_model(save_path)
                    logger.info(f"  ★ 保存新最佳模型 (AUC={best_val_auc:.4f})")

        if not val_loader and save_path:
            self.trainer.save_model(save_path)

        return history
