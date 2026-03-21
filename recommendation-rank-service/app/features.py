"""
特征工程模块
负责从原始数据构建 DeepFM 模型所需的特征向量
"""
import numpy as np
import logging
from typing import Dict, List, Optional, Tuple
import random
from .schemas import UserFeatures, ItemFeatures

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

    def close(self):
        """关闭数据库连接"""
        if self._connection:
            self._connection.close()
            self._connection = None
