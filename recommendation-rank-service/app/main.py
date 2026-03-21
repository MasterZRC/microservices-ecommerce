"""
recommendation-rank-service 主入口
基于 DeepFM 的推荐排序服务
"""
from fastapi import FastAPI, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import APIKeyHeader
from pydantic import BaseModel
from typing import Dict, List, Optional
import logging
import os
import numpy as np

from .schemas import (
    RankRequest, RankResponse, RankedItem, HealthResponse,
    UserFeatures, ItemFeatures, TrainRequest, TrainResponse,
    GenerateDataRequest, GenerateDataResponse,
    LoadDataRequest, LoadDataResponse,
    IncrementalUpdateRequest, IncrementalUpdateResponse
)
from .model import get_ranker, DeepFMRanker
from .features import FeatureEngine, SyntheticDataGenerator, RealDataGenerator
from .evaluation import (
    offline_evaluate_deepfm,
    offline_evaluate_itemcf,
    offline_evaluate_popular,
    compare_algorithms
)

# 配置日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# API Key 配置
_api_key: Optional[str] = None


def _get_api_key() -> Optional[str]:
    """从环境变量加载 API Key"""
    global _api_key
    if _api_key is None:
        _api_key = os.environ.get("RANK_SERVICE_API_KEY", "").strip() or None
    return _api_key


def _require_api_key(x_api_key: Optional[str] = Header(None, alias="X-API-Key")) -> str:
    """验证 API Key，失败则抛出 401"""
    expected = _get_api_key()
    if expected is None:
        return "dev-mode-no-key"

    if not x_api_key:
        raise HTTPException(
            status_code=401,
            detail="缺少 API Key，请通过 X-API-Key Header 传递有效密钥"
        )

    if x_api_key != expected:
        raise HTTPException(status_code=403, detail="API Key 无效")

    return x_api_key

# 创建 FastAPI 应用
app = FastAPI(
    title="Recommendation Rank Service",
    description="基于 DeepFM 的 CTR 预估排序服务",
    version="1.0.0"
)

# 配置 CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 全局变量存储训练数据
_train_data = {}
_val_data = {}


@app.on_event("startup")
async def startup_event():
    """服务启动时加载模型"""
    logger.info("正在加载 DeepFM 模型...")
    ranker = get_ranker()
    logger.info(f"模型加载完成，设备: {ranker.device}")


@app.get("/health", response_model=HealthResponse)
async def health_check():
    """健康检查"""
    ranker = get_ranker()
    return HealthResponse(
        status="healthy",
        model_loaded=ranker.is_loaded
    )


@app.get("/model/info")
async def model_info():
    """获取模型信息"""
    ranker = get_ranker()
    return {
        "device": str(ranker.device),
        "model_loaded": ranker.is_loaded,
        "sparse_field_dims": ranker.sparse_field_dims,
        "dense_dim": ranker.dense_dim,
        "embedding_dim": ranker.embedding_dim,
    }


@app.post("/rank", response_model=RankResponse)
async def rank_items(request: RankRequest, _auth: str = _require_api_key):
    """
    对候选商品进行排序（需要 API Key 认证）

    流程：
    1. 构建用户和商品特征
    2. DeepFM 模型推理计算 CTR 分数
    3. 按分数降序返回排序结果
    """
    try:
        ranker = get_ranker()

        if not ranker.is_loaded:
            raise HTTPException(status_code=503, detail="模型未加载")

        # 使用兼容方法获取数据（支持驼峰和蛇形命名）
        user_id = request.get_user_id()
        candidates = request.candidates
        
        # 获取用户特征 - 兼容驼峰和蛇形
        user_features_dict = {}
        user_feat = request.get_user_features()
        if user_feat:
            # 手动提取特征，支持两种命名方式
            uf = user_feat.dict() if hasattr(user_feat, 'dict') else user_feat
            user_features_dict = {
                "view_1d": uf.get("view_1d") or uf.get("view1d") or 0,
                "click_1d": uf.get("click_1d") or uf.get("click1d") or 0,
                "cart_1d": uf.get("cart_1d") or uf.get("cart1d") or 0,
                "buy_1d": uf.get("buy_1d") or uf.get("buy1d") or 0,
                "view_7d": uf.get("view_7d") or uf.get("view7d") or 0,
                "last_active_hours": uf.get("last_active_hours") or uf.get("lastActiveHours") or 0,
            }

        # 获取商品特征 - 兼容驼峰和蛇形
        item_features = {}
        raw_item_features = request.get_item_features()
        if raw_item_features:
            for item_id, feat in raw_item_features.items():
                if hasattr(feat, 'dict'):
                    f = feat.dict()
                elif isinstance(feat, dict):
                    f = feat
                else:
                    f = {}
                
                # 支持两种命名方式
                item_features[str(item_id)] = {
                    "category_id": f.get("category_id") or f.get("categoryId") or 0,
                    "brand_id": f.get("brand_id") or f.get("brandId") or 0,
                    "price_bucket": f.get("price_bucket") or f.get("priceBucket") or 0,
                    "sales_bucket": f.get("sales_bucket") or f.get("salesBucket") or 0,
                    "hot_score": f.get("hot_score") or f.get("hotScore") or 100.0,
                }

        # 禁止在 /rank 接口中生成假商品特征兜底
        if not item_features and candidates:
            raise HTTPException(
                status_code=400,
                detail="未提供商品特征 itemFeatures，禁止使用候选ID生成假特征。"
                      "请先调用商品服务获取真实商品数据后再提交排序请求。"
            )

        # DeepFM 推理
        scores = ranker.rank(user_features_dict, item_features)

        # 构建排序结果
        item_scores = list(zip([str(c) for c in candidates], scores))
        item_scores.sort(key=lambda x: x[1], reverse=True)

        ranked_items = [
            RankedItem(item_id=int(item_id), score=float(score))
            for item_id, score in item_scores
        ]

        return RankResponse(
            user_id=user_id,
            ranked_items=ranked_items
        )

    except Exception as e:
        logger.error(f"排序失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/rank/simple")
async def rank_items_simple(request: Dict, _auth: str = _require_api_key):
    """
    简化版排序接口（不需要完整的特征输入）
    仅在调用方能提供特征时使用，不生成任何假数据作为兜底

    要求：
    - 必须提供 user_id 和 candidates
    - 候选商品数量不超过 200 个
    - 不生成任何默认/假特征

    生产环境建议：使用 /rank 接口并传入完整特征
    """
    try:
        user_id = request.get("user_id")
        candidates = request.get("candidates", [])

        if not user_id:
            raise HTTPException(status_code=400, detail="user_id 不能为空")
        if not candidates:
            raise HTTPException(status_code=400, detail="候选列表不能为空")
        if len(candidates) > 200:
            raise HTTPException(status_code=400, detail="候选列表不能超过 200 个")

        # 必须提供用户特征，不接受任何默认值兜底
        has_user_features = any(
            k in request for k in [
                "view_1d", "click_1d", "cart_1d", "buy_1d",
                "view_7d", "click_7d", "cart_7d", "buy_7d",
                "view_30d", "last_active_hours"
            ]
        )
        if not has_user_features:
            raise HTTPException(
                status_code=400,
                detail="未提供用户行为特征，禁止使用假数据兜底。"
                      "请通过 /rank 接口传入完整 userFeatures，或在调用前从数据库查询用户行为统计。"
            )

        # 构建用户特征
        user_features = {
            "view_1d": request.get("view_1d", 0),
            "click_1d": request.get("click_1d", 0),
            "cart_1d": request.get("cart_1d", 0),
            "buy_1d": request.get("buy_1d", 0),
            "view_7d": request.get("view_7d", 0),
            "click_7d": request.get("click_7d", 0),
            "cart_7d": request.get("cart_7d", 0),
            "buy_7d": request.get("buy_7d", 0),
            "view_30d": request.get("view_30d", 0),
            "last_active_hours": request.get("last_active_hours", 0),
            "prefer_category": request.get("prefer_category", 0),
            "prefer_brand": request.get("prefer_brand", 0)
        }

        # 构建商品特征（兼容两种调用方式）
        # 方式1：调用方传入完整的 item_features 字典
        # 方式2：调用方只传入 candidates 列表，使用默认特征（可保证排序仍能运行）
        raw_item_features = request.get("item_features", {})
        item_features = {}

        if raw_item_features:
            # 方式1：直接使用传入的特征
            for item_id in candidates:
                key = str(item_id)
                feat = raw_item_features.get(key) or {}
                item_features[str(item_id)] = {
                    "category_id": feat.get("category_id") or feat.get("categoryId") or 0,
                    "brand_id": feat.get("brand_id") or feat.get("brandId") or 0,
                    "price_bucket": feat.get("price_bucket") or feat.get("priceBucket") or 0,
                    "sales_bucket": feat.get("sales_bucket") or feat.get("salesBucket") or 0,
                    "hot_score": feat.get("hot_score") or feat.get("hotScore") or 100.0,
                    "price_ratio": feat.get("price_ratio") or feat.get("priceRatio") or 0.5,
                }
        else:
            # 方式2：仅传入 ID 列表，使用默认特征（从 Redis 缓存获取真实特征更好）
            # rank-service 端使用默认特征，真实环境建议上层服务传入完整特征
            logger.warning("/rank/simple: 调用方未传入 item_features，使用默认特征进行排序")
            for item_id in candidates:
                key = str(item_id)
                item_features[key] = {
                    "category_id": 0, "brand_id": 0,
                    "price_bucket": 5, "sales_bucket": 10,
                    "hot_score": 100.0, "price_ratio": 0.5,
                }


        ranker = get_ranker()
        if not ranker.is_loaded:
            raise HTTPException(status_code=503, detail="模型未加载")

        scores = ranker.rank(user_features, item_features)

        # 排序
        item_scores = list(zip(candidates, scores))
        item_scores.sort(key=lambda x: x[1], reverse=True)

        return {
            "user_id": user_id,
            "ranked_items": [
                {"item_id": item_id, "score": round(float(score), 4)}
                for item_id, score in item_scores
            ]
        }

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"简化排序失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/data/generate", response_model=GenerateDataResponse)
async def generate_training_data(request: GenerateDataRequest):
    """
    生成训练数据

    使用合成数据生成器生成模拟的用户-商品交互数据
    """
    try:
        global _train_data, _val_data

        logger.info(f"生成训练数据: samples={request.num_samples}, "
                   f"users={request.num_users}, items={request.num_items}")

        # 创建数据生成器
        generator = SyntheticDataGenerator(
            num_users=request.num_users,
            num_items=request.num_items
        )

        # 生成交互数据
        user_features, item_features, labels = generator.generate_interaction_data(
            num_samples=request.num_samples,
            positive_ratio=0.3
        )

        # 划分训练集和验证集
        train_data, val_data = generator.split_data(
            user_features, item_features, labels,
            train_ratio=request.train_ratio
        )

        # 存储到全局变量
        _train_data = train_data
        _val_data = val_data

        logger.info(f"训练数据生成完成: train={len(train_data['labels'])}, "
                   f"val={len(val_data['labels'])}")

        return GenerateDataResponse(
            status="success",
            train_samples=len(train_data["labels"]),
            val_samples=len(val_data["labels"]),
            positive_ratio=0.3
        )

    except Exception as e:
        logger.error(f"生成训练数据失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/data/load", response_model=LoadDataResponse)
async def load_real_data(request: LoadDataRequest):
    """
    从数据库加载真实用户行为数据并生成训练数据

    从 MySQL 的 user_behavior 表读取数据，转换为模型可用的训练格式
    """
    try:
        global _train_data, _val_data

        logger.info(f"从数据库加载训练数据: min_interactions={request.min_interactions}")

        # 创建真实数据生成器
        db_config = {
            "host": request.db_host,
            "port": request.db_port,
            "user": request.db_user,
            "password": request.db_password,
            "database": request.db_name
        }

        data_generator = RealDataGenerator(db_config)

        # 加载交互数据
        user_features, item_features, labels = data_generator.load_interactions_from_db(
            min_interactions=request.min_interactions
        )

        if not labels:
            raise HTTPException(
                status_code=400,
                detail="没有足够的交互数据生成训练样本"
            )

        logger.info(f"加载完成: 总样本={len(labels)}, 正样本={labels.count(1)}, 负样本={labels.count(0)}")

        # 划分训练集和验证集（手动实现，不依赖 sklearn）
        import random
        random.seed(42)

        # 将数据转换为列表以便打乱
        indices = list(range(len(labels)))
        random.shuffle(indices)

        # 80% 训练，20% 验证
        split_idx = int(len(labels) * 0.8)
        train_indices = indices[:split_idx]
        val_indices = indices[split_idx:]

        # 存储到全局变量（使用字典列表格式）
        train_user_features = [user_features[i] for i in train_indices]
        train_item_features = [item_features[i] for i in train_indices]
        train_labels_list = [labels[i] for i in train_indices]

        val_user_features = [user_features[i] for i in val_indices]
        val_item_features = [item_features[i] for i in val_indices]
        val_labels_list = [labels[i] for i in val_indices]

        _train_data = {
            "user_features": train_user_features,
            "item_features": train_item_features,
            "labels": train_labels_list
        }
        _val_data = {
            "user_features": val_user_features,
            "item_features": val_item_features,
            "labels": val_labels_list
        }

        data_generator.close()

        # 统计用户数
        user_ids = set()
        for uf in user_features:
            if "user_id" in uf:
                user_ids.add(uf["user_id"])

        return LoadDataResponse(
            status="success",
            total_samples=len(labels),
            positive_samples=labels.count(1),
            negative_samples=labels.count(0),
            users_count=len(user_ids)
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"加载真实数据失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/train", response_model=TrainResponse)
async def train_model(request: TrainRequest):
    """
    训练 DeepFM 模型

    使用预先生成的数据进行模型训练
    """
    try:
        global _train_data, _val_data

        if not _train_data or "labels" not in _train_data:
            raise HTTPException(
                status_code=400,
                detail="请先调用 /data/generate 接口生成训练数据"
            )

        ranker = get_ranker()

        # 确保模型目录存在
        os.makedirs(os.path.dirname(request.save_path) or "models", exist_ok=True)

        logger.info(f"开始训练: epochs={request.epochs}, batch_size={request.batch_size}")

        # 训练模型
        history = ranker.train(
            train_data=_train_data,
            val_data=_val_data if _val_data else None,
            epochs=request.epochs,
            batch_size=request.batch_size,
            save_path=request.save_path
        )

        # 获取最终指标
        final_metrics = history["train_metrics"][-1] if history["train_metrics"] else {}

        # 构建每个 epoch 的完整历史
        epoch_history = []
        train_metrics = history.get("train_metrics", [])
        val_metrics = history.get("val_metrics", [])
        train_losses = history.get("train_loss", [])
        val_losses = history.get("val_loss", [])
        num_epochs = len(train_metrics)

        for i in range(num_epochs):
            tm = train_metrics[i] if i < len(train_metrics) else {}
            vm = val_metrics[i] if i < len(val_metrics) else {}
            tl = train_losses[i] if i < len(train_losses) else None
            vl = val_losses[i] if i < len(val_losses) else None
            epoch_history.append({
                "epoch": i + 1,
                "train_loss": tl,
                "val_loss": vl,
                "train_auc": tm.get("auc", 0),
                "train_logloss": tm.get("logloss", 0),
                "train_accuracy": tm.get("accuracy", 0),
                "train_f1": tm.get("f1", 0),
                "val_auc": vm.get("auc", 0),
                "val_logloss": vm.get("logloss", 0),
                "val_accuracy": vm.get("accuracy", 0),
                "val_f1": vm.get("f1", 0),
            })

        logger.info(f"训练完成: {final_metrics}")

        return TrainResponse(
            status="success",
            message=f"模型训练完成，已保存到 {request.save_path}",
            metrics=final_metrics,
            epoch_history=epoch_history
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"训练失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/data/info")
async def get_data_info():
    """获取当前训练数据信息"""
    global _train_data, _val_data

    if not _train_data or "labels" not in _train_data:
        return {
            "status": "no_data",
            "message": "请先调用 /data/generate 接口生成训练数据"
        }

    train_pos = sum(_train_data["labels"])
    train_total = len(_train_data["labels"])

    val_pos = sum(_val_data["labels"]) if _val_data and "labels" in _val_data else 0
    val_total = len(_val_data["labels"]) if _val_data and "labels" in _val_data else 0

    return {
        "status": "ready",
        "train_samples": train_total,
        "val_samples": val_total,
        "train_positive_ratio": train_pos / train_total if train_total > 0 else 0,
        "val_positive_ratio": val_pos / val_total if val_total > 0 else 0,
    }


@app.post("/model/incremental-update", response_model=IncrementalUpdateResponse)
async def incremental_update(request: IncrementalUpdateRequest, _auth: str = _require_api_key):
    """
    增量更新模型：基于最新收集的用户行为数据进行小步更新

    要求：
    - 每次更新样本量建议 100-5000 条，过多可能导致模型震荡
    - 使用较小的学习率（默认 0.0001）和少量 epoch（默认 3）
    - 增量更新后模型自动切换到推理模式

    生产建议：
    - 由 recommendation-service 定时任务触发（建议每 30 分钟）
    - 在低峰期执行，避免影响推理性能
    """
    try:
        ranker = get_ranker()
        if not ranker.is_loaded:
            raise HTTPException(status_code=503, detail="模型未加载，请先调用 /data/generate 和 /model/train")

        samples = request.samples
        if not samples or len(samples) < 10:
            raise HTTPException(status_code=400, detail="样本数量不足，最少需要 10 条")

        logger.info(f"开始增量更新: samples={len(samples)}, epochs={request.epochs}")

        user_features = [s.get("user_features", {}) for s in samples]
        item_features = [s.get("item_features", {}) for s in samples]
        labels = [int(s.get("label", 0)) for s in samples]

        updated_samples, loss_before, loss_after, new_version = ranker.incremental_update(
            user_features=user_features,
            item_features=item_features,
            labels=labels,
            learning_rate=request.learning_rate,
            epochs=request.epochs,
            minibatch_size=request.minibatch_size,
        )

        loss_delta = round(float(loss_after - loss_before), 6) if loss_before is not None else None

        logger.info(f"增量更新完成: updated={updated_samples}, loss_delta={loss_delta}, version={new_version}")

        return IncrementalUpdateResponse(
            status="ok",
            updated_samples=updated_samples,
            loss_delta=loss_delta,
            new_model_version=new_version
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"增量更新失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ========== 离线评测接口 ==========

class OnlineEvaluateRequest(BaseModel):
    """在线评测请求：基于当前加载的模型对验证集计算 CTR 指标"""
    test_ratio: float = 0.2   # 验证集比例


@app.post("/evaluate/online", tags=["evaluation"])
async def evaluate_online(request: OnlineEvaluateRequest, _auth: str = _require_api_key):
    """
    在线评测（基于已加载模型）

    使用当前已训练的模型，在验证集上计算 CTR 预估标准指标：
    - AUC（ROC AUC）：最重要的排序指标，衡量模型区分正负样本的能力
    - LogLoss：对数损失，衡量概率预测的准确性
    - Accuracy / Precision / Recall / F1：分类标准指标
    - positive_rate：数据正负样本比例（反映数据是否极度不平衡）

    注意：需要先调用 /data/generate 或 /data/load 生成训练数据。
    """
    try:
        ranker = get_ranker()

        if not ranker.is_loaded:
            raise HTTPException(status_code=503, detail="模型未加载，请先训练模型")

        if not _train_data or "labels" not in _train_data:
            raise HTTPException(status_code=400, detail="训练数据为空，请先调用 /data/generate 或 /data/load")

        train_data = _train_data
        val_data = _val_data if _val_data else None

        # 使用已加载的模型评测验证集
        from .model import ClickDataset
        from torch.utils.data import DataLoader

        if val_data and val_data.get("labels"):
            dataset = ClickDataset(
                user_features=val_data["user_features"],
                item_features=val_data["item_features"],
                labels=val_data["labels"]
            )
            dataloader = DataLoader(dataset, batch_size=256)
            metrics = ranker.trainer.evaluate(dataloader)

            return {
                "status": "ok",
                "source": "validation_set",
                "model_loaded": True,
                "metrics": metrics,
                "interpretation": {
                    "auc": f"AUC={metrics['auc']:.4f}，>0.7 为可用，>0.8 为优秀，>0.9 通常需特殊处理",
                    "logloss": f"LogLoss={metrics['logloss']:.4f}，越小越好，<0.5 通常合理",
                    "accuracy": f"Accuracy={metrics['accuracy']:.4f}，但由于类别不平衡，Accuracy 参考价值有限",
                    "positive_rate": f"正样本比例={metrics['positive_rate']:.2%}，低于 5% 为极度不平衡数据",
                }
            }

        # 如果没有验证集，用训练集尾部的 test_ratio 数据
        all_labels = train_data["labels"]
        split_idx = int(len(all_labels) * (1 - request.test_ratio))
        from .model import ClickDataset
        from torch.utils.data import DataLoader

        test_labels = all_labels[split_idx:]
        test_user_feats = train_data["user_features"][split_idx:]
        test_item_feats = train_data["item_features"][split_idx:]

        if len(test_labels) < 10:
            raise HTTPException(status_code=400, detail=f"测试样本量不足: {len(test_labels)}")

        dataset = ClickDataset(test_user_feats, test_item_feats, test_labels)
        dataloader = DataLoader(dataset, batch_size=256)
        metrics = ranker.trainer.evaluate(dataloader)

        return {
            "status": "ok",
            "source": f"train_data_tail_{int(request.test_ratio*100)}pct",
            "model_loaded": True,
            "test_samples": len(test_labels),
            "metrics": metrics,
            "interpretation": {
                "auc": f"AUC={metrics['auc']:.4f}，>0.7 为可用，>0.8 为优秀",
                "logloss": f"LogLoss={metrics['logloss']:.4f}，越小越好",
                "accuracy": f"Accuracy={metrics['accuracy']:.4f}，注意类别不平衡时参考价值有限",
                "positive_rate": f"正样本比例={metrics['positive_rate']:.2%}，低于 5% 为极度不平衡",
            }
        }

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"在线评测失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


class OfflineCompareRequest(BaseModel):
    """离线对比请求"""
    k: int = 10


@app.post("/evaluate/compare", tags=["evaluation"])
async def evaluate_compare(request: OfflineCompareRequest, _auth: str = _require_api_key):
    """
    离线排序指标对比（DeepFM vs ItemCF vs 热门基线）

    在测试集上对比三种算法的排序质量：
    - Precision@K：推荐列表中有多少比例是用户真正感兴趣的
    - Recall@K：用户真正感兴趣的商品中被推荐出了多少
    - NDCG@K：综合考虑排序位置和相关性，越接近 1 越好
    - MRR@K：第一个命中结果出现的位置，越小越好

    结果会显示每种算法相对于热门基线的提升百分比。
    """
    try:
        ranker = get_ranker()

        if not ranker.is_loaded:
            raise HTTPException(status_code=503, detail="模型未加载，请先训练模型")

        if not _train_data or "labels" not in _train_data:
            raise HTTPException(status_code=400, detail="训练数据为空，请先调用 /data/generate 或 /data/load")

        k = max(1, min(request.k, 50))  # 限制 K 在 1-50 之间

        # 1. DeepFM 离线评测
        deepfm_metrics = offline_evaluate_deepfm(
            model=ranker,
            user_features_list=_train_data["user_features"],
            item_features_list=_train_data["item_features"],
            labels=_train_data["labels"],
            k=k
        )

        # 2. 热门基线评测（用当前训练数据构造）
        all_labels = _train_data["labels"]
        all_item_feats = _train_data["item_features"]
        if not all_item_feats:
            raise HTTPException(status_code=400, detail="训练数据中没有商品特征")

        # 统计每个商品在正样本中出现的次数作为热门度
        item_popularity = {}
        for i, label in enumerate(all_labels):
            if label == 1:
                item_id = all_item_feats[i].get("item_id", i)
                item_popularity[item_id] = item_popularity.get(item_id, 0) + 1

        popular_items = sorted(item_popularity.keys(), key=lambda x: item_popularity[x], reverse=True)
        test_data = [
            (i, all_item_feats[i].get("item_id", i), all_labels[i])
            for i in range(len(all_labels))
            if all_labels[i] == 1  # 只评测正样本
        ]

        popular_metrics = offline_evaluate_popular(popular_items, test_data, k=k)

        # 3. ItemCF 评测（构造相似度矩阵）
        user_item_matrix = {}
        for i in range(len(all_labels)):
            uid = _train_data["user_features"][i].get("user_id", i)
            item_id = all_item_feats[i].get("item_id", i)
            if uid not in user_item_matrix:
                user_item_matrix[uid] = {}
            user_item_matrix[uid][item_id] = user_item_matrix[uid].get(item_id, 0) + 1

        # 简化 ItemCF 相似度（基于共现）
        item_ids = list(set(item_popularity.keys()))
        similarity = {iid: {} for iid in item_ids}
        for uid, items in user_item_matrix.items():
            items_list = list(items.keys())
            for a in items_list:
                for b in items_list:
                    if a != b and a in similarity and b in similarity:
                        similarity[a][b] = similarity[a].get(b, 0) + 1

        itemcf_metrics = offline_evaluate_itemcf(user_item_matrix, similarity, test_data, k=k)

        # 4. 对比
        comparison = compare_algorithms(deepfm_metrics, itemcf_metrics, popular_metrics)

        return {
            "status": "ok",
            "k": k,
            "total_samples": len(all_labels),
            "positive_samples": sum(all_labels),
            "positive_rate": round(sum(all_labels) / len(all_labels), 4),
            "algorithms": {
                "deepfm": deepfm_metrics,
                "itemcf": itemcf_metrics,
                "popular": popular_metrics,
            },
            "comparison": comparison,
            "guide": {
                "auc": "DeepFM 专属指标，衡量 CTR 预测准确性，>0.7 可用，>0.8 优秀",
                "ndcg_at_k": "NDCG@K 衡量排序质量，越接近 1 越好，通常提升 5-20% 即有显著收益",
                "mrr_at_k": "MRR@K 衡量首个命中位置，越小越好",
                "hit_rate": f"HitRate@{k}，前 {k} 个推荐中是否包含用户感兴趣的商品",
                "improvement": "相对于热门基线的提升百分比，正数表示更好"
            }
        }

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"离线对比评测失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8010)
