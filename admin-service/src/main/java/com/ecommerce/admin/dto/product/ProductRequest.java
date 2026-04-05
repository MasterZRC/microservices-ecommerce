package com.ecommerce.admin.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Schema(description = "创建/更新商品请求")
public class ProductRequest {

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 200, message = "商品名称不能超过200字符")
    @Schema(description = "商品名称")
    private String name;

    @Size(max = 1000, message = "商品描述不能超过1000字符")
    @Schema(description = "商品描述")
    private String description;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.01", message = "商品价格必须大于0")
    @Schema(description = "商品价格")
    private BigDecimal price;

    @DecimalMin(value = "0.01", message = "原价必须大于0")
    @Schema(description = "原价")
    private BigDecimal originalPrice;

    @NotNull(message = "分类ID不能为空")
    @Schema(description = "分类ID")
    private Long categoryId;

    @Schema(description = "品牌")
    private String brand;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    @Schema(description = "库存")
    private Integer stock;

    @Schema(description = "图片URL")
    private String imageUrl;

    @Schema(description = "状态: 0-下架, 1-上架")
    private Integer status;
}
