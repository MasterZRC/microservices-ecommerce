package com.ecommerce.admin.dto.seckill;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "秒杀活动响应")
public class SeckillResponse {

    @Schema(description = "活动ID")
    private Long id;

    @Schema(description = "活动名称")
    private String name;

    @Schema(description = "商品ID")
    private Long productId;

    @Schema(description = "商品名称")
    private String productName;

    @Schema(description = "秒杀价格")
    private BigDecimal seckillPrice;

    @Schema(description = "秒杀库存")
    private Integer stock;

    @Schema(description = "已售数量")
    private Integer soldCount;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "状态: 0-禁用, 1-启用")
    private Integer status;

    @Schema(description = "状态名称")
    private String statusName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
