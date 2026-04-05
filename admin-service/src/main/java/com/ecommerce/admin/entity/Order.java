package com.ecommerce.admin.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("order_info")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long userId;

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private BigDecimal totalAmount;

    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private String messageId;

    @TableField(exist = false)
    private String statusName;

    @TableField(exist = false)
    private String remark;

    @TableField(exist = false)
    private String userName;
}
