package com.ecommerce.recommendation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户画像表 - MySQL持久化存储
 *
 * 创新点：
 * 1. 支持用户RFM分层（最近/频率/金额）
 * 2. 存储用户偏好类目Top3和偏好品牌Top3
 * 3. 支持活跃度、消费能力等多维度标签
 * 4. 与Redis缓存双写，保证数据不丢失
 */
@TableName("user_portrait")
public class UserPortrait implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID（唯一索引）
     */
    private Long userId;

    /**
     * 活跃等级：高活/中活/低活/沉默
     */
    private String activeLevel;

    /**
     * 消费能力：高消费/中消费/低消费
     */
    private String purchasePower;

    /**
     * 偏好类目Top1（逗号分隔的多个类目ID）
     */
    private String preferCategoryIds;

    /**
     * 偏好类目名称（逗号分隔）
     */
    private String preferCategoryNames;

    /**
     * 偏好品牌Top3
     */
    private String preferBrands;

    /**
     * 价格偏好：low/middle/high
     */
    private String priceRange;

    /**
     * 浏览深度：浅度/中度/深度
     */
    private String browseDepth;

    /**
     * RFM得分（最近度-频率-金额综合评分）
     */
    private Double rfmScore;

    /**
     * 最近活跃时间
     */
    private LocalDateTime lastActiveTime;

    /**
     * 行为总数（近30天）
     */
    private Integer behaviorCount;

    /**
     * 购买次数（近30天）
     */
    private Integer buyCount;

    /**
     * 加购次数（近30天）
     */
    private Integer cartCount;

    /**
     * 数据版本号（用于乐观锁）
     */
    private Integer version;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getActiveLevel() { return activeLevel; }
    public void setActiveLevel(String activeLevel) { this.activeLevel = activeLevel; }
    public String getPurchasePower() { return purchasePower; }
    public void setPurchasePower(String purchasePower) { this.purchasePower = purchasePower; }
    public String getPreferCategoryIds() { return preferCategoryIds; }
    public void setPreferCategoryIds(String preferCategoryIds) { this.preferCategoryIds = preferCategoryIds; }
    public String getPreferCategoryNames() { return preferCategoryNames; }
    public void setPreferCategoryNames(String preferCategoryNames) { this.preferCategoryNames = preferCategoryNames; }
    public String getPreferBrands() { return preferBrands; }
    public void setPreferBrands(String preferBrands) { this.preferBrands = preferBrands; }
    public String getPriceRange() { return priceRange; }
    public void setPriceRange(String priceRange) { this.priceRange = priceRange; }
    public String getBrowseDepth() { return browseDepth; }
    public void setBrowseDepth(String browseDepth) { this.browseDepth = browseDepth; }
    public Double getRfmScore() { return rfmScore; }
    public void setRfmScore(Double rfmScore) { this.rfmScore = rfmScore; }
    public LocalDateTime getLastActiveTime() { return lastActiveTime; }
    public void setLastActiveTime(LocalDateTime lastActiveTime) { this.lastActiveTime = lastActiveTime; }
    public Integer getBehaviorCount() { return behaviorCount; }
    public void setBehaviorCount(Integer behaviorCount) { this.behaviorCount = behaviorCount; }
    public Integer getBuyCount() { return buyCount; }
    public void setBuyCount(Integer buyCount) { this.buyCount = buyCount; }
    public Integer getCartCount() { return cartCount; }
    public void setCartCount(Integer cartCount) { this.cartCount = cartCount; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
