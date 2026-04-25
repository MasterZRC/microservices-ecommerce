package com.ecommerce.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("seckill_product")
public class SeckillProduct implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long productId;

    private String productName;

    private String productImage;

    private BigDecimal seckillPrice;

    private Integer totalStock;

    private Integer availableStock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer status;

    private Long activityId;
}