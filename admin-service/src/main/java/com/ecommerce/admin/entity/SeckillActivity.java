package com.ecommerce.admin.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("seckill_activity")
public class SeckillActivity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Long productId;

    private String productName;

    private String productImage;

    private BigDecimal originalPrice;

    private BigDecimal seckillPrice;

    private Integer totalStock;

    private Integer availableStock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer limitPerUser;

    private Integer status;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private Integer stock;

    @TableField(exist = false)
    private Integer soldCount;

    @TableField(exist = false)
    private String statusName;

    public Integer getStock() {
        return this.availableStock;
    }

    public void setStock(Integer stock) {
        this.availableStock = stock;
    }

    public Integer getSoldCount() {
        return this.totalStock - this.availableStock;
    }
}
