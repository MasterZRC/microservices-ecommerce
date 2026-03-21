"""
DeepFM 模型模块
包含模型定义、训练逻辑和推理功能
"""
import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import Dataset, DataLoader
import numpy as np
from typing import Dict, Optional, List, Tuple
import os
import pickle
import logging
import yaml
import time

logger = logging.getLogger(__name__)


def _load_config() -> Dict:
    """从 config.yaml 加载模型配置，失败则使用默认值"""
    config_path = os.path.join(os.path.dirname(__file__), "config.yaml")
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                return yaml.safe_load(f)
        except Exception as e:
            logger.warning(f"加载 config.yaml 失败，使用默认配置: {e}")
    return {}


class ClickDataset(Dataset):
    """点击率预估数据集"""

    def __init__(self, user_features: List[Dict], item_features: List[Dict], labels: List[int]):
        """
        Args:
            user_features: 用户特征列表
            item_features: 商品特征列表
            labels: 点击标签 (0/1)
        """
        self.user_features = user_features
        self.item_features = item_features
        self.labels = labels

    def __len__(self):
        return len(self.labels)

    def __getitem__(self, idx) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        # 提取用户稀疏特征
        user_sparse = [
            self.user_features[idx].get("view_1d_bucket", 0),
            self.user_features[idx].get("click_1d_bucket", 0),
            self.user_features[idx].get("active_days_bucket", 0),
            self.user_features[idx].get("prefer_category_idx", 0),
        ]

        # 提取用户密集特征
        user_dense = [
            self.user_features[idx].get("view_1d", 0) / 100.0,
            self.user_features[idx].get("click_1d", 0) / 50.0,
            self.user_features[idx].get("cart_1d", 0) / 20.0,
            self.user_features[idx].get("buy_1d", 0) / 10.0,
            self.user_features[idx].get("view_7d", 0) / 500.0,
            self.user_features[idx].get("last_active_hours", 24) / 720.0,
        ]

        # 提取商品稀疏特征
        item_sparse = [
            self.item_features[idx].get("category_id", 0) % 100,
            self.item_features[idx].get("brand_id", 0) % 50,
            min(int(self.item_features[idx].get("price_bucket", 0) or 0), 9),
            min(int(self.item_features[idx].get("sales_bucket", 0) or 0), 9),
        ]

        # 提取商品密集特征
        item_dense = [
            self.item_features[idx].get("hot_score", 0) / 10000.0,
            self.item_features[idx].get("price_ratio", 0.5),
        ]

        # 交叉特征
        cross_features = [
            self.user_features[idx].get("category_match", 0),
            self.user_features[idx].get("brand_match", 0),
        ]

        # 合并所有稀疏特征
        sparse = user_sparse + item_sparse

        # 合并所有密集特征
        dense = user_dense + item_dense + cross_features

        return (
            torch.tensor(sparse, dtype=torch.long),
            torch.tensor(dense, dtype=torch.float32),
            torch.tensor(self.labels[idx], dtype=torch.float32)
        )


class DeepFM(nn.Module):
    """
    DeepFM 模型
    结合了 FM（因子分解机）的一阶、二阶特征交互和 DNN（深度神经网络）的高阶特征交互
    """

    def __init__(self, sparse_field_dims: List[int], dense_dim: int,
                 embedding_dim: int = 8, hidden_layers: List[int] = [128, 64, 32],
                 dropout_rate: float = 0.2):
        """
        Args:
            sparse_field_dims: 各稀疏特征域的维度列表
            dense_dim: 密集特征维度
            embedding_dim: 嵌入向量维度
            hidden_layers: DNN 隐藏层维度
            dropout_rate: Dropout 比率
        """
        super(DeepFM, self).__init__()

        self.sparse_field_dims = sparse_field_dims
        self.dense_dim = dense_dim
        self.embedding_dim = embedding_dim
        self.num_fields = len(sparse_field_dims)

        # 嵌入层 - 每个稀疏特征域一个嵌入矩阵
        self.embeddings = nn.ModuleList([
            nn.Embedding(dim, embedding_dim)
            for dim in sparse_field_dims
        ])

        # FM 部分参数
        # 一阶: 每个特征的偏置
        self.first_order_weights = nn.ParameterList([
            nn.Parameter(torch.randn(1) * 0.01)
            for _ in sparse_field_dims
        ])
        self.first_order_dense = nn.Linear(dense_dim, 1, bias=False)

        # 二阶: 使用嵌入向量（已经在forward中计算）

        # DNN 部分
        dnn_input_dim = self.num_fields * embedding_dim + dense_dim
        layers = []
        prev_dim = dnn_input_dim
        for hidden_dim in hidden_layers:
            layers.extend([
                nn.Linear(prev_dim, hidden_dim),
                nn.BatchNorm1d(hidden_dim),
                nn.ReLU(),
                nn.Dropout(dropout_rate)
            ])
            prev_dim = hidden_dim
        layers.append(nn.Linear(prev_dim, 1))
        self.dnn = nn.Sequential(*layers)

        # 最终输出层
        self.sigmoid = nn.Sigmoid()

    def forward(self, sparse_features: torch.Tensor, dense_features: torch.Tensor) -> torch.Tensor:
        """
        Args:
            sparse_features: 稀疏特征 [batch_size, num_fields]
            dense_features: 密集特征 [batch_size, dense_dim]
        Returns:
            预测 CTR [batch_size]
        """
        batch_size = sparse_features.size(0)

        # ========== FM 部分 ==========

        # 一阶: 每个稀疏特征的嵌入向量
        embeddings_list = []
        for i, emb_layer in enumerate(self.embeddings):
            embeddings_list.append(emb_layer(sparse_features[:, i]))
        embeddings_stack = torch.stack(embeddings_list, dim=1)  # [batch, num_fields, embedding_dim]
        
        # 一阶：嵌入向量求和 + 密集特征线性变换
        first_order_emb_sum = embeddings_stack.sum(dim=1)  # [batch, embedding_dim]
        
        first_order = torch.zeros(batch_size, device=sparse_features.device)
        for i, weight in enumerate(self.first_order_weights):
            first_order += (sparse_features[:, i].float() * weight)
        first_order = first_order + self.first_order_dense(dense_features).squeeze(-1)

        # 二阶: FM 交互项 (0.5 * (sum(V)^2 - sum(V^2)))
        emb_sum = embeddings_stack.sum(dim=1)  # [batch, embedding_dim]
        square_of_sum = torch.pow(emb_sum, 2)  # [batch, embedding_dim]
        
        emb_squared = torch.pow(embeddings_stack, 2)  # [batch, num_fields, embedding_dim]
        sum_of_square = emb_squared.sum(dim=1)  # [batch, embedding_dim]
        
        second_order = 0.5 * (square_of_sum - sum_of_square)
        second_order = second_order.sum(dim=1)  # Sum over embedding_dim to get [batch]

        # ========== DNN 部分 ==========
        # 将嵌入向量展平并与密集特征拼接
        emb_flat = embeddings_stack.view(batch_size, -1)  # [batch, num_fields * embedding_dim]
        dnn_input = torch.cat([emb_flat, dense_features], dim=1)
        dnn_output = self.dnn(dnn_input).squeeze(-1)

        # ========== 合并输出 ==========
        output = first_order + second_order + dnn_output
        output = self.sigmoid(output)

        return output


class DeepFMTrainer:
    """DeepFM 模型训练器"""

    def __init__(self, model: DeepFM, device: str = "cpu", learning_rate: float = None, weight_decay: float = 1e-5):
        self.model = model.to(device)
        self.device = device
        lr = learning_rate if learning_rate is not None else 0.001
        self.optimizer = optim.Adam(model.parameters(), lr=lr, weight_decay=weight_decay)
        self.criterion = nn.BCELoss()

    def train_epoch(self, dataloader: DataLoader) -> float:
        """训练一个epoch"""
        self.model.train()
        total_loss = 0.0
        num_batches = 0

        for sparse_feat, dense_feat, labels in dataloader:
            sparse_feat = sparse_feat.to(self.device)
            dense_feat = dense_feat.to(self.device)
            labels = labels.to(self.device)

            self.optimizer.zero_grad()

            # 前向传播
            outputs = self.model(sparse_feat, dense_feat)

            # 计算损失
            loss = self.criterion(outputs, labels)

            # 反向传播
            loss.backward()
            self.optimizer.step()

            total_loss += loss.item()
            num_batches += 1

        return total_loss / num_batches if num_batches > 0 else 0.0

    def evaluate(self, dataloader: DataLoader) -> Dict[str, float]:
        """评估模型，返回 CTR 预估标准指标：Loss / LogLoss / AUC / Accuracy / Precision / Recall / F1"""
        self.model.eval()
        total_loss = 0.0
        all_preds = []
        all_labels = []
        all_probs = []

        with torch.no_grad():
            for sparse_feat, dense_feat, labels in dataloader:
                sparse_feat = sparse_feat.to(self.device)
                dense_feat = dense_feat.to(self.device)
                labels = labels.to(self.device)

                outputs = self.model(sparse_feat, dense_feat)
                loss = self.criterion(outputs, labels)

                total_loss += loss.item()
                probs = outputs.cpu().numpy()
                preds = (probs > 0.5).astype(int)
                all_probs.extend(probs.tolist())
                all_preds.extend(preds.tolist())
                all_labels.extend(labels.cpu().numpy().tolist())

        # 转换为 numpy
        all_probs = np.array(all_probs)
        all_preds = np.array(all_preds)
        all_labels = np.array(all_labels)
        n = len(all_labels)
        pos_count = all_labels.sum()
        neg_count = n - pos_count

        # ---- 标准 CTR 预估指标 ----

        # 1. BCE Loss（平均）
        avg_loss = total_loss / len(dataloader) if len(dataloader) > 0 else 0.0

        # 2. LogLoss（对数损失）
        eps = 1e-7
        clipped_probs = np.clip(all_probs, eps, 1 - eps)
        logloss = float(-np.mean(
            all_labels * np.log(clipped_probs) + (1 - all_labels) * np.log(1 - clipped_probs)
        ))

        # 3. AUC（ROC AUC，需要同时有正负样本）
        auc = 0.0
        if pos_count > 0 and neg_count > 0:
            from sklearn.metrics import roc_auc_score
            try:
                auc = float(roc_auc_score(all_labels, all_probs))
            except Exception:
                auc = 0.0

        # 4. Accuracy / Precision / Recall / F1
        accuracy = float((all_preds == all_labels).mean())

        true_positives = int(np.sum((all_preds == 1) & (all_labels == 1)))
        pred_positives = int(np.sum(all_preds == 1))
        actual_positives = int(pos_count)

        precision = float(true_positives / pred_positives) if pred_positives > 0 else 0.0
        recall = float(true_positives / actual_positives) if actual_positives > 0 else 0.0
        f1 = float(2 * precision * recall / (precision + recall)) if (precision + recall) > 0 else 0.0

        # 5. Positive rate（数据平衡度参考）
        positive_rate = float(pos_count / n) if n > 0 else 0.0

        return {
            "loss": avg_loss,
            "logloss": logloss,
            "auc": auc,
            "accuracy": accuracy,
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "positive_rate": positive_rate,
            "positive_count": int(pos_count),
            "negative_count": int(neg_count),
            "total_count": n,
        }

    def save_model(self, path: str):
        """保存模型"""
        os.makedirs(os.path.dirname(path), exist_ok=True)
        torch.save({
            "model_state_dict": self.model.state_dict(),
            "sparse_field_dims": self.model.sparse_field_dims,
            "dense_dim": self.model.dense_dim,
            "embedding_dim": self.model.embedding_dim,
        }, path)
        logger.info(f"模型已保存到: {path}")

    def load_model(self, path: str):
        """加载模型"""
        checkpoint = torch.load(path, map_location=self.device)
        self.model.load_state_dict(checkpoint["model_state_dict"])
        logger.info(f"模型已从: {path} 加载")


class DeepFMRanker:
    """DeepFM 排序器封装 - 支持训练和推理"""

    # 默认值（与 config.yaml 一致，config.yaml 优先）
    DEFAULT_SPARSE_DIMS = [10, 10, 14, 10, 100, 50, 10, 10]
    DEFAULT_DENSE_DIM = 10  # user_dense(6) + item_dense(2) + cross(2)
    DEFAULT_HIDDEN_LAYERS = [128, 64, 32]
    DEFAULT_DROPOUT = 0.2
    DEFAULT_EMBEDDING_DIM = 8
    DEFAULT_LEARNING_RATE = 0.001

    def __init__(self, model_path: Optional[str] = None, embedding_dim: int = None):
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model: Optional[DeepFM] = None
        self.trainer: Optional[DeepFMTrainer] = None
        self.feature_engine = None
        self.is_loaded = False
        self.model_path = model_path

        cfg = _load_config()
        self.cfg = cfg

        self.embedding_dim = embedding_dim if embedding_dim is not None else cfg.get("embedding_dim", self.DEFAULT_EMBEDDING_DIM)

        dims_cfg = cfg.get("sparse_dims")
        self.sparse_field_dims = dims_cfg if dims_cfg else self.DEFAULT_SPARSE_DIMS.copy()
        self.dense_dim = cfg.get("dense_dim", self.DEFAULT_DENSE_DIM)
        self.hidden_layers = cfg.get("hidden_layers", self.DEFAULT_HIDDEN_LAYERS)
        self.dropout_rate = cfg.get("dropout_rate", self.DEFAULT_DROPOUT)
        self.learning_rate = cfg.get("learning_rate", self.DEFAULT_LEARNING_RATE)

        logger.info(f"DeepFMRanker 配置: embedding_dim={self.embedding_dim}, "
                   f"sparse_dims={self.sparse_field_dims}, hidden_layers={self.hidden_layers}")

    def build_model(self, hidden_layers: List[int] = None) -> DeepFM:
        """构建 DeepFM 模型"""
        layers = hidden_layers if hidden_layers is not None else self.hidden_layers
        model = DeepFM(
            sparse_field_dims=self.sparse_field_dims,
            dense_dim=self.dense_dim,
            embedding_dim=self.embedding_dim,
            hidden_layers=layers,
            dropout_rate=self.dropout_rate
        )
        return model

    def load_model(self, model_path: Optional[str] = None) -> bool:
        """加载预训练模型或初始化新模型"""
        try:
            path = model_path or self.model_path

            if path and os.path.exists(path):
                # 加载预训练模型
                checkpoint = torch.load(path, map_location=self.device)
                self.sparse_field_dims = checkpoint.get("sparse_field_dims", self.DEFAULT_SPARSE_DIMS)
                self.dense_dim = checkpoint.get("dense_dim", self.DEFAULT_DENSE_DIM)
                self.embedding_dim = checkpoint.get("embedding_dim", 8)

                self.model = self.build_model()
                self.model.load_state_dict(checkpoint["model_state_dict"])
                logger.info(f"从 {path} 加载预训练模型")
            else:
                # 初始化新模型
                logger.info("初始化新的 DeepFM 模型")
                self.model = self.build_model()

            self.model.to(self.device)
            self.model.eval()
            self.is_loaded = True

            # 初始化训练器（使用配置中的 learning_rate 和 weight_decay）
            wd = self.cfg.get("weight_decay", 1e-5)
            self.trainer = DeepFMTrainer(
                self.model,
                device=str(self.device),
                learning_rate=self.learning_rate,
                weight_decay=wd
            )

            # 初始化特征引擎
            from .features import FeatureEngine
            self.feature_engine = FeatureEngine()

            logger.info(f"模型已加载到设备: {self.device}")
            return True

        except Exception as e:
            logger.error(f"模型加载失败: {e}")
            return False

    def train(self, train_data: Dict, val_data: Optional[Dict] = None,
              epochs: int = 10, batch_size: int = 256,
              save_path: Optional[str] = None) -> Dict[str, List[float]]:
        """
        训练模型

        Args:
            train_data: 训练数据 {user_features, item_features, labels}
            val_data: 验证数据（可选）
            epochs: 训练轮数
            batch_size: 批次大小
            save_path: 模型保存路径

        Returns:
            训练历史 {train_loss, val_loss, train_metrics, val_metrics}
        """
        if not self.model:
            self.load_model()

        # 创建数据加载器
        train_dataset = ClickDataset(
            user_features=train_data["user_features"],
            item_features=train_data["item_features"],
            labels=train_data["labels"]
        )
        train_loader = DataLoader(train_dataset, batch_size=batch_size, shuffle=True)

        val_loader = None
        if val_data:
            val_dataset = ClickDataset(
                user_features=val_data["user_features"],
                item_features=val_data["item_features"],
                labels=val_data["labels"]
            )
            val_loader = DataLoader(val_dataset, batch_size=batch_size)

        # 训练循环
        history = {
            "train_loss": [],
            "train_metrics": [],
        }
        if val_loader:
            history["val_loss"] = []
            history["val_metrics"] = []

        best_val_auc = 0.0  # 最佳 AUC 用于早停/保存模型

        for epoch in range(epochs):
            # 训练
            train_loss = self.trainer.train_epoch(train_loader)
            train_metrics = self.trainer.evaluate(train_loader)

            history["train_loss"].append(train_loss)
            history["train_metrics"].append(train_metrics)

            logger.info(f"Epoch {epoch+1}/{epochs} | "
                        f"Train Loss={train_loss:.4f} LogLoss={train_metrics.get('logloss', 0):.4f} "
                        f"AUC={train_metrics.get('auc', 0):.4f} "
                        f"Acc={train_metrics['accuracy']:.4f} F1={train_metrics['f1']:.4f} "
                        f"| pos_rate={train_metrics.get('positive_rate', 0):.2%}")

            # 验证
            if val_loader:
                val_metrics = self.trainer.evaluate(val_loader)
                history["val_loss"].append(val_metrics["loss"])
                history["val_metrics"].append(val_metrics)

                logger.info(f"           Val     Loss={val_metrics['loss']:.4f} "
                            f"LogLoss={val_metrics.get('logloss', 0):.4f} "
                            f"AUC={val_metrics.get('auc', 0):.4f} "
                            f"Acc={val_metrics['accuracy']:.4f} F1={val_metrics['f1']:.4f} "
                            f"| pos_rate={val_metrics.get('positive_rate', 0):.2%}")

                # 以 AUC 为标准保存最佳模型（AUC 是 CTR 预估最核心指标）
                val_auc = val_metrics.get("auc", 0.0)
                if val_auc > best_val_auc and save_path:
                    best_val_auc = val_auc
                    self.trainer.save_model(save_path)
                    logger.info(f"  ★ 保存新最佳模型 (AUC={best_val_auc:.4f})")

        # 如果没有验证集，保存最后一个模型
        if not val_loader and save_path:
            self.trainer.save_model(save_path)

        return history

    def rank(self, user_features: Dict, item_features: Dict) -> List[float]:
        """
        对候选商品进行排序（推理）

        Args:
            user_features: 用户特征字典
            item_features: {item_id: item_feature_dict} 商品特征字典

        Returns:
            scores: 各商品的 CTR 预测分数
        """
        if not self.model or not self.is_loaded:
            logger.warning("模型未加载，返回默认分数")
            return [0.5] * len(item_features)

        self.model.eval()

        item_ids = list(item_features.keys())

        with torch.no_grad():
            # 批量处理 - 按照 ClickDataset 的格式构建特征
            batch_sparse = []
            batch_dense = []

            for item_id in item_ids:
                item_feat = item_features[item_id]

                # ========== 处理可能为列表的用户偏好特征 ==========
                prefer_category = user_features.get("prefer_category")
                if isinstance(prefer_category, list):
                    prefer_category = prefer_category[0] if prefer_category else 0
                elif prefer_category is None:
                    prefer_category = 0
                    
                prefer_brand = user_features.get("prefer_brand")
                if isinstance(prefer_brand, list):
                    prefer_brand = prefer_brand[0] if prefer_brand else 0
                elif prefer_brand is None:
                    prefer_brand = 0

                # 稀疏特征 - 8个: 与 ClickDataset 一致
                # 用户部分 (4个)
                user_view_bucket = min(int(user_features.get("view_1d", 0) or 0) // 10, 9)
                user_click_bucket = min(int(user_features.get("click_1d", 0) or 0) // 5, 9)
                user_active_bucket = min(int(user_features.get("last_active_hours", 24) or 24) // 24, 13)
                user_prefer_cat_idx = int(prefer_category) % 10
                
                # 商品部分 (4个) - 必须应用与训练时相同的 bucketing 避免 embedding 越界
                item_cat = int(item_feat.get("category_id", 0) or 0) % 100
                item_brand = int(item_feat.get("brand_id", 0) or 0) % 50
                item_price = min(int(item_feat.get("price_bucket", 0) or 0), 9)
                item_sales = min(int(item_feat.get("sales_bucket", 0) or 0), 9)
                
                sparse = [
                    user_view_bucket, user_click_bucket, user_active_bucket, user_prefer_cat_idx,
                    item_cat, item_brand, item_price, item_sales
                ]

                # 密集特征 - 10个
                dense = [
                    float(user_features.get("view_1d", 0) or 0) / 100.0,
                    float(user_features.get("click_1d", 0) or 0) / 50.0,
                    float(user_features.get("cart_1d", 0) or 0) / 20.0,
                    float(user_features.get("buy_1d", 0) or 0) / 10.0,
                    float(user_features.get("view_7d", 0) or 0) / 500.0,
                    float(user_features.get("last_active_hours", 24) or 24) / 720.0,
                    float(item_feat.get("hot_score", 0) or 0) / 10000.0,
                    float(item_feat.get("price_ratio", 0.5) or 0.5),
                    1.0 if prefer_category == item_feat.get("category_id") else 0.0,
                    1.0 if prefer_brand == item_feat.get("brand_id") else 0.0,
                ]

                batch_sparse.append(sparse)
                batch_dense.append(dense)

            # 转换为张量
            sparse_tensor = torch.tensor(batch_sparse, dtype=torch.long).to(self.device)
            dense_tensor = torch.tensor(batch_dense, dtype=torch.float32).to(self.device)

            # 模型推理
            scores = self.model(sparse_tensor, dense_tensor)

            return scores.cpu().numpy().tolist()

    def incremental_update(self, user_features: List[Dict], item_features: List[Dict],
                          labels: List[int], learning_rate: float = None,
                          epochs: int = 3, minibatch_size: int = 64
                          ) -> Tuple[int, Optional[float], Optional[float], Optional[str]]:
        """
        增量更新模型（小步 SGD）

        Args:
            user_features: 用户特征列表
            item_features: 商品特征列表
            labels: 标签列表 (0/1)
            learning_rate: 学习率（默认使用配置的 1/10）
            epochs: 增量更新的 epoch 数
            minibatch_size: 小批量大小

        Returns:
            (updated_count, loss_before, loss_after, model_version)
        """
        if not self.model or not self.is_loaded:
            logger.warning("模型未加载，无法执行增量更新")
            return 0, None, None, None

        if len(user_features) != len(item_features) or len(user_features) != len(labels):
            logger.error("特征与标签长度不匹配")
            return 0, None, None, None

        lr = learning_rate if learning_rate is not None else self.learning_rate * 0.1

        self.model.train()

        # 创建增量数据集
        dataset = ClickDataset(user_features, item_features, labels)
        dataloader = DataLoader(dataset, batch_size=minibatch_size, shuffle=True)

        # 记录更新前的验证损失
        loss_before = None
        if len(dataset) > minibatch_size:
            val_ds = ClickDataset(
                user_features[minibatch_size:],
                item_features[minibatch_size:],
                labels[minibatch_size:]
            )
            val_dl = DataLoader(val_ds, batch_size=minibatch_size)
            metrics = self.trainer.evaluate(val_dl)
            loss_before = metrics["loss"]

        # 增量训练循环（使用较小的学习率）
        for epoch in range(epochs):
            epoch_loss = 0.0
            batches = 0
            for sparse_feat, dense_feat, lbl in dataloader:
                sparse_feat = sparse_feat.to(self.device)
                dense_feat = dense_feat.to(self.device)
                lbl = lbl.to(self.device)

                self.trainer.optimizer.zero_grad()
                outputs = self.model(sparse_feat, dense_feat)
                loss = self.trainer.criterion(outputs, lbl)
                loss.backward()
                self.trainer.optimizer.step()

                epoch_loss += loss.item()
                batches += 1

            avg_loss = epoch_loss / batches if batches > 0 else 0.0
            logger.info(f"增量 epoch {epoch+1}/{epochs}, avg_loss={avg_loss:.4f}")

        self.model.eval()

        # 计算更新后损失
        loss_after = None
        new_version = None
        if loss_before is not None:
            val_ds = ClickDataset(
                user_features[minibatch_size:],
                item_features[minibatch_size:],
                labels[minibatch_size:]
            )
            if len(val_ds) > 0:
                val_dl = DataLoader(val_ds, batch_size=minibatch_size)
                metrics = self.trainer.evaluate(val_dl)
                loss_after = metrics["loss"]
                new_version = f"incremental-{len(user_features)}-{int(time.time())}"

        logger.info(f"增量更新完成: samples={len(user_features)}, loss_before={loss_before}, loss_after={loss_after}")
        return len(user_features), loss_before, loss_after, new_version

    def predict_single(self, user_features: Dict, item_features: Dict) -> float:
        """单次预测"""
        scores = self.rank(user_features, {item_features.get("item_id", "0"): item_features})
        return scores[0] if scores else 0.5


# 全局模型实例
_ranker: Optional[DeepFMRanker] = None


def get_ranker() -> DeepFMRanker:
    """获取全局排序器实例"""
    global _ranker
    if _ranker is None:
        _ranker = DeepFMRanker()
        _ranker.load_model()
    return _ranker
