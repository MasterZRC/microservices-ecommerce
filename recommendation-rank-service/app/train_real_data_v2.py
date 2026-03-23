"""
DeepFM Model Training Script - Optimized Version
Uses real data from MySQL database to train the model with better data balance
"""

import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.chdir(os.path.dirname(os.path.abspath(__file__)))

from app.model import DeepFMRanker
import pymysql
import logging
import random
import numpy as np
from datetime import datetime, timedelta
from torch.utils.data import DataLoader
from app.model import ClickDataset

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

DB_CONFIG = {
    "host": "localhost",
    "port": 3306,
    "user": "root",
    "password": "root123",
    "database": "ecommerce"
}

MODEL_PATH = "models/deepfm_real.pt"


def load_training_data():
    """Load and prepare training data with proper balance"""
    logger.info("[Step 1] Loading data from MySQL...")

    conn = pymysql.connect(
        host=DB_CONFIG["host"],
        port=DB_CONFIG["port"],
        user=DB_CONFIG["user"],
        password=DB_CONFIG["password"],
        database=DB_CONFIG["database"],
        cursorclass=pymysql.cursors.DictCursor
    )

    # Load products
    cursor = conn.cursor()
    cursor.execute("""
        SELECT id, category_id, brand, price, sales
        FROM product WHERE status = 1
    """)
    products = {p["id"]: p for p in cursor.fetchall()}

    max_sales = max((float(p.get("sales") or 0) for p in products.values()), default=1.0)
    max_price = max((float(p.get("price") or 0) for p in products.values()), default=1.0)

    def brand_to_id(brand):
        return hash(str(brand) or "") % 50 if brand else 0

    def price_bucket(price):
        p = float(price) if price else 0.0
        ratio = min(p / max_price, 1.0) if max_price > 0 else 0
        return min(int(ratio ** 0.5 * 10), 9)

    def sales_bucket(sales):
        s = float(sales) if sales else 0.0
        ratio = min(s / max_sales, 1.0) if max_sales > 0 else 0
        return min(int(ratio ** 0.5 * 10), 9)

    # Load all behaviors
    cursor.execute("""
        SELECT user_id, product_id, behavior_type, create_time, score
        FROM user_behavior ORDER BY user_id, create_time DESC
    """)
    behaviors = cursor.fetchall()
    cursor.close()
    conn.close()

    logger.info(f"Loaded {len(behaviors)} behaviors, {len(products)} products")

    # Group by user
    user_behaviors = {}
    for b in behaviors:
        uid = b["user_id"]
        if uid not in user_behaviors:
            user_behaviors[uid] = []
        user_behaviors[uid].append(b)

    # Build training samples
    user_features_list = []
    item_features_list = []
    labels = []

    now = datetime.now()
    one_day = timedelta(days=1)
    seven_days = timedelta(days=7)

    for user_id, user_acts in user_behaviors.items():
        if len(user_acts) < 3:
            continue

        # Build user features
        view_1d = click_1d = cart_1d = buy_1d = 0
        view_7d = click_7d = cart_7d = buy_7d = 0
        last_active = now

        for b in user_acts:
            bt = b["behavior_type"]
            weight = {"view": 1, "click": 2, "cart": 4, "favorite": 5, "buy": 8}.get(bt, 1)
            if b["create_time"] >= now - one_day:
                if bt == "view": view_1d += weight
                elif bt == "click": click_1d += weight
                elif bt == "cart": cart_1d += weight
                elif bt == "buy": buy_1d += weight
            if b["create_time"] >= now - seven_days:
                if bt == "view": view_7d += weight
                elif bt == "click": click_7d += weight
                elif bt == "cart": cart_7d += weight
                elif bt == "buy": buy_7d += weight
            if b["create_time"] < last_active:
                last_active = b["create_time"]

        last_active_hours = (now - last_active).total_seconds() / 3600

        # User preference: most purchased category
        cat_counts = {}
        for b in user_acts:
            if b["behavior_type"] in ("buy", "cart") and b["product_id"] in products:
                cat_id = products[b["product_id"]]["category_id"]
                cat_counts[cat_id] = cat_counts.get(cat_id, 0) + 1
        prefer_category = max(cat_counts.keys(), default=0) if cat_counts else 0

        user_feat = {
            "view_1d": view_1d, "click_1d": click_1d, "cart_1d": cart_1d, "buy_1d": buy_1d,
            "view_7d": view_7d, "click_7d": click_7d, "cart_7d": cart_7d, "buy_7d": buy_7d,
            "last_active_hours": last_active_hours,
            "prefer_category": prefer_category, "prefer_brand": 0
        }

        # Build user-item interaction map
        user_items = {}
        for b in user_acts:
            iid = b["product_id"]
            if iid in products:
                w = {"view": 1, "click": 2, "cart": 4, "favorite": 5, "buy": 8}.get(b["behavior_type"], 1)
                user_items[iid] = user_items.get(iid, 0) + w

        # Positive samples: bought/carted items
        positive_items = [iid for iid, score in user_items.items() if score >= 4 and iid in products]

        # Negative samples: random items not interacted
        all_product_ids = list(products.keys())
        negative_pool = [iid for iid in all_product_ids if iid not in user_items]

        # Balance: 1:1 positive to negative
        for pitem_id in positive_items:
            p = products[pitem_id]

            # Add positive sample
            item_feat = {
                "category_id": p["category_id"],
                "brand_id": brand_to_id(p.get("brand")),
                "price_bucket": price_bucket(p.get("price")),
                "sales_bucket": sales_bucket(p.get("sales")),
                "hot_score": float(p.get("sales") or 100),
                "price_ratio": (price_bucket(p.get("price")) + 1) / 10.0,
                "category_match": 1 if p["category_id"] == prefer_category else 0,
                "brand_match": 0
            }

            user_features_list.append(user_feat.copy())
            item_features_list.append(item_feat)
            labels.append(1)

            # Add 1 negative sample (balanced 1:1)
            if negative_pool:
                nitem_id = random.choice(negative_pool)
                n = products[nitem_id]
                neg_feat = {
                    "category_id": n["category_id"],
                    "brand_id": brand_to_id(n.get("brand")),
                    "price_bucket": price_bucket(n.get("price")),
                    "sales_bucket": sales_bucket(n.get("sales")),
                    "hot_score": float(n.get("sales") or 100),
                    "price_ratio": (price_bucket(n.get("price")) + 1) / 10.0,
                    "category_match": 1 if n["category_id"] == prefer_category else 0,
                    "brand_match": 0
                }
                user_features_list.append(user_feat.copy())
                item_features_list.append(neg_feat)
                labels.append(0)

    logger.info(f"Generated {len(labels)} samples: {sum(labels)} positive, {len(labels)-sum(labels)} negative")
    logger.info(f"Positive rate: {sum(labels)/len(labels)*100:.1f}%")

    return user_features_list, item_features_list, labels


def train_model(user_features, item_features, labels):
    """Train the DeepFM model"""
    logger.info("\n[Step 2] Splitting data...")

    random.seed(42)
    indices = list(range(len(labels)))
    random.shuffle(indices)

    split_idx = int(len(labels) * 0.8)
    train_idx = indices[:split_idx]
    val_idx = indices[split_idx:]

    train_data = {
        "user_features": [user_features[i] for i in train_idx],
        "item_features": [item_features[i] for i in train_idx],
        "labels": [labels[i] for i in train_idx]
    }
    val_data = {
        "user_features": [user_features[i] for i in val_idx],
        "item_features": [item_features[i] for i in val_idx],
        "labels": [labels[i] for i in val_idx]
    }

    logger.info(f"Training: {len(train_data['labels'])}, Validation: {len(val_data['labels'])}")

    logger.info("\n[Step 3] Training DeepFM model...")

    # Create model with adjusted config
    os.makedirs("models", exist_ok=True)

    ranker = DeepFMRanker(
        model_path=MODEL_PATH,
        embedding_dim=16  # Increase embedding dimension
    )
    ranker.load_model()

    # Train with more epochs
    history = ranker.train(
        train_data=train_data,
        val_data=val_data,
        epochs=20,  # More epochs
        batch_size=512,  # Larger batch
        save_path=MODEL_PATH
    )

    return ranker, history


def main():
    logger.info("=" * 60)
    logger.info("DeepFM Training - Optimized Version")
    logger.info("=" * 60)

    # Load data
    user_features, item_features, labels = load_training_data()

    if not labels:
        logger.error("No training data!")
        return

    # Train
    ranker, history = train_model(user_features, item_features, labels)

    # Results
    logger.info("\n" + "=" * 60)
    logger.info("Training Complete!")
    logger.info("=" * 60)

    if history.get("train_metrics"):
        final_train = history["train_metrics"][-1]
        logger.info(f"Final Training - AUC: {final_train.get('auc', 0):.4f}, LogLoss: {final_train.get('logloss', 0):.4f}")

    if history.get("val_metrics"):
        final_val = history["val_metrics"][-1]
        logger.info(f"Final Validation - AUC: {final_val.get('auc', 0):.4f}, LogLoss: {final_val.get('logloss', 0):.4f}")

    logger.info(f"\nModel saved to: {MODEL_PATH}")

    # Interpretation
    best_val_auc = max((m.get('auc', 0) for m in history.get("val_metrics", [])), default=0)
    logger.info("\n[AUC Interpretation]")
    logger.info(f"  Best Val AUC: {best_val_auc:.4f}")
    if best_val_auc >= 0.75:
        logger.info("  -> Good model! Can distinguish positive/negative samples well.")
    elif best_val_auc >= 0.65:
        logger.info("  -> Acceptable model. Has learned some patterns.")
    elif best_val_auc >= 0.55:
        logger.info("  -> Weak model. May need more data or feature engineering.")
    else:
        logger.info("  -> Poor model. Consider reviewing features and data quality.")


if __name__ == "__main__":
    main()
