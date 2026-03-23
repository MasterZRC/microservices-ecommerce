package com.ecommerce.recommendation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.recommendation.entity.UserPortrait;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;

import java.util.List;

@Mapper
public interface UserPortraitMapper extends BaseMapper<UserPortrait> {

    @Select("SELECT * FROM user_portrait WHERE user_id = #{userId} LIMIT 1")
    UserPortrait selectByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM user_portrait WHERE user_id IN (#{userIds})")
    List<UserPortrait> selectByUserIds(@Param("userIds") List<Long> userIds);

    @Update("UPDATE user_portrait SET " +
            "active_level = #{portrait.activeLevel}, " +
            "purchase_power = #{portrait.purchasePower}, " +
            "prefer_category_ids = #{portrait.preferCategoryIds}, " +
            "prefer_category_names = #{portrait.preferCategoryNames}, " +
            "prefer_brands = #{portrait.preferBrands}, " +
            "price_range = #{portrait.priceRange}, " +
            "browse_depth = #{portrait.browseDepth}, " +
            "rfm_score = #{portrait.rfmScore}, " +
            "last_active_time = #{portrait.lastActiveTime}, " +
            "behavior_count = #{portrait.behaviorCount}, " +
            "buy_count = #{portrait.buyCount}, " +
            "cart_count = #{portrait.cartCount}, " +
            "update_time = NOW(), " +
            "version = version + 1 " +
            "WHERE user_id = #{portrait.userId} AND version = #{portrait.version}")
    int updateByUserIdWithOptimisticLock(@Param("portrait") UserPortrait portrait);

    @Select("SELECT user_id FROM user_portrait WHERE update_time >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    List<Long> selectRecentlyActiveUserIds();
}
