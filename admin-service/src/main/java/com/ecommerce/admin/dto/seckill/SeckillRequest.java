package com.ecommerce.admin.dto.seckill;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "创建/更新秒杀活动请求")
public class SeckillRequest {

    @NotBlank(message = "活动名称不能为空")
    @Schema(description = "活动名称")
    private String name;

    @NotNull(message = "商品ID不能为空")
    @Schema(description = "商品ID")
    private Long productId;

    @NotNull(message = "秒杀价格不能为空")
    @DecimalMin(value = "0.01", message = "秒杀价格必须大于0")
    @Schema(description = "秒杀价格")
    private BigDecimal seckillPrice;

    @NotNull(message = "秒杀库存不能为空")
    @Min(value = 1, message = "秒杀库存至少为1")
    @Schema(description = "秒杀库存")
    private Integer stock;

    @NotNull(message = "开始时间不能为空")
    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "状态: 0-禁用, 1-启用")
    private Integer status;
}
