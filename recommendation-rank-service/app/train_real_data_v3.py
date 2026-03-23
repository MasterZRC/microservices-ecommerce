"""
DeepFM Model Training Script - Final Version
With proper feature engineering for better AUC
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


def load_training_data_v3():
    """Load and prepare training data with user-specific and item-specific features"""
    logger.info("[Step 1] Loading data from MySQL...")

    conn = pymysql.connect(
        host=DB_CONFIG["host"],
        port=DB_CONFIG["port"],
        user=DB_CONFIG["user"],
        password=DB_CONFIG["password"],
        database=DB_CONFIG["database"],
        cursorclass=pymysql.cursors.DictCursor
    )

    cursor = conn.cursor()

    # Load products
    cursor.execute("""
        SELECT id, category_id, brand, price, sales
        FROM product WHERE status = 1
    """)
    products = {p["id"]: p for p in cursor.fetchall()}
    logger.info(f"Loaded {len(products)} products")

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

    logger.info(f"Loaded {len(behaviors)} behaviors")

    # Group by user
    user_behaviors = {}
    for b in behaviors:
        uid = b["user_id"]
        if uid not in user_behaviors:
            user_behaviors[uid] = []
        user_behaviors[uid].append(b)

    # Build training samples with proper features
    user_features_list = []
    item_features_list = []
    labels = []

    now = datetime.now()
    one_day = timedelta(days=1)
    seven_days = timedelta(days=7)

    for user_id, user_acts in user_behaviors.items():
        if len(user_acts) < 3:
            continue

        # Build user profile from ALL interactions
        cat_counts = {}  # category -> count
        brand_counts = {}  # brand -> count
        price_sum = 0
        price_count = 0
        viewed_items = set()
        bought_items = set()
        carted_items = set()

        for b in user_acts:
            pid = b["product_id"]
            bt = b["behavior_type"]

            if pid in products:
                p = products[pid]
                cat_id = p["category_id"]
                brand_id = brand_to_id(p.get("brand"))

                if bt in ("buy", "cart", "favorite"):
                    cat_counts[cat_id] = cat_counts.get(cat_id, 0) + 1
                    brand_counts[brand_id] = brand_counts.get(brand_id, 0) + 1

                if bt in ("buy", "cart"):
                    price_sum += float(p.get("price") or 0)
                    price_count += 1

            if bt == "view":
                viewed_items.add(pid)
            elif bt == "buy":
                bought_items.add(pid)
            elif bt == "cart":
                carted_items.add(pid)

        # User preferences
        prefer_category = max(cat_counts.keys(), key=lambda x: cat_counts[x]) if cat_counts else 0
        prefer_brand = max(brand_counts.keys(), key=lambda x: brand_counts[x]) if brand_counts else 0
        avg_buy_price = price_sum / price_count if price_count > 0 else 1000

        # User activity stats
        view_1d = click_1d = cart_1d = buy_1d = 0
        view_7d = click_7d = cart_7d = buy_7d = 0

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

        last_active = min((b["create_time"] for b in user_acts), default=now)
        last_active_hours = (now - last_active).total_seconds() / 3600

        # Top categories user interacted with (for cross-features)
        top_cats = sorted(cat_counts.keys(), key=lambda x: cat_counts[x], reverse=True)[:3]
        top_brands = sorted(brand_counts.keys(), key=lambda x: brand_counts[x], reverse=True)[:3]

        # User behavior counts
        total_buys = len(bought_items)
        total_views = len(viewed_items)

        # User feature dict (same for all samples of this user)
        user_feat = {
            "view_1d": min(view_1d, 100),
            "click_1d": min(click_1d, 50),
            "cart_1d": min(cart_1d, 20),
            "buy_1d": min(buy_1d, 10),
            "view_7d": min(view_7d, 500),
            "last_active_hours": min(last_active_hours, 720),
            "prefer_category": prefer_category,
            "prefer_brand": prefer_brand,
            "top_cats": top_cats,
            "top_brands": top_brands,
            "avg_buy_price": avg_buy_price,
            "total_buys": total_buys,
            "total_views": total_views,
            "viewed_items": viewed_items,
            "bought_items": bought_items,
        }

        # User-item interaction map
        user_items = {}
        user_item_scores = {}
        for b in user_acts:
            iid = b["product_id"]
            if iid in products:
                w = {"view": 1, "click": 2, "cart": 4, "favorite": 5, "buy": 8}.get(b["behavior_type"], 1)
                user_items[iid] = user_items.get(iid, 0) + 1
                user_item_scores[iid] = user_item_scores.get(iid, 0) + w

        # Positive: high interaction score items (buy or cart)
        positive_items = [iid for iid, score in user_item_scores.items() if score >= 4]

        # Negative: random items not interacted (or only viewed)
        all_product_ids = list(products.keys())
        negative_pool = [iid for iid in all_product_ids if iid not in user_items or user_item_scores.get(iid, 0) < 4]

        # Generate samples
        for pitem_id in positive_items:
            if pitem_id not in products:
                continue

            p = products[pitem_id]

            # Build item features
            item_feat = {
                "category_id": p["category_id"],
                "brand_id": brand_to_id(p.get("brand")),
                "price_bucket": price_bucket(p.get("price")),
                "sales_bucket": sales_bucket(p.get("sales")),
                "hot_score": float(p.get("sales") or 100),
                "price": float(p.get("price") or 0),
            }

            # Cross-features: interaction between user and item
            cat_match = 1 if p["category_id"] in top_cats else 0
            brand_match = 1 if brand_to_id(p.get("brand")) in top_brands else 0

            # Price affordability
            item_price = float(p.get("price") or 0)
            if item_price <= avg_buy_price * 0.5:
                price_afford = 2  # cheap
            elif item_price <= avg_buy_price * 1.5:
                price_afford = 1  # normal
            else:
                price_afford = 0  # expensive

            # Whether user has viewed this item
            user_viewed = 1 if pitem_id in viewed_items else 0
            user_bought_similar_cat = 1 if p["category_id"] in cat_counts else 0

            cross_feat = {
                "category_match": cat_match,
                "brand_match": brand_match,
                "price_afford": price_afford,
                "user_viewed": user_viewed,
                "user_bought_similar": user_bought_similar_cat,
            }

            # Merge all features
            full_item_feat = {**item_feat, **cross_feat}

            user_features_list.append(user_feat.copy())
            item_features_list.append(full_item_feat)
            labels.append(1)

            # Add 1-2 negative samples
            num_neg = min(2, len(negative_pool))
            if num_neg > 0:
                neg_samples = random.sample(negative_pool, num_neg)
                for nitem_id in neg_samples:
                    if nitem_id not in products:
                        continue

                    n = products[nitem_id]
                    neg_item_feat = {
                        "category_id": n["category_id"],
                        "brand_id": brand_to_id(n.get("brand")),
                        "price_bucket": price_bucket(n.get("price")),
                        "sales_bucket": sales_bucket(n.get("sales")),
                        "hot_score": float(n.get("sales") or 100),
                        "price": float(n.get("price") or 0),
                    }

                    neg_cat_match = 1 if n["category_id"] in top_cats else 0
                    neg_brand_match = 1 if brand_to_id(n.get("brand")) in top_brands else 0

                    neg_price = float(n.get("price") or 0)
                    if neg_price <= avg_buy_price * 0.5:
                        neg_price_afford = 2
                    elif neg_price <= avg_buy_price * 1.5:
                        neg_price_afford = 1
                    else:
                        neg_price_afford = 0

                    neg_cross = {
                        "category_match": neg_cat_match,
                        "brand_match": neg_brand_match,
                        "price_afford": neg_price_afford,
                        "user_viewed": 1 if nitem_id in viewed_items else 0,
                        "user_bought_similar": 1 if n["category_id"] in cat_counts else 0,
                    }

                    neg_full_feat = {**neg_item_feat, **neg_cross}

                    user_features_list.append(user_feat.copy())
                    item_features_list.append(neg_full_feat)
                    labels.append(0)

    logger.info(f"Generated {len(labels)} samples: {sum(labels)} positive, {len(labels)-sum(labels)} negative")
    logger.info(f"Positive rate: {sum(labels)/len(labels)*100:.1f}%")

    return user_features_list, item_features_list, labels


def main():
    logger.info("=" * 60)
    logger.info("DeepFM Training - Feature Engineering Version")
    logger.info("=" * 60)

    # Load data
    user_features, item_features, labels = load_training_data_v3()

    if not labels:
        logger.error("No training data!")
        return

    # Split data
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

    # Train
    logger.info("\n[Step 3] Training DeepFM model...")

    os.makedirs("models", exist_ok=True)

    ranker = DeepFMRanker(
        model_path=MODEL_PATH,
        embedding_dim=16
    )
    ranker.load_model()

    history = ranker.train(
        train_data=train_data,
        val_data=val_data,
        epochs=30,
        batch_size=512,
        save_path=MODEL_PATH
    )

    # Results
    logger.info("\n" + "=" * 60)
    logger.info("Training Complete!")
    logger.info("=" * 60)

    if history.get("train_metrics"):
        final_train = history["train_metrics"][-1]
        logger.info(f"Final Training - AUC: {final_train.get('auc', 0):.4f}")

    if history.get("val_metrics"):
        final_val = history["val_metrics"][-1]
        logger.info(f"Final Validation - AUC: {final_val.get('auc', 0):.4f}")

    best_val_auc = max((m.get('auc', 0) for m in history.get("val_metrics", [])), default=0)
    logger.info(f"\nBest Validation AUC: {best_val_auc:.4f}")

    logger.info(f"\nModel saved to: {MODEL_PATH}")


if __name__ == "__main__":
    main()
