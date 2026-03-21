from pydantic import BaseModel, Field, AliasChoices
from typing import List, Dict, Optional
from pydantic import ConfigDict


class UserFeatures(BaseModel):
    """用户特征"""
    model_config = ConfigDict(populate_by_name=True, extra='allow')  # 允许额外字段

    view_1d: int = Field(default=0, validation_alias=AliasChoices('view_1d', 'view1d'))
    click_1d: int = Field(default=0, validation_alias=AliasChoices('click_1d', 'click1d'))
    cart_1d: int = Field(default=0, validation_alias=AliasChoices('cart_1d', 'cart1d'))
    buy_1d: int = Field(default=0, validation_alias=AliasChoices('buy_1d', 'buy1d'))
    view_7d: int = Field(default=0, validation_alias=AliasChoices('view_7d', 'view7d'))
    click_7d: int = Field(default=0, validation_alias=AliasChoices('click_7d', 'click7d'))
    cart_7d: int = Field(default=0, validation_alias=AliasChoices('cart_7d', 'cart7d'))
    buy_7d: int = Field(default=0, validation_alias=AliasChoices('buy_7d', 'buy7d'))
    view_30d: int = Field(default=0, validation_alias=AliasChoices('view_30d', 'view30d'))
    click_30d: int = Field(default=0, validation_alias=AliasChoices('click_30d', 'click30d'))
    cart_30d: int = Field(default=0, validation_alias=AliasChoices('cart_30d', 'cart30d'))
    buy_30d: int = Field(default=0, validation_alias=AliasChoices('buy_30d', 'buy30d'))
    last_active_hours: int = Field(default=24, validation_alias=AliasChoices('last_active_hours', 'lastActiveHours'))
    prefer_category: List[int] = Field(default_factory=list, validation_alias=AliasChoices('prefer_category', 'preferCategory'))
    prefer_brand: List[int] = Field(default_factory=list, validation_alias=AliasChoices('prefer_brand', 'preferBrand'))


class ItemFeatures(BaseModel):
    """商品特征"""
    model_config = ConfigDict(populate_by_name=True, extra='allow')  # 允许额外字段

    category_id: int = Field(default=0, validation_alias=AliasChoices('category_id', 'categoryId'))
    brand_id: int = Field(default=0, validation_alias=AliasChoices('brand_id', 'brandId'))
    price_bucket: int = Field(default=0, validation_alias=AliasChoices('price_bucket', 'priceBucket'))
    sales_bucket: int = Field(default=0, validation_alias=AliasChoices('sales_bucket', 'salesBucket'))
    hot_score: float = Field(default=0.0, validation_alias=AliasChoices('hot_score', 'hotScore'))


class RankRequest(BaseModel):
    """排序请求"""
    model_config = ConfigDict(populate_by_name=True, extra='allow')  # 允许额外字段

    user_id: Optional[int] = Field(default=None, validation_alias=AliasChoices('user_id', 'userId'))
    candidates: List[int]
    user_features: Optional[UserFeatures] = Field(default=None, validation_alias=AliasChoices('user_features', 'userFeatures'))
    item_features: Optional[Dict[str, ItemFeatures]] = Field(default=None, validation_alias=AliasChoices('item_features', 'itemFeatures'))

    def get_user_id(self) -> int:
        return self.user_id or self.userId or 0

    def get_user_features(self) -> Optional[UserFeatures]:
        return self.user_features or self.userFeatures

    def get_item_features(self) -> Optional[Dict[str, ItemFeatures]]:
        return self.item_features or self.itemFeatures


class RankedItem(BaseModel):
    """排序后的商品"""
    model_config = ConfigDict(populate_by_name=True, extra='allow')  # 允许额外字段

    item_id: int = Field(default=0, validation_alias=AliasChoices('item_id', 'itemId'))
    score: float = 0.0

    def get_item_id(self) -> int:
        return self.item_id or self.itemId or 0


class RankResponse(BaseModel):
    """排序响应"""
    model_config = ConfigDict(populate_by_name=True, extra='allow')  # 允许额外字段

    user_id: Optional[int] = Field(default=None, validation_alias=AliasChoices('user_id', 'userId'))
    ranked_items: Optional[List[RankedItem]] = Field(default_factory=list, validation_alias=AliasChoices('ranked_items', 'rankedItems'))


class HealthResponse(BaseModel):
    """健康检查响应"""
    model_config = ConfigDict(populate_by_name=True, extra='allow')

    status: str = "healthy"
    model_loaded: bool = Field(default=False, validation_alias=AliasChoices('model_loaded', 'modelLoaded'))


class TrainRequest(BaseModel):
    """训练请求"""
    epochs: int = 10
    batch_size: int = 256
    num_samples: int = 50000
    save_path: str = "models/deepfm.pt"
    train_ratio: float = 0.8
    use_real_data: bool = Field(default=False, validation_alias=AliasChoices('use_real_data', 'useRealData'))


class LoadDataRequest(BaseModel):
    """从数据库加载数据的请求"""
    min_interactions: int = Field(default=5, validation_alias=AliasChoices('min_interactions', 'minInteractions'))
    db_host: Optional[str] = Field(default="ecommerce-mysql", validation_alias=AliasChoices('db_host', 'dbHost'))
    db_port: int = 3306
    db_user: str = "root"
    db_password: str = "root123"
    db_name: str = "ecommerce"


class LoadDataResponse(BaseModel):
    """从数据库加载数据的响应"""
    status: str
    total_samples: int = 0
    positive_samples: int = 0
    negative_samples: int = 0
    users_count: int = 0


class TrainResponse(BaseModel):
    """训练响应"""
    status: str
    message: str
    metrics: Optional[Dict] = None
    epoch_history: Optional[List[Dict]] = None


class GenerateDataRequest(BaseModel):
    """生成训练数据请求"""
    num_samples: int = 50000
    num_users: int = 10000
    num_items: int = 1000
    train_ratio: float = 0.8


class GenerateDataResponse(BaseModel):
    """生成训练数据响应"""
    status: str
    train_samples: int
    val_samples: int
    positive_ratio: float


class IncrementalUpdateRequest(BaseModel):
    """增量更新请求"""
    samples: List[Dict]  # [{user_features, item_features, label}]
    learning_rate: Optional[float] = None
    epochs: int = 3  # 增量更新只做少量 epoch
    minibatch_size: int = 64


class IncrementalUpdateResponse(BaseModel):
    """增量更新响应"""
    status: str
    updated_samples: int
    loss_delta: Optional[float] = None
    new_model_version: Optional[str] = None
