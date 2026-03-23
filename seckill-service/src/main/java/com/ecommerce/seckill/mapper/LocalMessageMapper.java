package com.ecommerce.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.seckill.entity.LocalMessage;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LocalMessageMapper extends BaseMapper<LocalMessage> {

    @Select("SELECT * FROM seckill_local_message WHERE status = #{status} AND retry_count < #{maxRetries} ORDER BY create_time ASC LIMIT #{limit}")
    List<LocalMessage> selectPendingMessages(@Param("status") String status, @Param("maxRetries") int maxRetries, @Param("limit") int limit);

    @Update("UPDATE seckill_local_message SET status = #{status}, update_time = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE seckill_local_message SET status = 'failed', error_message = #{errorMessage}, retry_count = retry_count + 1, update_time = NOW() WHERE id = #{id}")
    int updateFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    @Update("UPDATE seckill_local_message SET status = 'confirmed', confirm_time = NOW(), update_time = NOW() WHERE id = #{id}")
    int confirmMessage(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM seckill_local_message WHERE status = 'pending' AND create_time <= #{beforeTime}")
    long countPendingBefore(@Param("beforeTime") LocalDateTime beforeTime);

    @Delete("DELETE FROM seckill_local_message WHERE status = 'confirmed' AND confirm_time < #{beforeTime}")
    int deleteConfirmedBefore(@Param("beforeTime") LocalDateTime beforeTime);
}
