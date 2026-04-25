"""
Features module for DeepFM model
"""

import numpy as np
import logging
from typing import Dict, List, Optional, Tuple
import random

try:
    from .schemas import UserFeatures, ItemFeatures
except ImportError:
    # Standalone mode (when running scripts directly)
    from schemas import UserFeatures, ItemFeatures

logger = logging.getLogger(__name__)


class FeatureEngine:
    """特征工程引擎"""

    # 特征字段配置
    SPARSE_FEATURES = [
        "category_id", "brand_id", "price_bucket", "sales_bucket"
    ]

    DENSE_FEATURES = [
        "view_1d", "click_1d", "cart_1d", "buy_1d",
        "view_7d", "click_7d", "cart_7d", "buy_7d",
        "view_30d", "last_active_hours", "hot_score"
    ]

    def __init__(self):
        # 特征维度（实际训练时从数据中学习）
        self.category_id_dim = 100  # 类目数量
        self.brand_id_dim = 50      # 品牌数量
        self.price_bucket_dim = 10   # 价格分桶数
        self.sales_bucket_dim = 10    # 销量分桶数

    def build_user_features(self, user_feat: Optional[UserFeatures]) -> Dict[str, np.ndarray]:
        """构建用户特征向量"""
        if user_feat is None:
            user_feat = UserFeatures()

        features = {}

        # 密集特征
        features["view_1d"] = np.array([min(user_feat.view_1d, 100)])
        features["click_1d"] = np.array([min(user_feat.click_1d, 50)])
        features["cart_1d"] = np.array([min(user_feat.cart_1d, 20)])
        features["buy_1d"] = np.array([min(user_feat.buy_1d, 10)])

        features["view_7d"] = np.array([min(user_feat.view_7d, 500)])
        features["click_7d"] = np.array([min(user_feat.click_7d, 200)])
        features["cart_7d"] = np.array([min(user_feat.cart_7d, 50)])
        features["buy_7d"] = np.array([min(user_feat.buy_7d, 20)])

        features["view_30d"] = np.array([min(user_feat.view_30d, 1000)])
        features["last_active_hours"] = np.array([min(user_feat.last_active_hours, 720)])

        # 用户偏好类目（转换为 one-hot）
        category_onehot = np.zeros(self.category_id_dim)
        for cat_id in user_feat.prefer_category[:5]:  # 最多5个
            if 0 <= cat_id < self.category_id_dim:
                category_onehot[cat_id] = 1.0
        features["prefer_category"] = category_onehot

        # 用户偏好品牌
        brand_onehot = np.zeros(self.brand_id_dim)
        for brand_id in user_feat.prefer_brand[:5]:
            if 0 <= brand_id < self.brand_id_dim:
                brand_onehot[brand_id] = 1.0
        features["prefer_brand"] = brand_onehot

        return features

    def build_item_features(self, item_id: str, item_feat: Optional[ItemFeatures]) -> Dict[str, np.ndarray]:
        """构建商品特征向量"""
        if item_feat is None:
            item_feat = ItemFeatures(category_id=0, brand_id=0)

        features = {}

        # 稀疏特征（离散 ID）
        features["category_id"] = np.array([min(item_feat.category_id, self.category_id_dim - 1)])
        features["brand_id"] = np.array([min(item_feat.brand_id, self.brand_id_dim - 1)])
        features["price_bucket"] = np.array([min(item_feat.price_bucket, self.price_bucket_dim - 1)])
        features["sales_bucket"] = np.array([min(item_feat.sales_bucket, self.sales_bucket_dim - 1)])

        # 密集特征
        features["hot_score"] = np.array([min(item_feat.hot_score, 10000.0)])

        return features

    def build_cross_features(self, user_feat: Optional[UserFeatures],
                            item_feat: Optional[ItemFeatures]) -> Dict[str, np.ndarray]:
        """构建交叉特征"""
        cross_features = {}

        if user_feat is None or item_feat is None:
            return cross_features

        # 用户偏好类目与商品类目是否匹配
        if user_feat.prefer_category and item_feat.category_id:
            match = 1.0 if item_feat.category_id in user_feat.prefer_category else 0.0
            cross_features["category_match"] = np.array([match])

        # 用户偏好品牌与商品品牌是否匹配
        if user_feat.prefer_brand and item_feat.brand_id:
            match = 1.0 if item_feat.brand_id in user_feat.prefer_brand else 0.0
            cross_features["brand_match"] = np.array([match])

        return cross_features

    def get_feature_dim(self) -> Dict[str, int]:
        """获取各特征维度"""
        return {
            "sparse": len(self.SPARSE_FEATURES),
            "dense": len(self.DENSE_FEATURES),
            "category_dim": self.category_id_dim,
            "brand_dim": self.brand_id_dim
        }


class SyntheticDataGenerator:
    """合成数据生成器 - 用于生成训练数据"""

    def __init__(self, num_users: int = 10000, num_items: int = 1000,
                 num_categories: int = 20, num_brands: int = 10):
        self.num_users = num_users
        self.num_items = num_items
        self.num_categories = num_categories
        self.num_brands = num_brands

        # 预生成商品属性
        self.item_attributes = self._generate_item_attributes()

        # 预生成用户偏好
        self.user_preferences = self._generate_user_preferences()

    def _generate_item_attributes(self) -> List[Dict]:
        """生成商品属性"""
        items = []
        for item_id in range(self.num_items):
            items.append({
                "item_id": item_id,
                "category_id": random.randint(0, self.num_categories - 1),
                "brand_id": random.randint(0, self.num_brands - 1),
                "price_bucket": random.randint(0, 9),
                "sales_bucket": random.randint(0, 9),
                "hot_score": random.uniform(100, 10000),
            })
        return items

    def _generate_user_preferences(self) -> List[Dict]:
        """生成用户偏好"""
        users = []
        for user_id in range(self.num_users):
            # 随机选择2-5个偏好类目
            num_prefer_cats = random.randint(2, 5)
            prefer_categories = random.sample(range(self.num_categories), num_prefer_cats)

            # 随机选择1-3个偏好品牌
            num_prefer_brands = random.randint(1, 3)
            prefer_brands = random.sample(range(self.num_brands), num_prefer_brands)

            users.append({
                "user_id": user_id,
                "prefer_categories": prefer_categories,
                "prefer_brands": prefer_brands,
            })
        return users

    def generate_interaction_data(self, num_samples: int = 50000,
                                   positive_ratio: float = 0.3) -> Tuple[List[Dict], List[Dict], List[int]]:
        """
        生成用户-商品交互数据

        Args:
            num_samples: 样本数量
            positive_ratio: 正样本比例

        Returns:
            (user_features, item_features, labels)
        """
        user_features_list = []
        item_features_list = []
        labels = []

        num_positive = int(num_samples * positive_ratio)
        num_negative = num_samples - num_positive

        # 生成正样本（用户点击）
        for _ in range(num_positive):
            user = random.choice(self.user_preferences)
            item = random.choice(self.item_attributes)

            # 正样本：用户偏好与商品匹配的概率更高
            category_match = item["category_id"] in user["prefer_categories"]
            brand_match = item["brand_id"] in user["prefer_brands"]

            # 根据匹配度调整概率
            if category_match or brand_match:
                prob = 0.8
            else:
                prob = 0.3

            if random.random() < prob:
                user_feat = self._build_user_features(user, include_prefer=True)
                item_feat = self._build_item_features(item)
                labels.append(1)

                user_features_list.append(user_feat)
                item_features_list.append(item_feat)

        # 生成负样本（用户未点击）
        for _ in range(num_negative):
            user = random.choice(self.user_preferences)
            item = random.choice(self.item_attributes)

            user_feat = self._build_user_features(user, include_prefer=True)
            item_feat = self._build_item_features(item)
            labels.append(0)

            user_features_list.append(user_feat)
            item_features_list.append(item_feat)

        # 打乱数据
        combined = list(zip(user_features_list, item_features_list, labels))
        random.shuffle(combined)
        user_features_list, item_features_list, labels = zip(*combined)

        return list(user_features_list), list(item_features_list), list(labels)

    def _build_user_features(self, user: Dict, include_prefer: bool = True) -> Dict:
        """构建用户特征"""
        # 行为统计特征
        view_1d = random.randint(0, 50)
        click_1d = int(view_1d * random.uniform(0.1, 0.3))
        cart_1d = int(click_1d * random.uniform(0.05, 0.15))
        buy_1d = int(cart_1d * random.uniform(0.1, 0.3))

        view_7d = view_1d * random.randint(3, 7)
        click_7d = click_1d * random.randint(3, 7)
        cart_7d = cart_1d * random.randint(3, 7)
        buy_7d = buy_1d * random.randint(3, 7)

        last_active_hours = random.randint(1, 168)

        user_feat = {
            "user_id": user["user_id"],
            "view_1d": view_1d,
            "click_1d": click_1d,
            "cart_1d": cart_1d,
            "buy_1d": buy_1d,
            "view_7d": view_7d,
            "click_7d": click_7d,
            "cart_7d": cart_7d,
            "buy_7d": buy_7d,
            "last_active_hours": last_active_hours,
        }

        if include_prefer:
            user_feat["prefer_category"] = user["prefer_categories"][0] if user["prefer_categories"] else 0
            user_feat["prefer_brand"] = user["prefer_brands"][0] if user["prefer_brands"] else 0
            user_feat["prefer_categories"] = user["prefer_categories"]
            user_feat["prefer_brands"] = user["prefer_brands"]

            # 添加分桶特征
            user_feat["view_1d_bucket"] = min(view_1d // 10, 9)
            user_feat["click_1d_bucket"] = min(click_1d // 5, 9)
            user_feat["active_days_bucket"] = min(last_active_hours // 24, 13)
            user_feat["prefer_category_idx"] = user_feat["prefer_category"] % 10

        return user_feat

    def _build_item_features(self, item: Dict) -> Dict:
        """构建商品特征"""
        return {
            "item_id": item["item_id"],
            "category_id": item["category_id"],
            "brand_id": item["brand_id"],
            "price_bucket": item["price_bucket"],
            "sales_bucket": item["sales_bucket"],
            "hot_score": item["hot_score"],
            "price_ratio": (item["price_bucket"] + 1) / 10.0,
        }

    def split_data(self, user_features: List[Dict], item_features: List[Dict],
                   labels: List[int], train_ratio: float = 0.8
                   ) -> Tuple[Dict, Dict]:
        """
        划分训练集和验证集

        Returns:
            train_data: {user_features, item_features, labels}
            val_data: {user_features, item_features, labels}
        """
        num_samples = len(labels)
        split_idx = int(num_samples * train_ratio)

        train_data = {
            "user_features": user_features[:split_idx],
            "item_features": item_features[:split_idx],
            "labels": labels[:split_idx]
        }

        val_data = {
            "user_features": user_features[split_idx:],
            "item_features": item_features[split_idx:],
            "labels": labels[split_idx:]
        }

        return train_data, val_data


def combine_features(user_features: Dict[str, np.ndarray],
                     item_features: Dict[str, np.ndarray],
                     cross_features: Dict[str, np.ndarray]) -> np.ndarray:
    """合并所有特征为单一向量"""
    all_features = []

    for feat_dict in [user_features, item_features, cross_features]:
        for key, value in feat_dict.items():
            if isinstance(value, np.ndarray):
                all_features.append(value.flatten())

    return np.concatenate(all_features) if all_features else np.array([])


class RealDataGenerator:
    """从真实数据库读取用户行为数据并转换为训练数据"""

    def __init__(self, db_config: Dict = None):
        """
        初始化数据库连接配置

        Args:
            db_config: 数据库配置，包含 host, port, user, password, database
        """
        self.db_config = db_config or {
            "host": "ecommerce-mysql",
            "port": 3306,
            "user": "root",
            "password": "root123",
            "database": "ecommerce"
        }
        self._connection = None

    def _get_connection(self):
        """获取数据库连接（延迟连接）"""
        if self._connection is None:
            try:
                import pymysql
                self._connection = pymysql.connect(
                    host=self.db_config["host"],
                    port=self.db_config["port"],
                    user=self.db_config["user"],
                    password=self.db_config["password"],
                    database=self.db_config["database"],
                    cursorclass=pymysql.cursors.DictCursor
                )
                logger.info(f"已连接到 MySQL: {self.db_config['host']}:{self.db_config['port']}/{self.db_config['database']}")
            except Exception as e:
                logger.error(f"数据库连接失败: {e}")
                raise
        return self._connection

    def load_interactions_from_db(self, min_interactions: int = 5) -> Tuple[List[Dict], List[Dict], List[int]]:
        """
        从数据库加载用户-商品交互数据

        Args:
            min_interactions: 最少交互次数（用于筛选活跃用户）

        Returns:
            (user_features_list, item_features_list, labels)
        """
        import pymysql

        conn = self._get_connection()
        cursor = conn.cursor()

        # 行为权重
        behavior_weights = {
            "view": 1,
            "click": 2,
            "cart": 4,
            "favorite": 5,
            "buy": 8
        }

        user_features_list = []
        item_features_list = []
        labels = []

        try:
            # 查询所有行为数据
            cursor.execute("""
                SELECT user_id, product_id, behavior_type, create_time, score
                FROM user_behavior
                ORDER BY user_id, create_time DESC
            """)

            all_behaviors = cursor.fetchall()
            logger.info(f"从数据库加载了 {len(all_behaviors)} 条行为记录")

            if not all_behaviors:
                raise ValueError("数据库中没有行为数据，请先生成一些数据")

            # 按用户分组
            user_behaviors = {}
            for row in all_behaviors:
                uid = int(row["user_id"])
                if uid not in user_behaviors:
                    user_behaviors[uid] = []
                user_behaviors[uid].append(row)

            # 获取商品信息
            product_info = self._get_product_info(cursor)

            # 为每个用户生成正负样本
            for user_id, behaviors in user_behaviors.items():
                if len(behaviors) < min_interactions:
                    continue

                # 统计用户的商品交互
                user_item_scores = {}
                for behavior in behaviors:
                    item_id = int(behavior["product_id"])
                    behavior_type = behavior["behavior_type"]
                    weight = behavior_weights.get(behavior_type, 1)
                    score = float(behavior["score"]) if behavior["score"] else 1.0

                    if item_id not in user_item_scores:
                        user_item_scores[item_id] = 0
                    user_item_scores[item_id] += weight * score

                # 计算用户特征
                user_feat = self._build_user_features(behaviors)

                # 正样本：用户交互过的商品（购买/加购权重更高）
                positive_items = []
                for item_id, score in user_item_scores.items():
                    if score >= 4:  # 有购买或加购行为
                        positive_items.append(item_id)

                # 为每个正样本生成训练数据
                for item_id in positive_items:
                    if item_id not in product_info:
                        continue

                    item_feat = self._build_item_features(item_id, product_info.get(item_id, {}))

                    user_features_list.append(user_feat)
                    item_features_list.append(item_feat)
                    labels.append(1)  # 正样本

                    # 负采样：用户未交互的真实商品
                    # 使用 product_info 中实际存在的商品ID，而非硬编码范围
                    all_item_ids = list(product_info.keys())
                    negative_candidates = [i for i in all_item_ids if i not in user_item_scores]
                    num_negatives = min(len(positive_items), 3)  # 每个正样本负采样最多3个
                    negative_samples = random.sample(
                        negative_candidates,
                        min(num_negatives, len(negative_candidates))
                    )

                    for neg_item_id in negative_samples:
                        if neg_item_id in product_info:
                            neg_item_feat = self._build_item_features(neg_item_id, product_info.get(neg_item_id, {}))
                            user_features_list.append(user_feat.copy())
                            item_features_list.append(neg_item_feat)
                            labels.append(0)  # 负样本

            logger.info(f"生成训练数据: 正样本={labels.count(1)}, 负样本={labels.count(0)}")

        finally:
            cursor.close()

        return user_features_list, item_features_list, labels

    def _brand_name_to_id(self, brand_name: str) -> int:
        """将品牌名称转换为 ID（使用哈希）"""
        if not brand_name:
            return 0
        # 使用哈希将品牌名转换为 0-49 的 ID
        return hash(brand_name) % 50

    def _get_product_info(self, cursor) -> Dict:
        """
        从 product 表获取商品真实信息
        包含: category_id, brand(→brand_id), price(→price_bucket), sales(→sales_bucket), hot_score
        """
        product_info = {}

        try:
            cursor.execute("""
                SELECT id, category_id, brand, price, sales
                FROM product
                WHERE status = 1
                LIMIT 1000
            """)
            products = cursor.fetchall()

            if not products:
                logger.warning("product 表中没有上架商品 (status=1)")

            # 全局最大销量用于归一化
            max_sales = max((float(p.get("sales", 0) or 0) for p in products), default=1)
            max_price = max((float(p.get("price", 0) or 0) for p in products), default=1)

            for p in products:
                item_id = int(p["id"])
                price = float(p.get("price", 0) or 0)
                sales = float(p.get("sales", 0) or 0)

                product_info[item_id] = {
                    "category_id": int(p.get("category_id", 0) or 0),
                    "brand_id": self._brand_name_to_id(p.get("brand", "")),
                    # price_bucket: 0-9 分桶，基于该商品在其所属类目中的相对价格
                    "price_bucket": self._compute_price_bucket(price, max_price),
                    # sales_bucket: 0-9 分桶，基于归一化销量
                    "sales_bucket": self._compute_sales_bucket(sales, max_sales),
                    # hot_score: 使用真实销量作为热门度指标
                    "hot_score": sales,
                }

            logger.info(f"从 product 表获取了 {len(product_info)} 个商品信息")

        except Exception as e:
            logger.error(f"无法从 product 表获取商品信息: {e}")
            raise ValueError("无法获取商品数据，禁止使用假数据兜底。请确保 product 表有数据。")

        if len(product_info) < 1:
            raise ValueError("商品表为空，无法生成真实训练数据。请先导入商品数据。")

        return product_info

    def _compute_price_bucket(self, price: float, max_price: float) -> int:
        """根据价格相对位置计算分桶 (0-9)，使用对数缩放避免极值影响"""
        if price <= 0 or max_price <= 0:
            return 0
        ratio = min(price / max_price, 1.0)
        # 使用对数分桶：0-9 共10个桶
        return min(int(ratio ** 0.5 * 10), 9)

    def _compute_sales_bucket(self, sales: float, max_sales: float) -> int:
        """根据销量相对位置计算分桶 (0-9)，使用对数缩放"""
        if sales <= 0 or max_sales <= 0:
            return 0
        ratio = min(sales / max_sales, 1.0)
        return min(int(ratio ** 0.5 * 10), 9)

    def _build_user_features(self, behaviors: List[Dict]) -> Dict:
        """构建用户特征"""
        import datetime

        now = datetime.datetime.now()
        one_day_ago = now - datetime.timedelta(days=1)
        seven_days_ago = now - datetime.timedelta(days=7)
        thirty_days_ago = now - datetime.timedelta(days=30)

        # 统计各时间段的各类行为数量
        stats = {
            "view_1d": 0, "click_1d": 0, "cart_1d": 0, "buy_1d": 0,
            "view_7d": 0, "click_7d": 0, "cart_7d": 0, "buy_7d": 0,
            "view_30d": 0
        }

        last_active = now

        for b in behaviors:
            create_time = b["create_time"]
            if create_time < last_active:
                last_active = create_time

            behavior_type = b["behavior_type"]
            weight = {"view": 1, "click": 2, "cart": 4, "favorite": 5, "buy": 8}.get(behavior_type, 1)

            if create_time >= one_day_ago:
                key = f"{behavior_type}_1d"
                if key in stats:
                    stats[key] += weight

            if create_time >= seven_days_ago:
                key = f"{behavior_type}_7d"
                if key in stats:
                    stats[key] += weight

            if create_time >= thirty_days_ago:
                if behavior_type == "view":
                    stats["view_30d"] += weight

        # 计算活跃时长（小时）
        last_active_hours = (now - last_active).total_seconds() / 3600
        last_active_hours = min(last_active_hours, 720)  # 最多30天

        return {
            "view_1d": stats["view_1d"],
            "click_1d": stats["click_1d"],
            "cart_1d": stats["cart_1d"],
            "buy_1d": stats["buy_1d"],
            "view_7d": stats["view_7d"],
            "click_7d": stats["click_7d"],
            "cart_7d": stats["cart_7d"],
            "buy_7d": stats["buy_7d"],
            "view_30d": stats["view_30d"],
            "last_active_hours": int(last_active_hours),
            "prefer_category": [],  # 简化处理
            "prefer_brand": []     # 简化处理
        }

    def _build_item_features(self, item_id: int, product_data: Dict) -> Dict:
        """
        构建商品特征

        注意：price_bucket 和 sales_bucket 需要从数据库中真实获取，
        如果 product 表缺少这些字段，则返回原始 category_id 和 brand_id，
        让特征维度自动适配，而非用 ID 模运算生成假数据。
        """
        category_id = product_data.get("category_id", 0)
        brand_id = product_data.get("brand_id", 0)

        # price_bucket 和 sales_bucket 只能从 product 表的 price/sales 字段计算得出
        # 如果 product_data 包含这些字段则使用，否则传 0 让模型自己学习
        price_bucket = product_data.get("price_bucket", 0)
        sales_bucket = product_data.get("sales_bucket", 0)
        hot_score = product_data.get("hot_score", 0.0)

        return {
            "category_id": category_id,
            "brand_id": brand_id,
            "price_bucket": price_bucket,
            "sales_bucket": sales_bucket,
            "hot_score": hot_score,
        }

    def load_exposure_negative_samples(self, user_id: int, exclude_items: set, limit: int = 50) -> List[int]:
        """
        加载用户曝光但未点击的商品作为负样本（曝光负采样）

        Args:
            user_id: 用户ID
            exclude_items: 需要排除的商品ID集合
            limit: 返回数量上限

        Returns:
            曝光但未交互的商品ID列表
        """
        try:
            cursor = self._get_connection().cursor()

            # 查询用户曝光但未点击/购买/加购的商品
            cursor.execute("""
                SELECT pe.product_id, MAX(pe.create_time) AS latest_time
                FROM product_exposure pe
                LEFT JOIN user_behavior ub
                    ON ub.user_id = pe.user_id
                    AND ub.product_id = pe.product_id
                    AND ub.behavior_type IN ('click', 'buy', 'cart', 'favorite')
                WHERE pe.user_id = %s
                  AND ub.id IS NULL
                  AND pe.create_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                GROUP BY pe.product_id
                ORDER BY latest_time DESC
                LIMIT %s
            """, (user_id, limit))

            results = cursor.fetchall()
            cursor.close()

            samples = [int(r["product_id"]) for r in results]

            # 排除已交互的商品
            if exclude_items:
                samples = [pid for pid in samples if pid not in exclude_items]

            logger.debug(f"曝光负采样: user_id={user_id}, 采样数={len(samples)}")
            return samples[:limit]

        except Exception as e:
            logger.warning(f"曝光负采样失败: user_id={user_id}, error={e}")
            return []

    def load_training_data_with_exposure_negative(
        self,
        min_interactions: int = 5,
        negative_per_positive: int = 3,
        exposure_negative_ratio: float = 0.5
    ) -> Tuple[List[Dict], List[Dict], List[int]]:
        """
        增强版训练数据生成：
        - 正样本：从真实交互中提取
        - 负样本：曝光未点击样本（高质量）+ 随机未交互样本

        Args:
            min_interactions: 最少交互次数
            negative_per_positive: 每个正样本对应的负样本数量
            exposure_negative_ratio: 负样本中曝光负采样的比例 (0.0-1.0)

        Returns:
            (user_features, item_features, labels)
        """
        import pymysql
        conn = self._get_connection()
        cursor = conn.cursor()

        # 行为权重
        behavior_weights = {"view": 1, "click": 2, "cart": 4, "favorite": 5, "buy": 8}

        # 加载商品信息
        product_info = self._get_product_info(cursor)

        # 加载所有行为数据
        cursor.execute("""
            SELECT user_id, product_id, behavior_type, create_time, score
            FROM user_behavior ORDER BY user_id, create_time DESC
        """)
        all_behaviors = cursor.fetchall()
        cursor.close()

        if not all_behaviors:
            raise ValueError("数据库中没有行为数据")

        # 按用户分组
        user_behaviors = {}
        for row in all_behaviors:
            uid = int(row["user_id"])
            if uid not in user_behaviors:
                user_behaviors[uid] = []
            user_behaviors[uid].append(row)

        user_features_list = []
        item_features_list = []
        labels = []

        for user_id, behaviors in user_behaviors.items():
            if len(behaviors) < min_interactions:
                continue

            # 构建用户特征
            user_feat = self._build_user_features(behaviors)

            # 用户已交互商品
            user_item_scores = {}
            for behavior in behaviors:
                item_id = int(behavior["product_id"])
                weight = behavior_weights.get(behavior["behavior_type"], 1)
                score = float(behavior["score"]) if behavior["score"] else 1.0
                user_item_scores[item_id] = user_item_scores.get(item_id, 0) + weight * score

            # 正样本
            positive_items = [iid for iid, score in user_item_scores.items() if score >= 4]

            # 曝光负样本（高质量）
            exposure_negatives = set(self.load_exposure_negative_samples(
                user_id, set(user_item_scores.keys()), limit=negative_per_positive * 3
            ))

            # 随机负样本
            all_item_ids = list(product_info.keys())
            random_negatives_pool = [iid for iid in all_item_ids if iid not in user_item_scores]
            random_negatives = set(random.sample(
                random_negatives_pool,
                min(len(random_negatives_pool), len(positive_items) * negative_per_positive)
            ))

            for item_id in positive_items:
                if item_id not in product_info:
                    continue

                # 添加正样本
                item_feat = self._build_item_features(item_id, product_info.get(item_id, {}))
                user_features_list.append(user_feat.copy())
                item_features_list.append(item_feat)
                labels.append(1)

                # 混合负样本
                num_exposure_neg = int(negative_per_positive * exposure_negative_ratio)
                num_random_neg = negative_per_positive - num_exposure_neg

                # 曝光负样本
                for neg_id in list(exposure_negatives)[:num_exposure_neg]:
                    if neg_id in product_info:
                        neg_feat = self._build_item_features(neg_id, product_info.get(neg_id, {}))
                        user_features_list.append(user_feat.copy())
                        item_features_list.append(neg_feat)
                        labels.append(0)

                # 随机负样本
                for neg_id in list(random_negatives)[:num_random_neg]:
                    if neg_id in product_info:
                        neg_feat = self._build_item_features(neg_id, product_info.get(neg_id, {}))
                        user_features_list.append(user_feat.copy())
                        item_features_list.append(neg_feat)
                        labels.append(0)

        logger.info(f"增强训练数据: 正样本={labels.count(1)}, 负样本={labels.count(0)}")
        return user_features_list, item_features_list, labels

    def close(self):
        """关闭数据库连接"""
        if self._connection:
            self._connection.close()
            self._connection = None


class SequenceFeatureBuilder:
    """
    序列特征构建器
    从用户行为历史中提取序列特征，用于 DeepFM-Attention 模型
    """

    def __init__(self, db_config: Dict = None):
        # 默认 host 走环境变量 MYSQL_HOST（容器内默认 docker network DNS 名 mysql/ecommerce-mysql）
        # 本地开发时可设置 MYSQL_HOST=localhost
        import os
        default_host = os.environ.get("MYSQL_HOST", "ecommerce-mysql")
        self.db_config = db_config or {
            "host": default_host,
            "port": 3306,
            "user": "root",
            "password": "root123",
            "database": "ecommerce"
        }
        self._connection = None
        # 预加载商品信息用于序列特征构建
        self._product_cache = {}

    def _get_connection(self):
        """获取数据库连接"""
        if self._connection is None:
            import pymysql
            self._connection = pymysql.connect(
                host=self.db_config["host"],
                port=self.db_config["port"],
                user=self.db_config["user"],
                password=self.db_config["password"],
                database=self.db_config["database"],
                cursorclass=pymysql.cursors.DictCursor
            )
        return self._connection

    def _brand_to_id(self, brand: str) -> int:
        """将品牌名转换为 ID"""
        if not brand:
            return 0
        return hash(str(brand)) % 50

    def _ensure_product_cache(self):
        """预加载商品信息到缓存"""
        if self._product_cache:
            return
        try:
            conn = self._get_connection()
            cursor = conn.cursor()
            cursor.execute("""
                SELECT id, category_id, brand, price, sales
                FROM product WHERE status = 1
            """)
            for p in cursor.fetchall():
                self._product_cache[int(p["id"])] = {
                    "category_id": int(p.get("category_id", 0) or 0),
                    "brand_id": self._brand_to_id(p.get("brand", "")),
                    "price": float(p.get("price", 0) or 0),
                    "sales": float(p.get("sales", 0) or 0),
                }
            cursor.close()
            logger.info(f"预加载了 {len(self._product_cache)} 个商品信息到序列特征缓存")
        except Exception as e:
            logger.warning(f"无法预加载商品缓存: {e}")

    def load_user_behavior_sequence(self, user_id: int, limit: int = 20) -> List[Tuple[int, str, float]]:
        """
        加载用户最近 N 次行为序列

        Args:
            user_id: 用户ID
            limit: 最大返回数量

        Returns:
            List[(product_id, behavior_type, timestamp_timestamp)]
        """
        try:
            conn = self._get_connection()
            cursor = conn.cursor()
            cursor.execute("""
                SELECT product_id, behavior_type, create_time
                FROM user_behavior
                WHERE user_id = %s
                ORDER BY create_time DESC
                LIMIT %s
            """, (user_id, limit))
            results = cursor.fetchall()
            cursor.close()
            return [(int(r["product_id"]), r["behavior_type"], r["create_time"].timestamp()) for r in results]
        except Exception as e:
            logger.error(f"加载用户行为序列失败: user_id={user_id}, error={e}")
            return []

    def build_sequence_features(
        self,
        behaviors: List[Tuple[int, str, float]],
        target_item_id: int
    ) -> Dict[str, float]:
        """
        构建序列特征

        Args:
            behaviors: 用户历史行为序列 [(product_id, behavior_type, timestamp)]
            target_item_id: 目标商品ID

        Returns:
            序列特征字典
        """
        if not behaviors:
            return self._empty_sequence_features()

        self._ensure_product_cache()

        target_info = self._product_cache.get(target_item_id, {})
        target_cat = target_info.get("category_id", 0)
        target_brand = target_info.get("brand_id", 0)
        target_price = target_info.get("price", 0)

        # 历史商品的类目和品牌列表
        history_cats = []
        history_brands = []
        history_prices = []
        now_ts = behaviors[0][2] if behaviors else 0

        for pid, btype, ts in behaviors:
            p_info = self._product_cache.get(pid, {})
            history_cats.append((p_info.get("category_id", 0), btype, ts))
            history_brands.append((p_info.get("brand_id", 0), btype, ts))
            history_prices.append(p_info.get("price", 0))

        # 1. 类目匹配度：历史行为中与目标类目相同的比例
        cat_match_count = sum(1 for cat, _, _ in history_cats if cat == target_cat)
        seq_category_match = cat_match_count / len(history_cats) if history_cats else 0

        # 2. 品牌匹配度
        brand_match_count = sum(1 for brand, _, _ in history_brands if brand == target_brand)
        seq_brand_match = brand_match_count / len(history_brands) if history_brands else 0

        # 3. 价格相似度（使用归一化价格差）
        if history_prices and target_price > 0:
            avg_hist_price = np.mean(history_prices)
            price_similarity = 1.0 - min(abs(target_price - avg_hist_price) / max(target_price, avg_hist_price), 1.0)
        else:
            price_similarity = 0.5

        # 4. 时间衰减分数：越近期的行为权重越高
        recency_score = 0.0
        for i, (pid, btype, ts) in enumerate(behaviors[:5]):  # 只看最近5个
            # 指数衰减：半衰期约 1 天
            hours_ago = (now_ts - ts) / 3600
            decay = np.exp(-0.693 * hours_ago / 24)  # 半衰期1天
            weight = {"view": 1, "click": 2, "cart": 4, "favorite": 5, "buy": 8}.get(btype, 1)
            recency_score += decay * weight
        seq_recency_score = min(recency_score / 10.0, 1.0)  # 归一化到 [0,1]

        # 5. 类目多样性：用户历史行为覆盖了多少个不同类目
        unique_cats = len(set(cat for cat, _, _ in history_cats))
        seq_diversity = min(unique_cats / 10.0, 1.0)  # 假设最多10个类目

        # 6. 行为强度：最近的行为数量（加权）
        total_weight = sum(
            {"view": 1, "click": 2, "cart": 4, "favorite": 5, "buy": 8}.get(btype, 1)
            for _, btype, _ in behaviors
        )
        seq_intensity = min(total_weight / 50.0, 1.0)

        # 7. 购买意图：历史中是否有加购/购买类似类目的商品
        buy_intent = 0.0
        for pid, btype, _ in behaviors:
            if btype in ("cart", "buy", "favorite"):
                p_info = self._product_cache.get(pid, {})
                if p_info.get("category_id") == target_cat:
                    buy_intent = 1.0
                    break
        seq_buy_intent = buy_intent

        return {
            "seq_category_match": float(seq_category_match),
            "seq_brand_match": float(seq_brand_match),
            "seq_price_similarity": float(price_similarity),
            "seq_recency_score": float(seq_recency_score),
            "seq_diversity": float(seq_diversity),
            "seq_intensity": float(seq_intensity),
            "seq_buy_intent": float(seq_buy_intent),
        }

    def _empty_sequence_features(self) -> Dict[str, float]:
        """返回空的序列特征"""
        return {
            "seq_category_match": 0.0,
            "seq_brand_match": 0.0,
            "seq_price_similarity": 0.5,
            "seq_recency_score": 0.0,
            "seq_diversity": 0.0,
            "seq_intensity": 0.0,
            "seq_buy_intent": 0.0,
        }

    def get_sequence_embedding(
        self,
        behaviors: List[Tuple[int, str, float]],
        embedding_dim: int = 16
    ) -> np.ndarray:
        """
        将用户行为序列转换为固定长度的 embedding 向量
        用于 DeepFM-Attention 模型的序列输入

        Args:
            behaviors: 用户历史行为序列
            embedding_dim: embedding 维度

        Returns:
            [seq_len, embedding_dim] 的 numpy 数组
        """
        import datetime
        if not behaviors:
            # 返回零向量
            return np.zeros((20, embedding_dim), dtype=np.float32)

        self._ensure_product_cache()

        seq_len = 20
        result = np.zeros((seq_len, embedding_dim), dtype=np.float32)
        now_ts = behaviors[0][2] if behaviors else 0

        for i, (pid, btype, ts) in enumerate(behaviors[:seq_len]):
            p_info = self._product_cache.get(pid, {})
            cat_id = p_info.get("category_id", 0) % 100
            brand_id = p_info.get("brand_id", 0) % 50

            # 基于类目 ID 生成 embedding（简化版本，实际应用中应该用预训练的 embedding）
            # 使用可重复的伪随机方法保证相同输入有相同输出
            rng = np.random.RandomState(int(cat_id * 1000 + brand_id + i))
            emb = rng.randn(embedding_dim).astype(np.float32)
            emb = emb / (np.linalg.norm(emb) + 1e-8)  # L2 归一化

            # 时间衰减
            hours_ago = (now_ts - ts) / 3600
            decay = np.exp(-0.693 * hours_ago / 24)
            emb = emb * decay

            result[i] = emb

        return result

    def close(self):
        """关闭数据库连接"""
        if self._connection:
            self._connection.close()
            self._connection = None
