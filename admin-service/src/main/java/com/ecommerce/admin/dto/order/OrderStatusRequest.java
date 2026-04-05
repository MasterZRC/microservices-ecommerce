package com.ecommerce.admin.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "更新订单状态请求")
public class OrderStatusRequest {

    @NotNull(message = "订单状态不能为空")
    @Schema(description = "订单状态: 0-待支付, 1-已支付, 2-已发货, 3-已完成, 4-已取消")
    private Integer status;

    @Schema(description = "备注")
    private String remark;
}
